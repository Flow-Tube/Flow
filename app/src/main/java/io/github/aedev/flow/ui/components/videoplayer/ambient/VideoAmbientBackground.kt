package io.github.aedev.flow.ui.components.videoplayer.ambient

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import io.github.aedev.flow.player.DEFAULT_VIDEO_ASPECT_RATIO
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.stream.StoryboardLevel
import io.github.aedev.flow.ui.theme.PlayerScrim
import io.github.aedev.flow.ui.utils.rememberThermalThrottling
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/** What the light shows now. During a crossfade the previous images fade out under it. */
data class AmbientFrameState(
    val frame: ImageBitmap? = null,
    /** False once capture has been found permanently impossible, e.g. a protected surface. */
    val supported: Boolean = true,
    /** The light for the page around the player, while a page has asked for it. */
    val page: AmbientPageFrame? = null,
    val previousFrame: ImageBitmap? = null,
    val previousPage: AmbientPageFrame? = null,
)

/**
 * The light of one player, shared by every surface that draws it, so the player's bars and the
 * page around it read one source instead of starting a second loop on the same surface. It keeps
 * its pipeline across videos, so a new video fades from the last one's light.
 */
@Stable
class AmbientFrameHolder {
    var state by mutableStateOf(AmbientFrameState())
        internal set

    /** How far the crossfade from the previous images has come. Read it only in draw. */
    var fade by mutableFloatStateOf(1f)
        internal set

    internal var pageStyle by mutableStateOf<AmbientPageStyle?>(null)

    internal var seededUrl: String? = null

    /** Follow the picture quickly instead of gliding; read by the pipeline on every sample. */
    var responsive: Boolean = false

    internal val pipeline by lazy {
        AmbientPipeline(
            pageStyle = { pageStyle },
            capturePace = { if (pageStyle is AmbientPageStyle.Immersive) AmbientCadence.LIVELY else AmbientCadence.STANDARD },
            responsive = { responsive },
        )
    }
}

/** Starts the capture for a player that is not the main one, e.g. a pooled Shorts player. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun rememberAmbientFrame(
    playerView: PlayerView,
    active: Boolean,
    isPlaying: Flow<Boolean>,
): AmbientFrameHolder {
    val holder = remember { AmbientFrameHolder() }
    AmbientFrameCaptureEffect(playerView, active, holder, isPlaying)
    return holder
}

/** Whether [this] is playing, from its own callbacks. Collect on the application thread. */
fun Player.isPlayingFlow(): Flow<Boolean> =
    callbackFlow {
        trySend(isPlaying)
        val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    trySend(isPlaying)
                }
            }
        addListener(listener)
        awaitClose { removeListener(listener) }
    }.distinctUntilChanged()

/**
 * Runs the light for [playerView] while [active] and publishes it into [holder]. [storyboard] frames
 * are the source when the video has them; otherwise, or when they fail to load, the live surface
 * is sampled. [thumbnailUrl] lights a new video before its first frame. Live sampling slows to the
 * storyboard's pace while the device runs warm.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun AmbientFrameCaptureEffect(
    playerView: PlayerView,
    active: Boolean,
    holder: AmbientFrameHolder,
    isPlaying: Flow<Boolean> = mainPlayerIsPlaying(),
    storyboard: List<StoryboardLevel> = emptyList(),
    thumbnailUrl: String? = null,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current.applicationContext
    val currentHot by rememberUpdatedState(if (active) rememberThermalThrottling() else false)

    LaunchedEffect(active, playerView, lifecycleOwner, holder, isPlaying, storyboard, thumbnailUrl) {
        if (!active) {
            holder.pipeline.reset()
            holder.seededUrl = null
            holder.state = AmbientFrameState()
            holder.fade = 1f
            return@LaunchedEffect
        }
        val pipeline = holder.pipeline
        val load: suspend (String) -> Bitmap? = { loadAmbientBitmap(context, it, thumbnail = false) }
        // Gated on STARTED. Background audio keeps the composition alive and the player playing, so
        // without this the loop kept running a GPU readback every cadence with the screen off,
        // painting a surface nobody could see.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val seedUrl = thumbnailUrl?.takeIf { it.isNotEmpty() && it != holder.seededUrl }
            holder.seededUrl = thumbnailUrl
            val thumbnail = seedUrl?.let { url -> suspend { loadAmbientBitmap(context, url, thumbnail = true)?.let { stillOf(it, url) } } }
            val frames = ambientStoryboardLevel(storyboard)?.let { AmbientStoryboardFrames(it, load) }
            // coroutineScope, not a bare launch pair: it suspends until every loop finishes, so the
            // pipeline is never left while one of them can still touch its buffers.
            coroutineScope {
                launch { pipeline.runSources(playerView, isPlaying, frames, thumbnail) { currentHot } }
                launch { pipeline.runPresenter(emit = { holder.state = it }, progress = { holder.fade = it }) }
                launch { snapshotFlow { holder.pageStyle }.collect { pipeline.requestPublish() } }
            }
        }
    }
}

private fun stillOf(
    bitmap: Bitmap,
    url: String,
): AmbientStill {
    val rect = if (url.startsWith("http")) thumbnailContentRect(bitmap.width, bitmap.height) else Rect(0, 0, bitmap.width, bitmap.height)
    return AmbientStill(bitmap, rect)
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

/**
 * Draws [previous] fading out under [current] at [progress], the pair at [alpha] overall. The
 * previous image's alpha is solved so two plain draws match one blended layer, with no offscreen pass.
 */
