package io.github.aedev.flow.ui.components.videoplayer.ambient

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import io.github.aedev.flow.player.DEFAULT_VIDEO_ASPECT_RATIO
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.ui.theme.PlayerScrim
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Latest smoothed frame. */
data class AmbientFrameState(
    val frame: ImageBitmap? = null,
    /** False once capture has been found permanently impossible, e.g. a protected surface. */
    val supported: Boolean = true,
    /** The light for the page around the player, while a page has asked for it. */
    val page: AmbientPageFrame? = null,
)

/**
 * The latest frame of one player, shared by every surface that draws it, so the player's bars and
 * the page around it read one capture instead of starting a second loop on the same surface.
 */
@Stable
class AmbientFrameHolder {
    var state by mutableStateOf(AmbientFrameState())
        internal set

    internal var pageStyle by mutableStateOf<AmbientPageStyle?>(null)
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun rememberAmbientFrame(
    playerView: PlayerView,
    active: Boolean,
    isPlayingProvider: () -> Boolean = {
        EnhancedPlayerManager.getInstance().getPlayer()?.isPlaying == true
    },
): AmbientFrameState {
    val holder = remember { AmbientFrameHolder() }
    val currentIsPlayingProvider by rememberUpdatedState(isPlayingProvider)
    // A pooled Shorts player has no observable playing state to wait on, so it is asked on a timer.
    val isPlaying =
        remember {
            flow {
                while (true) {
                    emit(currentIsPlayingProvider())
                    delay(IDLE_MS)
                }
            }
        }
    AmbientFrameCaptureEffect(playerView, active, holder, isPlaying)
    return holder.state
}

/** Runs the capture for [playerView] while [active] and publishes each frame into [holder]. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun AmbientFrameCaptureEffect(
    playerView: PlayerView,
    active: Boolean,
    holder: AmbientFrameHolder,
    isPlaying: Flow<Boolean> = mainPlayerIsPlaying(),
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(active, playerView, lifecycleOwner, holder, isPlaying) {
        if (!active) {
            holder.state = AmbientFrameState()
            return@LaunchedEffect
        }
        // Gated on STARTED. Background audio keeps the composition alive and the player playing, so
        // without this the loop kept running a GPU readback every cadence with the screen off,
        // painting a surface nobody could see.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val pipeline =
                AmbientPipeline(
                    pageStyle = { holder.pageStyle },
                    capturePace = {
                        if (holder.pageStyle is AmbientPageStyle.Immersive) AmbientCadence.LIVELY else AmbientCadence.STANDARD
                    },
                )
            // coroutineScope, not a bare launch pair: it suspends until both loops finish, so the
            // pipeline is never abandoned while either loop can still touch its buffers.
            coroutineScope {
                // Capture sets targets at whatever cadence the surface can afford; smoothing
                // walks toward them on its own clock. Both run on Main, so the shared target
                // buffers need no synchronisation — the heavy work inside each hops to Default
                // and is joined before anything is published.
                launch { pipeline.runCapture(playerView, isPlaying) }
                launch { pipeline.runSmoothing { holder.state = it } }
                launch { snapshotFlow { holder.pageStyle }.collect { pipeline.requestPublish() } }
            }
        }
    }
}

@Composable
private fun mainPlayerIsPlaying(): Flow<Boolean> =
    remember {
        EnhancedPlayerManager
            .getInstance()
            .playerState
            .map { it.isPlaying }
            .distinctUntilChanged()
    }

private const val AMBIENT_EDGE_ALPHA = 0.82f
private const val AMBIENT_EDGE_KNEE = 0.30f

@Composable
fun VideoAmbientBackground(
    frame: ImageBitmap?,
    videoAspect: Float?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(PlayerScrim),
    ) {
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                modifier = Modifier.matchParentSize(),
            )
        }
        AmbientEdgeMask(
            videoAspect = videoAspect,
            modifier = Modifier.matchParentSize(),
        )
    }
}

/**
 * Kept separate from the frame so a new frame recomposes only the [Image]; the mask's draw cache
 * is rebuilt solely when the fitted video rectangle changes.
 */
@Composable
private fun AmbientEdgeMask(
    videoAspect: Float?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier.drawWithCache {
                val aspect = videoAspect ?: DEFAULT_VIDEO_ASPECT_RATIO
                val w = size.width
                val h = size.height
                val vw = minOf(w, h * aspect)
                val vh = vw / aspect
                val left = (w - vw) / 2f
                val right = left + vw
                val top = (h - vh) / 2f
                val bottom = top + vh

                val edge = PlayerScrim.copy(alpha = AMBIENT_EDGE_ALPHA)
                val knee = PlayerScrim.copy(alpha = AMBIENT_EDGE_ALPHA * 0.5f)
                val outIn = arrayOf(0f to edge, AMBIENT_EDGE_KNEE to knee, 1f to Color.Transparent)
                val inOut = arrayOf(0f to Color.Transparent, 1f - AMBIENT_EDGE_KNEE to knee, 1f to edge)

                val leftBrush = if (left > 0f) Brush.horizontalGradient(*outIn, startX = 0f, endX = left) else null
                val rightBrush = if (right < w) Brush.horizontalGradient(*inOut, startX = right, endX = w) else null
                val topBrush = if (top > 0f) Brush.verticalGradient(*outIn, startY = 0f, endY = top) else null
                val bottomBrush = if (bottom < h) Brush.verticalGradient(*inOut, startY = bottom, endY = h) else null

                onDrawBehind {
                    leftBrush?.let { drawRect(it, size = Size(left, h)) }
                    rightBrush?.let { drawRect(it, topLeft = Offset(right, 0f), size = Size(w - right, h)) }
                    topBrush?.let { drawRect(it, size = Size(w, top)) }
                    bottomBrush?.let { drawRect(it, topLeft = Offset(0f, bottom), size = Size(w, h - bottom)) }
                }
            },
    )
}
