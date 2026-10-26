package io.github.aedev.flow.ui.screens.player.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.ui.components.videoplayer.PlayerDraggableState
import io.github.aedev.flow.ui.components.videoplayer.PlayerSheetValue
import io.github.aedev.flow.ui.components.videoplayer.motion.EXPANDED_SURFACES_MOUNT_FRACTION
import io.github.aedev.flow.ui.components.videoplayer.motion.EXPANDED_SURFACES_UNMOUNT_FRACTION
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModel
import io.github.aedev.flow.ui.screens.player.state.PlayerLayoutMode
import io.github.aedev.flow.ui.screens.player.state.PlayerScreenState
import io.github.aedev.flow.ui.screens.player.state.PlayerSheet
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** Collapsing the sheet, or leaving fullscreen, drops every surface the expanded player owns. */
@Composable
internal fun PlayerSheetCollapseSyncEffects(
    playerSheetState: PlayerDraggableState,
    screenState: PlayerScreenState,
) {
    LaunchedEffect(playerSheetState.currentValue) {
        if (playerSheetState.currentValue == PlayerSheetValue.Collapsed) {
            screenState.isFullscreen = false
            screenState.isFullscreenPortrait = false
            screenState.dismissMediaSheets()
            screenState.zoomScale = 1f
            screenState.zoomOffsetX = 0f
            screenState.zoomOffsetY = 0f
            screenState.showZoomIndicator = false
        }
    }

    LaunchedEffect(screenState.isFullscreen) {
        screenState.dismissMediaSheets()
        screenState.exitDragOffsetY = 0f
    }
}

/**
 * Reading comments is interacting with this video, so the autoplay countdown waits until the
 * comments sheet or side panel closes. A countdown that starts while comments are already open
 * stays on screen and only ticks once they close. The hold only applies while the app is in
 * front: comments left open behind a locked screen, the background or PiP keep composed, and
 * autoplay must still advance there.
 */
@Composable
internal fun AutoplayCommentsHoldEffect(
    screenState: PlayerScreenState,
    viewModel: VideoPlayerViewModel,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        combine(
            lifecycle.currentStateFlow,
            snapshotFlow { screenState.activeSheet is PlayerSheet.Comments },
        ) { state, commentsOpen -> commentsOpen && state.isAtLeast(Lifecycle.State.RESUMED) }
            .collect(viewModel::setAutoplayHold)
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.setAutoplayHold(false) }
    }
}

/**
 * Live chat is a poll loop, so it only runs while a surface is actually showing it: the sheet or
 * side panel the user raised, or the wide layout's detail column. The expanded player keeps those
 * surfaces composed once opened, so composition alone is not evidence anyone is watching, and an
 * open chat behind the background, a locked screen or PiP is not on screen either.
 */
@Composable
internal fun LiveChatVisibilityEffect(
    playerSheetState: PlayerDraggableState,
    screenState: PlayerScreenState,
    layoutMode: PlayerLayoutMode,
    viewModel: VideoPlayerViewModel,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, layoutMode, lifecycle) {
        combine(
            lifecycle.currentStateFlow,
            snapshotFlow {
                screenState.activeSheet is PlayerSheet.LiveChat ||
                    (
                        layoutMode == PlayerLayoutMode.WIDE &&
                            screenState.showLiveChatPanel &&
                            playerSheetState.currentValue == PlayerSheetValue.Expanded
                    )
            },
        ) { state, chatShown -> chatShown && state.isAtLeast(Lifecycle.State.RESUMED) }
            .collect(viewModel::setLiveChatPanelVisible)
    }
}

/**
 * A window with no room for a detail layout beside or under the video has nothing to show in
 * landscape but the video, so it goes fullscreen on its own rather than letterboxing the page.
 */
@Composable
internal fun PhoneLandscapeFullscreenEffect(
    isLandscape: Boolean,
    isLargeWindow: Boolean,
    isInPipMode: Boolean,
    playerSheetState: PlayerDraggableState,
    screenState: PlayerScreenState,
) {
    LaunchedEffect(isLandscape, isLargeWindow, isInPipMode) {
        if (isLandscape && !isLargeWindow && !isInPipMode && playerSheetState.currentValue == PlayerSheetValue.Expanded) {
            screenState.isFullscreen = true
        }
    }
}

/**
 * Takes a video the viewer opened to fullscreen through the same switch as the fullscreen button,
 * once its sheet has finished opening and the player knows the video's shape: switching before the
 * shape is known would turn the window to landscape for a vertical video and straight back. A sheet
 * the viewer collapses first drops the request.
 */
@Composable
internal fun OpenInFullscreenEffect(
    videoId: String,
    requestedVideoId: String?,
    playerSheetState: PlayerDraggableState,
    screenState: PlayerScreenState,
    isInPipMode: Boolean,
    isVideoShapeKnown: () -> Boolean,
    onConsumed: (String) -> Unit,
) {
    val currentIsInPipMode by rememberUpdatedState(isInPipMode)
    val currentIsVideoShapeKnown by rememberUpdatedState(isVideoShapeKnown)
    LaunchedEffect(requestedVideoId, videoId) {
        if (requestedVideoId == null || requestedVideoId != videoId) return@LaunchedEffect
        var wasExpanded = false
        val expanded =
            snapshotFlow {
                val isExpanded = playerSheetState.currentValue == PlayerSheetValue.Expanded
                val opened = playerSheetState.isSettled && playerSheetState.openOrigin == null
                isExpanded to (isExpanded && opened && currentIsVideoShapeKnown())
            }.first { (isExpanded, ready) ->
                wasExpanded = wasExpanded || isExpanded
                ready || (wasExpanded && !isExpanded)
            }.first
        if (expanded && !currentIsInPipMode && !screenState.isFullscreen) {
            screenState.isFullscreenPortrait = false
            screenState.isFullscreen = true
        }
        onConsumed(requestedVideoId)
    }
}