internal inline fun <T : Any> DrawScope.crossfade(
    previous: T?,
    current: T,
    progress: Float,
    alpha: Float,
    draw: DrawScope.(T, Float) -> Unit,
) {
    if (previous == null || progress >= 1f) {
        draw(current, alpha)
        return
    }
    val currentAlpha = alpha * progress
    if (currentAlpha < 1f) draw(previous, previousAlpha(alpha, progress))
    draw(current, currentAlpha)
}

internal fun previousAlpha(
    alpha: Float,
    progress: Float,
): Float = alpha * (1f - progress) / (1f - alpha * progress)

private const val AMBIENT_EDGE_ALPHA = 0.82f
private const val AMBIENT_EDGE_KNEE = 0.30f

/** How far into the falloff a bar's corner next to the video sits. */
private const val AMBIENT_CORNER_REACH = 0.5f

/** The bars around a fitted video. [frames] is read only in draw, so a new frame never recomposes. */
@Composable
fun VideoAmbientBackground(
    frames: AmbientFrameHolder,
    videoAspect: Float?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(PlayerScrim)
                .drawBehind {
                    val state = frames.state
                    val frame = state.frame ?: return@drawBehind
                    crossfade(state.previousFrame, frame, frames.fade, 1f) { image, alpha -> drawCropped(image, alpha) }
                },
    ) {
        AmbientEdgeMask(
            videoAspect = videoAspect,
            modifier = Modifier.matchParentSize(),
        )
    }
}

/** Scales [image] to cover the whole area and centres it, as `ContentScale.Crop` does. */
internal fun DrawScope.drawCropped(
    image: ImageBitmap,
    alpha: Float = 1f,
) {
    val scale = max(size.width / image.width, size.height / image.height)
    val scaled = IntSize((image.width * scale).roundToInt(), (image.height * scale).roundToInt())
    val area = IntSize(size.width.roundToInt(), size.height.roundToInt())
    val offset = Alignment.Center.align(scaled, area, layoutDirection)
    clipRect {
        translate(offset.x.toFloat(), offset.y.toFloat()) {
            drawImage(image = image, dstSize = scaled, alpha = alpha, filterQuality = FilterQuality.Low)
        }
    }
}

/**
 * Each bar fades into the bezel along an ellipse anchored on the video's edge, so the middle of a
 * bar keeps the straight fade while its corners darken, like YouTube's fullscreen scrim. The draw
 * cache is rebuilt only when the fitted video rectangle changes.
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
                val stops = listOf(0f to Color.Transparent, 1f - AMBIENT_EDGE_KNEE to knee, 1f to edge)

                val leftBrush = if (left > 0f) barFalloff(Offset(left, h / 2f), left, h, acrossX = true, stops) else null
                val rightBrush = if (right < w) barFalloff(Offset(right, h / 2f), w - right, h, acrossX = true, stops) else null
                val topBrush = if (top > 0f) barFalloff(Offset(w / 2f, top), top, w, acrossX = false, stops) else null
                val bottomBrush = if (bottom < h) barFalloff(Offset(w / 2f, bottom), h - bottom, w, acrossX = false, stops) else null

                onDrawBehind {
                    leftBrush?.let { drawRect(it, size = Size(left, h)) }
                    rightBrush?.let { drawRect(it, topLeft = Offset(right, 0f), size = Size(w - right, h)) }
                    topBrush?.let { drawRect(it, size = Size(w, top)) }
                    bottomBrush?.let { drawRect(it, topLeft = Offset(0f, bottom), size = Size(w, h - bottom)) }
                }
            },
    )
}

/**
 * A radial falloff from [anchor] on the video's edge, [depth] deep across the bar and stretched
 * along its [length] so the bar's corners next to the video sit at [AMBIENT_CORNER_REACH].
 */
private fun barFalloff(
    anchor: Offset,
    depth: Float,
    length: Float,
    acrossX: Boolean,
    stops: List<Pair<Float, Color>>,
): Brush {
    val shader = RadialGradientShader(anchor, depth, stops.map { it.second }, stops.map { it.first }, TileMode.Clamp)
    val stretch = length / (2f * AMBIENT_CORNER_REACH * depth)
    val matrix = Matrix()
    if (acrossX) matrix.setScale(1f, stretch, anchor.x, anchor.y) else matrix.setScale(stretch, 1f, anchor.x, anchor.y)
    shader.setLocalMatrix(matrix)
    return ShaderBrush(shader)
}
