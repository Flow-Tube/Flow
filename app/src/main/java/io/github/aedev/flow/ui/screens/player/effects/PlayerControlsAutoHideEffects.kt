package io.github.aedev.flow.ui.screens.player.effects

import androidx.compose.runtime.*
import io.github.aedev.flow.ui.components.videoplayer.gesture.DOUBLE_TAP_SEEK_WINDOW_MS
import io.github.aedev.flow.ui.screens.player.state.PlayerScreenState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private const val AUTO_HIDE_CONTROLS_DELAY_MS = 3_000L
private const val GESTURE_OVERLAY_HIDE_DELAY_MS = 1_000L

@Composable
internal fun AutoHideControlsEffect(
    showControls: Boolean,
    isPlaying: Boolean,
    hasEnded: Boolean,
    lastInteractionTimestamp: Long,
    isTouchLocked: Boolean = false,
    isScrubbing: Boolean = false,
    onHideControls: () -> Unit,
) {
    LaunchedEffect(
        showControls,
        isPlaying,
        hasEnded,
        lastInteractionTimestamp,
        isTouchLocked,
        isScrubbing,
    ) {
        if (showControls && isPlaying && !hasEnded && !isTouchLocked && !isScrubbing) {
            delay(AUTO_HIDE_CONTROLS_DELAY_MS)
            onHideControls()
        }
    }
}

@Composable
internal fun GestureOverlayAutoHideEffect(screenState: PlayerScreenState) {
    LaunchedEffect(screenState) {
        snapshotFlow { screenState.showBrightnessOverlay to screenState.brightnessLevel }
            .collectLatest { (visible, _) ->
                if (visible) {
                    delay(GESTURE_OVERLAY_HIDE_DELAY_MS)
                    screenState.showBrightnessOverlay = false
                }
            }
    }

    LaunchedEffect(screenState) {
        snapshotFlow { screenState.showVolumeOverlay to screenState.volumeLevel }
            .collectLatest { (visible, _) ->
                if (visible) {
                    delay(GESTURE_OVERLAY_HIDE_DELAY_MS)
                    screenState.showVolumeOverlay = false
                }
            }
    }

    LaunchedEffect(screenState) {
        snapshotFlow { screenState.showSpeedSwipeOverlay to screenState.speedSwipeLevel }
            .collectLatest { (visible, _) ->
                if (visible) {
                    delay(GESTURE_OVERLAY_HIDE_DELAY_MS)
                    screenState.showSpeedSwipeOverlay = false
                }
            }
    }

    LaunchedEffect(screenState.seekAccumulation, screenState.showSeekForwardAnimation) {
        if (screenState.showSeekForwardAnimation) {
            delay(DOUBLE_TAP_SEEK_WINDOW_MS)
            screenState.showSeekForwardAnimation = false
            delay(400)
            screenState.seekAccumulation = 10
        }
    }

    LaunchedEffect(screenState.chapterSkip) {
        if (screenState.chapterSkip != null) {
            delay(DOUBLE_TAP_SEEK_WINDOW_MS)
            screenState.showChapterSkip = false
        }
    }

    LaunchedEffect(screenState.seekAccumulation, screenState.showSeekBackAnimation) {
        if (screenState.showSeekBackAnimation) {
            delay(DOUBLE_TAP_SEEK_WINDOW_MS)
            screenState.showSeekBackAnimation = false
            delay(400)
            screenState.seekAccumulation = 10
        }
    }
}
