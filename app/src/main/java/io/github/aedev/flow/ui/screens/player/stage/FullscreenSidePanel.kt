package io.github.aedev.flow.ui.screens.player.stage

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.util.UnstableApi
import io.github.aedev.flow.data.local.FullscreenSidePanelWidth
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.ui.screens.player.dialogs.PlayerChaptersSheetHost
import io.github.aedev.flow.ui.screens.player.dialogs.PlayerCommentsPanelHost
import io.github.aedev.flow.ui.screens.player.dialogs.PlayerDescriptionSheetHost
import io.github.aedev.flow.ui.screens.player.dialogs.PlayerLiveChatColumn
import io.github.aedev.flow.ui.screens.player.dialogs.PlayerQueueSheetHost
import io.github.aedev.flow.ui.screens.player.dialogs.PlayerSettingsSheetHost
import io.github.aedev.flow.ui.screens.player.dialogs.PlayerSleepTimerSheetHost
import io.github.aedev.flow.ui.screens.player.dialogs.PlayerTranscriptSheetHost
import io.github.aedev.flow.ui.screens.player.state.PlayerCommentsUiState
import io.github.aedev.flow.ui.screens.player.state.PlayerScreenState
import io.github.aedev.flow.ui.screens.player.state.PlayerSheet
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.ui.screens.player.state.hasTranscriptTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The drawer a landscape fullscreen player slides in from the trailing edge instead of raising a
 * bottom sheet, plus the width it leaves for the video beside it.
 */
@Stable
internal class FullscreenSidePanelState(
    val visible: Boolean,
    val showSettings: Boolean,
    val showChapters: Boolean,
    val showDescription: Boolean,
    val showTranscript: Boolean,
    val showLiveChat: Boolean,
    val showComments: Boolean,
    val showSleepTimer: Boolean,
    val showQueue: Boolean,
    val drawerWidth: Dp,
    val drawerOffset: Dp,
    val panelHeight: Dp,
    val playerWidth: Dp,
    val dragModifier: Modifier,
    val close: () -> Unit,
)

