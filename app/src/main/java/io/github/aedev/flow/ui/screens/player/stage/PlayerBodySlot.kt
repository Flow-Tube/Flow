package io.github.aedev.flow.ui.screens.player.stage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.media3.common.util.UnstableApi
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.ui.components.videoplayer.controls.LockModeTouchShield
import io.github.aedev.flow.ui.screens.player.EnhancedVideoPlayerScreen

/** The scrollable page under the video, plus the shield that swallows taps while touch is locked. */
@UnstableApi
@Composable
internal fun PlayerBodySlot(
    session: VideoPlayerStageSession,
    alpha: () -> Float,
    videoHeightPx: () -> Float,
    isLandscape: Boolean,
    localIsInPipMode: Boolean,
    onClose: () -> Unit,
    onNavigateToChannel: (String) -> Unit,
    onNavigateToShorts: (String) -> Unit,
) {
    val video = session.video
    val screenState = session.screenState
    val playerViewModel = session.viewModel

    Box(Modifier.fillMaxSize()) {
        EnhancedVideoPlayerScreen(
            viewModel = playerViewModel,
            video = video,
            alpha = alpha,
            videoPlayerHeightPx = videoHeightPx,
            screenState = screenState,
            isFullscreenShown = session.isFullscreenShown,
            prefs = session.prefs,
            ambientGlowFrames = session.ambient.frames.takeIf { session.ambient.glow },
            ambientImmersive = session.ambient.immersive,
            onVideoClick = { clickedVideo ->
                if (clickedVideo.isShort) {
                    onClose()
                    EnhancedPlayerManager.getInstance().stop()
                    onNavigateToShorts(clickedVideo.id)
                } else {
                    playerViewModel.playVideo(clickedVideo)
                }
            },
            onChannelClick = { channelId ->
                onNavigateToChannel(channelId)
            },
        )

        if (screenState.isTouchLocked && !screenState.isFullscreen && !localIsInPipMode && !isLandscape) {
            LockModeTouchShield(
                onRevealUnlock = {
                    screenState.revealLockOverlay()
                    screenState.onInteraction()
                },
                onUnlock = {
                    screenState.isTouchLocked = false
                    screenState.showControls = true
                    screenState.onInteraction()
                },
                modifier =
                    Modifier
                        .matchParentSize()
                        .zIndex(2f),
            )
        }
    }
}