/**
 * Reconciles the sheet anchor with the session the view model reports: a dismissed player
 * minimises, a fresh load expands, and a restored session resumes once the user expands it. A
 * queue advance that happened while the user was in the mini player must not expand it.
 */
@Composable
internal fun PlayerSheetSessionEffects(
    playerSheetState: PlayerDraggableState,
    uiState: VideoPlayerUiState,
    queueTitle: () -> String?,
    viewModel: VideoPlayerViewModel,
    onMinimize: () -> Unit,
) {
    var keepMiniOnQueueAutoAdvance by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.shouldDismissPlayer) {
        if (uiState.shouldDismissPlayer) {
            onMinimize()
            viewModel.resetDismissState()
        }
    }

    LaunchedEffect(Unit) {
        EnhancedPlayerManager.getInstance().queueAutoAdvanceEvent.collect {
            keepMiniOnQueueAutoAdvance = playerSheetState.currentValue == PlayerSheetValue.Collapsed
        }
    }

    LaunchedEffect(uiState.isLoading) {
        val isQueueAutoAdvanceInMiniPlayer =
            keepMiniOnQueueAutoAdvance &&
                queueTitle() != null &&
                playerSheetState.currentValue == PlayerSheetValue.Collapsed

        if (
            uiState.isLoading &&
            !uiState.isRestoredSession &&
            !uiState.resumedInMiniPlayer &&
            !isQueueAutoAdvanceInMiniPlayer
        ) {
            playerSheetState.expand()
        }
        if (!uiState.isLoading) {
            if (uiState.resumedInMiniPlayer) {
                viewModel.clearResumedInMiniPlayer()
            }
            keepMiniOnQueueAutoAdvance = false
        }
    }

    LaunchedEffect(playerSheetState.currentValue) {
        if (playerSheetState.currentValue == PlayerSheetValue.Expanded && uiState.isRestoredSession) {
            viewModel.resumeRestoredSession()
        }
    }
}

/**
 * Composing or dropping the controls, subtitle, gesture and sponsor surfaces costs one long
 * frame. Doing it at the halfway point of the morph landed that frame inside the spring or
 * right under the finger, so both now wait until the sheet is fully at rest near an anchor.
 * While the surfaces are composed but the sheet is past halfway they are not placed, which
 * keeps the pop at the midpoint pixel-identical to mounting there.
 */
@Composable
internal fun rememberExpandedSurfacesMounted(
    playerSheetState: PlayerDraggableState,
    isMinimized: Boolean,
): State<Boolean> {
    val expandedSurfacesMounted = remember { mutableStateOf(!isMinimized) }
    LaunchedEffect(playerSheetState) {
        snapshotFlow {
            when {
                !playerSheetState.isSettled -> null
                playerSheetState.fraction > EXPANDED_SURFACES_UNMOUNT_FRACTION -> false
                playerSheetState.fraction < EXPANDED_SURFACES_MOUNT_FRACTION -> true
                else -> null
            }
        }.collect { verdict -> if (verdict != null) expandedSurfacesMounted.value = verdict }
    }
    return expandedSurfacesMounted
}

/**
 * Any of these tears down the gesture modifier (or restarts its pointer coroutine) without an
 * onDragCancel, so a drag in flight would otherwise strand the seek preview on screen and keep
 * the controls from ever auto-hiding again.
 */
@Composable
internal fun SeekDragResetEffect(
    screenState: PlayerScreenState,
    isMinimized: Boolean,
    isInPipMode: Boolean,
) {
    LaunchedEffect(screenState.isFullscreen, screenState.isTouchLocked, isMinimized, isInPipMode) {
        screenState.isSeekDragging = false
    }
}

@Composable
internal fun PlayerEndAndPipEffects(
    playerState: EnhancedPlayerState,
    playerSheetState: PlayerDraggableState,
    screenState: PlayerScreenState,
    isInPipMode: Boolean,
    isLandscape: Boolean,
    pipForcedFullscreen: MutableState<Boolean>,
) {
    LaunchedEffect(playerState.hasEnded, playerSheetState.currentValue, isInPipMode) {
        if (
            playerState.hasEnded &&
            playerSheetState.currentValue == PlayerSheetValue.Expanded &&
            !isInPipMode
        ) {
            screenState.showControls = true
        }
    }

    LaunchedEffect(isInPipMode, isLandscape) {
        if (isInPipMode) {
            playerSheetState.expand()
            if (!screenState.isFullscreen) {
                pipForcedFullscreen.value = true
                screenState.isFullscreen = true
            }
            screenState.showControls = false
        } else if (pipForcedFullscreen.value && !isLandscape) {
            pipForcedFullscreen.value = false
            screenState.isFullscreen = false
        }
    }
}