@Composable
internal fun rememberFullscreenSidePanelState(
    screenState: PlayerScreenState,
    playerUiState: VideoPlayerUiState,
    commentsEnabled: Boolean,
    canUseFullscreenSidePanel: Boolean,
    maxWidth: Dp,
    maxHeight: Dp,
    widthPreset: FullscreenSidePanelWidth,
    scope: CoroutineScope,
): FullscreenSidePanelState {
    val density = LocalDensity.current
    val showSettingsSurface = screenState.isSettingsOpen
    val showChaptersSidePanel = screenState.activeSheet == PlayerSheet.Chapters
    // The drawer hosted every other sheet but this one, and the bottom sheet is suppressed wherever
    // a drawer exists — so opening the description in fullscreen drew nothing at all.
    val showDescriptionSidePanel = screenState.activeSheet == PlayerSheet.Description
    val showTranscriptSidePanel = screenState.activeSheet == PlayerSheet.Transcript
    val showLiveChatSidePanel =
        screenState.activeSheet == PlayerSheet.LiveChat(fullscreen = true) && playerUiState.isLiveChatAvailable
    val showCommentsSidePanel =
        screenState.activeSheet == PlayerSheet.Comments(fullscreen = true) && commentsEnabled
    val showSleepTimerSidePanel = screenState.activeSheet == PlayerSheet.SleepTimer
    val showQueueSidePanel = screenState.activeSheet == PlayerSheet.Queue
    val fullscreenSidePanelVisible =
        canUseFullscreenSidePanel &&
            (
                showSettingsSurface || showChaptersSidePanel || showDescriptionSidePanel || showTranscriptSidePanel ||
                    showLiveChatSidePanel || showCommentsSidePanel || showSleepTimerSidePanel || showQueueSidePanel
            )
    val fullscreenDrawerWidth = sidePanelWidth(maxWidth, widthPreset)
    val fullscreenDrawerWidthPx = with(density) { fullscreenDrawerWidth.toPx() }
    val fullscreenDrawerOffsetPx = remember { Animatable(0f) }

    LaunchedEffect(fullscreenSidePanelVisible, fullscreenDrawerWidthPx) {
        fullscreenDrawerOffsetPx.updateBounds(
            lowerBound = 0f,
            upperBound = fullscreenDrawerWidthPx,
        )
        if (fullscreenSidePanelVisible) {
            if (fullscreenDrawerOffsetPx.value == 0f) {
                fullscreenDrawerOffsetPx.snapTo(fullscreenDrawerWidthPx)
            }
            fullscreenDrawerOffsetPx.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 260),
            )
        }
    }

    fun closeFullscreenSidePanel() {
        scope.launch {
            fullscreenDrawerOffsetPx.animateTo(
                targetValue = fullscreenDrawerWidthPx,
                animationSpec = tween(durationMillis = 220),
            )
            screenState.dismissMediaSheets()
        }
    }
    val fullscreenSidePanelDragModifier =
        Modifier.pointerInput(fullscreenDrawerWidthPx, fullscreenSidePanelVisible) {
            if (!fullscreenSidePanelVisible || fullscreenDrawerWidthPx <= 0f) return@pointerInput
            val velocityTracker = VelocityTracker()
            detectHorizontalDragGestures(
                onHorizontalDrag = { change, dragAmount ->
                    velocityTracker.addPointerInputChange(change)
                    change.consume()
                    scope.launch {
                        fullscreenDrawerOffsetPx.snapTo(
                            (fullscreenDrawerOffsetPx.value + dragAmount)
                                .coerceIn(0f, fullscreenDrawerWidthPx),
                        )
                    }
                },
                onDragCancel = {
                    velocityTracker.resetTracking()
                    scope.launch {
                        fullscreenDrawerOffsetPx.animateTo(
                            targetValue = 0f,
                            animationSpec = tween(durationMillis = 220),
                        )
                    }
                },
                onDragEnd = {
                    val velocityX = velocityTracker.calculateVelocity().x
                    velocityTracker.resetTracking()
                    val shouldDismiss =
                        velocityX > 900f ||
                            fullscreenDrawerOffsetPx.value > fullscreenDrawerWidthPx * 0.38f
                    if (shouldDismiss) {
                        closeFullscreenSidePanel()
                    } else {
                        scope.launch {
                            fullscreenDrawerOffsetPx.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(durationMillis = 220),
                            )
                        }
                    }
                },
            )
        }
    val fullscreenDrawerOffset = with(density) { fullscreenDrawerOffsetPx.value.toDp() }
    val fullscreenReservedWidth =
        if (fullscreenSidePanelVisible) {
            (fullscreenDrawerWidth - fullscreenDrawerOffset).coerceIn(0.dp, fullscreenDrawerWidth)
        } else {
            0.dp
        }
    val fullscreenSidePanelHeight = maxHeight
    val fullscreenPlayerWidth =
        if (fullscreenSidePanelVisible) {
            (maxWidth - fullscreenReservedWidth).coerceAtLeast(sidePanelMinPlayerWidth(maxWidth, widthPreset))
        } else {
            maxWidth
        }

    return FullscreenSidePanelState(
        visible = fullscreenSidePanelVisible,
        showSettings = showSettingsSurface,
        showChapters = showChaptersSidePanel,
        showDescription = showDescriptionSidePanel,
        showTranscript = showTranscriptSidePanel,
        showLiveChat = showLiveChatSidePanel,
        showComments = showCommentsSidePanel,
        showSleepTimer = showSleepTimerSidePanel,
        showQueue = showQueueSidePanel,
        drawerWidth = fullscreenDrawerWidth,
        drawerOffset = fullscreenDrawerOffset,
        panelHeight = fullscreenSidePanelHeight,
        playerWidth = fullscreenPlayerWidth,
        dragModifier = fullscreenSidePanelDragModifier,
        close = ::closeFullscreenSidePanel,
    )
}

