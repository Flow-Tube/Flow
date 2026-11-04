package io.github.aedev.flow.ui.components.videoplayer

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.PlayerView
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.PictureInPictureHelper
import io.github.aedev.flow.player.PipBounds
import io.github.aedev.flow.player.toDisplayAspectRatioOrNull
import io.github.aedev.flow.ui.components.shared.clipToCornerRadius
import io.github.aedev.flow.ui.components.shared.flowPlayerViewLayout
import io.github.aedev.flow.ui.components.videoplayer.ambient.VideoAmbientBackground
import io.github.aedev.flow.ui.components.videoplayer.ambient.rememberAmbientFrame
import kotlin.math.roundToInt

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerSurface(
    video: Video,
    resizeMode: Int,
    modifier: Modifier = Modifier,
    onVideoAspectRatioChanged: ((Float) -> Unit)? = null,
    cornerRadiusDp: Float = 0f,
    ambientMode: Boolean = false,
) {
    val ambientActive = ambientMode && resizeMode == 0
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var surfaceRestoreTrigger by remember { mutableIntStateOf(0) }
    var attachedVideoId by remember { mutableStateOf<String?>(null) }
    var lastAudioOnlySkipLogKey by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    val currentVideoId by rememberUpdatedState(video.id)
    var ambientVideoAspect by remember { mutableStateOf<Float?>(null) }
    val reportAspectRatio by
        rememberUpdatedState<(Float) -> Unit> { ratio ->
            ambientVideoAspect = ratio
            onVideoAspectRatioChanged?.invoke(ratio)
        }
    val cornerRadiusPx = with(density) { cornerRadiusDp.dp.toPx() }

    val playerView =
        remember {
            Log.d("EnhancedVideoPlayer", "Creating shared PlayerView (sdk=${Build.VERSION.SDK_INT})")
            (LayoutInflater.from(context).inflate(flowPlayerViewLayout(), null) as PlayerView).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                setKeepContentOnPlayerReset(true)
            }
        }

    DisposableEffect(playerView) {
        val surfaceView = playerView.videoSurfaceView as? SurfaceView
        if (surfaceView == null) {
            return@DisposableEffect onDispose { }
        }

        val manager = EnhancedPlayerManager.getInstance()
        var attachedHolder: SurfaceHolder? = null
        var attachedSurface: Surface? = null

        fun attachIfValid(holder: SurfaceHolder) {
            val surface = holder.surface
            if (!surface.isValid || (attachedHolder === holder && attachedSurface === surface)) return
            attachedHolder = holder
            attachedSurface = surface
            manager.attachVideoSurface(holder, forceAttach = true)
        }

        val callback =
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    attachIfValid(holder)
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) {
                    attachIfValid(holder)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    if (attachedHolder === holder) {
                        manager.detachVideoSurface(holder)
                        attachedHolder = null
                        attachedSurface = null
                    }
                }
            }

        surfaceView.holder.addCallback(callback)
        attachIfValid(surfaceView.holder)

        onDispose {
            surfaceView.holder.removeCallback(callback)
            attachedHolder?.let(manager::detachVideoSurface)
        }
    }

    val videoSizeListener =
        remember {
            object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    val playerMediaId =
                        EnhancedPlayerManager
                            .getInstance()
                            .getPlayer()
                            ?.currentMediaItem
                            ?.mediaId
                    if (playerMediaId == null || playerMediaId == currentVideoId) {
                        videoSize.toDisplayAspectRatioOrNull()?.let(reportAspectRatio)
                    }
                }
            }
        }

    DisposableEffect(playerView) {
        onDispose {
            playerView.player?.removeListener(videoSizeListener)
            playerView.player = null
        }
    }

    DisposableEffect(playerView) {
        onDispose { PictureInPictureHelper.setVideoViewBounds(context as? Activity, null) }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START, lifecycleOwner) { surfaceRestoreTrigger++ }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME, lifecycleOwner) { surfaceRestoreTrigger++ }

    val currentSurfaceRestoreTrigger = surfaceRestoreTrigger

    val ambientFrame = rememberAmbientFrame(playerView, ambientActive)

    Box(modifier = modifier.fillMaxSize()) {
        if (ambientActive) {
            // Held back until the first frame is sampled, then faded in: before that the glow is only
            // its dark base, which used to black out the backdrop around the video for a moment.
            val ambientAppear = remember { Animatable(0f) }
            val ambientFade = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
            val ambientReady = ambientFrame.frame != null
            LaunchedEffect(ambientReady) { if (ambientReady) ambientAppear.animateTo(1f, ambientFade) }
            VideoAmbientBackground(
                frame = ambientFrame.frame,
                videoAspect = ambientVideoAspect,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = ambientAppear.value },
            )
        }
        AndroidView(
            factory = { playerView },
            update = { view ->
                @Suppress("UNUSED_VARIABLE")
                val restoreTrigger = currentSurfaceRestoreTrigger
                val manager = EnhancedPlayerManager.getInstance()
                val newPlayer = manager.getPlayer()
                val oldPlayer = view.player
                val videoChanged = attachedVideoId != video.id

                if (oldPlayer !== newPlayer || videoChanged) {
                    val isVideoSwitch = attachedVideoId != null && videoChanged
                    val playerMediaId = newPlayer?.currentMediaItem?.mediaId
                    val alreadyRenderingThisVideo = playerMediaId == video.id
                    oldPlayer?.removeListener(videoSizeListener)
                    if (oldPlayer === newPlayer && oldPlayer != null && !alreadyRenderingThisVideo) {
                        view.player = null
                    }
                    newPlayer?.addListener(videoSizeListener)
                    view.player = newPlayer
                    attachedVideoId = video.id
                    reportAspectRatio(
                        if (isVideoSwitch && !alreadyRenderingThisVideo) {
                            16f / 9f
                        } else {
                            newPlayer?.videoSize?.toDisplayAspectRatioOrNull() ?: (16f / 9f)
                        },
                    )
                }

                if (newPlayer != null && manager.isInAudioOnlyMode()) {
                    val lifecycleReadyForVideo =
                        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    val displayInteractive = context.isDisplayInteractive()
                    if (lifecycleReadyForVideo && displayInteractive) {
                        Log.d("VideoPlayerSurface", "Restoring video output after audio-only background mode")
                        lastAudioOnlySkipLogKey = null
                        manager.restoreVideoOutput()
                    } else {
                        val skipLogKey = lifecycleReadyForVideo to displayInteractive
                        if (lastAudioOnlySkipLogKey != skipLogKey) {
                            Log.d(
                                "VideoPlayerSurface",
                                "Keeping audio-only background mode (started=$lifecycleReadyForVideo interactive=$displayInteractive)",
                            )
                            lastAudioOnlySkipLogKey = skipLogKey
                        }
                    }
                }

                if (newPlayer != null &&
                    newPlayer.playbackState == Player.STATE_IDLE &&
                    newPlayer.currentMediaItem != null
                ) {
                    Log.d("VideoPlayerSurface", "PlayerView attached; player IDLE with media - calling prepare()")
                    newPlayer.prepare()
                }

                view.subtitleView?.let { subtitleView ->
                    if (subtitleView.visibility != View.GONE) {
                        subtitleView.visibility = View.GONE
                    }
                }

                val desiredResizeMode =
                    when (resizeMode) {
                        0 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        1 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                        2 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                if (view.resizeMode != desiredResizeMode) {
                    view.resizeMode = desiredResizeMode
                }

                view.setBackgroundColor(
                    if (ambientActive) android.graphics.Color.TRANSPARENT else android.graphics.Color.BLACK,
                )

                view.clipToCornerRadius(cornerRadiusPx)
            },
            modifier = Modifier.fillMaxSize().then(rememberPipSourceHintModifier(context as? Activity)),
        )
    }
}

