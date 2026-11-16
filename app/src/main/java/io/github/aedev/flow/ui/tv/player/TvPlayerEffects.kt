package io.github.aedev.flow.ui.tv.player

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModel
import io.github.aedev.flow.ui.screens.player.state.PlayerScreenState
import io.github.aedev.flow.ui.screens.player.state.PlayerSheet
import io.github.aedev.flow.ui.tv.player.state.TvOverlayMode
import io.github.aedev.flow.ui.tv.player.state.TvOverlayState
import io.github.aedev.flow.ui.tv.player.state.TvPlayerOverlayController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine

internal enum class TvPlayerFocusTarget { PLAY_PAUSE, SEEK_BAR, UP_NEXT }

/** Hides the controls once the remote has been idle for the controller's delay; restarts on every press. */
@Composable
internal fun TvOverlayAutoHideEffect(
    overlayController: TvPlayerOverlayController,
    overlayState: TvOverlayState,
    isPlaying: Boolean,
    isScrubbing: Boolean,
) {
    LaunchedEffect(overlayState.mode, overlayState.lastInteractionAtMs, isPlaying, isScrubbing) {
        val deadline = overlayController.autoHideDeadline(isPlaying = isPlaying, isScrubbing = isScrubbing) ?: return@LaunchedEffect
        val waitMs = deadline - SystemClock.uptimeMillis()
        if (waitMs > 0) delay(waitMs)
        overlayController.hide()
    }
}

/**
 * Puts focus where the overlay mode calls for: the player itself while the controls are hidden, so the
 * D-pad reaches playback, and [transportTarget] as they appear. A panel moves focus on its own.
 */
@Composable
internal fun TvOverlayFocusEffect(
    mode: TvOverlayMode,
    rootFocusRequester: FocusRequester,
    transportTarget: () -> FocusRequester,
    onHidden: () -> Unit,
) {
    LaunchedEffect(mode) {
        when (mode) {
            TvOverlayMode.HIDDEN -> {
                onHidden()
                rootFocusRequester.requestFocus()
            }

            TvOverlayMode.TRANSPORT -> {
                transportTarget().requestFocus()
            }

            // The seek bar alone leaves focus on the player, so the D-pad keeps seeking.
            TvOverlayMode.SEEK, TvOverlayMode.PANEL -> {
                Unit
            }
        }
    }
}

/**
 * Comments load once per video, the first time their panel opens; live chat polls only while its panel
 * shows and the player is resumed, as on the phone.
 */
@Composable
internal fun TvPlayerSheetEffects(
    videoId: String,
    screenState: PlayerScreenState,
    viewModel: VideoPlayerViewModel,
    commentsEnabled: Boolean,
) {
    var commentsLoadedFor by remember { mutableStateOf<String?>(null) }
    val commentsOpen = screenState.activeSheet is PlayerSheet.Comments
    LaunchedEffect(videoId, commentsOpen) {
        if (!commentsOpen || !commentsEnabled || commentsLoadedFor == videoId) return@LaunchedEffect
        commentsLoadedFor = videoId
        viewModel.loadComments(videoId)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        combine(lifecycle.currentStateFlow, snapshotFlow { screenState.activeSheet is PlayerSheet.LiveChat }) { state, shown ->
            shown && state.isAtLeast(Lifecycle.State.RESUMED)
        }.collect(viewModel::setLiveChatPanelVisible)
    }
}