@UnstableApi
@Composable
internal fun BoxScope.FullscreenSidePanel(
    session: VideoPlayerStageSession,
    panelState: FullscreenSidePanelState,
    commentsUiState: PlayerCommentsUiState,
    videoAspectRatio: Float,
    rememberSubtitleLanguage: (String) -> Unit,
    onNavigateToChannel: (String) -> Unit,
) {
    val video = session.video
    val scope = session.scope
    val screenState = session.screenState
    val playerState = session.playerState
    val playerUiState = session.uiState
    val playerViewModel = session.viewModel
    val prefs = session.prefs
    val playerPreferences = prefs.preferences
    val closeFullscreenSidePanel = panelState.close

    androidx.compose.foundation.layout.Box(
        modifier =
            Modifier
                .align(Alignment.CenterEnd)
                .offset(x = panelState.drawerOffset)
                .width(panelState.drawerWidth)
                .fillMaxHeight()
                .then(panelState.dragModifier)
                .background(MaterialTheme.colorScheme.surface)
                .zIndex(8f),
    ) {
        if (panelState.showSettings) {
            PlayerSettingsSheetHost(
                screenState = screenState,
                playerState = playerState,
                uiState = playerUiState,
                viewModel = playerViewModel,
                playerPreferences = playerPreferences,
                scope = scope,
                rememberPlaybackSpeed = prefs.rememberPlaybackSpeed,
                ambientModeEnabled = prefs.ambientModeEnabled,
                groupedQualitySelectorEnabled = prefs.groupedQualitySelectorEnabled,
                rememberSubtitleLanguage = rememberSubtitleLanguage,
                asSidePanel = true,
                expandedHeight = panelState.panelHeight,
                onDismiss = closeFullscreenSidePanel,
                pipAspectRatio = videoAspectRatio,
            )
        } else if (panelState.showChapters) {
            PlayerChaptersSheetHost(
                screenState = screenState,
                chapters = playerUiState.chapters,
                thumbnailUrl = video.thumbnailUrl,
                asSidePanel = true,
                expandedHeight = panelState.panelHeight,
                onDismiss = closeFullscreenSidePanel,
            )
        } else if (panelState.showDescription) {
            PlayerDescriptionSheetHost(
                video = video,
                uiState = playerUiState,
                viewModel = playerViewModel,
                asSidePanel = true,
                expandedHeight = panelState.panelHeight,
                onDismiss = closeFullscreenSidePanel,
                hasTranscriptTrack = hasTranscriptTrack(playerState),
                onChaptersClick = { screenState.open(PlayerSheet.Chapters) },
                onTranscriptClick = { screenState.open(PlayerSheet.Transcript) },
                onChannelClick = onNavigateToChannel,
            )
        } else if (panelState.showTranscript) {
            PlayerTranscriptSheetHost(
                viewModel = playerViewModel,
                screenState = screenState,
                playerState = playerState,
                prefs = prefs,
                chapters = playerUiState.chapters,
                artworkUrl = video.thumbnailUrl,
                asSidePanel = true,
                expandedHeight = panelState.panelHeight,
                onDismiss = closeFullscreenSidePanel,
            )
        } else if (panelState.showSleepTimer) {
            PlayerSleepTimerSheetHost(
                asSidePanel = true,
                expandedHeight = panelState.panelHeight,
                onDismiss = closeFullscreenSidePanel,
            )
        } else if (panelState.showQueue) {
            PlayerQueueSheetHost(
                asSidePanel = true,
                expandedHeight = panelState.panelHeight,
                onDismiss = closeFullscreenSidePanel,
            )
        } else if (panelState.showLiveChat) {
            PlayerLiveChatColumn(
                messages = playerUiState.liveChatMessages,
                isLoading = playerUiState.isLiveChatLoading,
                onClose = closeFullscreenSidePanel,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (panelState.showComments) {
            PlayerCommentsPanelHost(
                videoId = video.id,
                screenState = screenState,
                viewModel = playerViewModel,
                commentsUiState = commentsUiState,
                artworkUrl = video.thumbnailUrl,
                onNavigateToChannel = onNavigateToChannel,
                onClose = closeFullscreenSidePanel,
            )
        }
    }
}
