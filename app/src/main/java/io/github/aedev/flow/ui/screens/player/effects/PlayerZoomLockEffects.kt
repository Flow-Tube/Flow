package io.github.aedev.flow.ui.screens.player.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import io.github.aedev.flow.ui.screens.player.state.PlayerScreenState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** A new video, or a switch into or out of the 360 view, starts unzoomed; the read-out fades after each pinch. */
@Composable
internal fun PlayerZoomEffects(
    videoId: String,
    spherical: Boolean,
    screenState: PlayerScreenState,
    onResetVideoAspectRatio: () -> Unit,
) {
    LaunchedEffect(videoId) {
        onResetVideoAspectRatio()
        screenState.zoomScale = 1f
        screenState.zoomOffsetX = 0f
        screenState.zoomOffsetY = 0f
        screenState.showZoomIndicator = false
        screenState.zoomIndicatorSequence = 0
    }

    LaunchedEffect(spherical) {
        screenState.zoomScale = 1f
        screenState.zoomOffsetX = 0f
        screenState.zoomOffsetY = 0f
    }

    LaunchedEffect(screenState) {
        snapshotFlow { screenState.zoomIndicatorSequence }
            .collectLatest {
                if (!screenState.showZoomIndicator) return@collectLatest
                delay(if (screenState.zoomScale > 1.02f) 900 else 600)
                screenState.showZoomIndicator = false
            }
    }
}

@Composable
internal fun TouchLockDisableEffect(
    screenState: PlayerScreenState,
    lockModeEnabled: Boolean,
) {
    LaunchedEffect(lockModeEnabled) {
        if (!lockModeEnabled && screenState.isTouchLocked) {
            screenState.isTouchLocked = false
        }
    }
}