private fun Context.isDisplayInteractive(): Boolean =
    runCatching {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    }.getOrDefault(true)

private val Float.dp: androidx.compose.ui.unit.Dp
    get() =
        androidx.compose.ui.unit
            .Dp(this)

/**
 * Publishes where the video is drawn as the PiP source hint. The sheet and the fullscreen layouts move
 * it with graphics layers, which no layout pass reports, so a hint read on layout left the next entry
 * growing from above or below the video (#1671). onLayoutRectChanged sees those moves, once they stop,
 * but its rect leaves out scale, so the bounds themselves come from the coordinates.
 */
@Composable
private fun rememberPipSourceHintModifier(activity: Activity?): Modifier {
    val placed = remember { Ref<LayoutCoordinates>() }
    return remember(activity, placed) {
        Modifier
            .onGloballyPositioned { placed.value = it }
            .onLayoutRectChanged {
                val bounds = placed.value?.takeIf { it.isAttached }?.boundsInWindow()
                if (bounds != null && !bounds.isEmpty) {
                    PictureInPictureHelper.setVideoViewBounds(
                        activity,
                        PipBounds(
                            bounds.left.roundToInt(),
                            bounds.top.roundToInt(),
                            bounds.right.roundToInt(),
                            bounds.bottom.roundToInt(),
                        ),
                    )
                }
            }
    }
}
