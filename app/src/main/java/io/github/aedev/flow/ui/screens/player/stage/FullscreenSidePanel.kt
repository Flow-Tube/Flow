package io.github.aedev.flow.ui.screens.player.stage

import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.util.UnstableApi
import io.github.aedev.flow.data.local.FullscreenSidePanelWidth
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.ui.components.videoplayer.motion.PlayerGestureThresholds
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
import kotlin.math.roundToInt

/**
 * The drawer a landscape fullscreen player slides in from the trailing edge instead of raising a
 * bottom sheet, plus the width it leaves for the video beside it.
 *
 * The video is laid out at its settled width only. While the drawer moves, [playerSlide] scales and
 * shifts it in a graphics layer so its fitted picture sits where the room left by the drawer puts it;
 * a width change every frame would resize the video surface every frame.
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
    /** How far the drawer is still pushed out past the edge, in px; read in the layout phase. */
    val drawerOffsetPx: () -> Float,
    val panelHeight: Dp,
    /** The video's settled width beside the drawer; it changes once per open or close, never per frame. */
    val playerWidth: Dp,
    private val maxWidthPx: Float,
    private val maxHeightPx: Float,
    private val drawerWidthPx: Float,
    val dragModifier: Modifier,
    val close: () -> Unit,
) {
    fun playerSlide(videoAspectRatio: Float): Modifier =
        Modifier.graphicsLayer {
            if (!visible || drawerWidthPx <= 0f) return@graphicsLayer
            val settledWidth = playerWidth.toPx()
            val reserved = (drawerWidthPx - drawerOffsetPx()).coerceIn(0f, drawerWidthPx)
            val roomNow = (maxWidthPx - reserved).coerceAtLeast(settledWidth)
            val aspect = videoAspectRatio.coerceAtLeast(0.1f)
            val fittedNow = minOf(roomNow, maxHeightPx * aspect)
            val fittedSettled = minOf(settledWidth, maxHeightPx * aspect).coerceAtLeast(1f)
            val scale = fittedNow / fittedSettled
            transformOrigin = TransformOrigin(0.5f, 0.5f)
            scaleX = scale
            scaleY = scale
            translationX = (roomNow - settledWidth) / 2f
        }
}

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

    val openSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val closeSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
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
                animationSpec = openSpec,
            )
        }
    }

    fun closeFullscreenSidePanel() {
        scope.launch {
            fullscreenDrawerOffsetPx.animateTo(
                targetValue = fullscreenDrawerWidthPx,
                animationSpec = closeSpec,
            )
            screenState.dismissMediaSheets()
        }
    }
    val fullscreenSidePanelDragModifier =
        Modifier.pointerInput(fullscreenDrawerWidthPx, fullscreenSidePanelVisible) {
            if (!fullscreenSidePanelVisible || fullscreenDrawerWidthPx <= 0f) return@pointerInput
            val closeVelocity = PlayerGestureThresholds(density.density).sidePanelCloseVelocity
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
                            animationSpec = openSpec,
                        )
                    }
                },
                onDragEnd = {
                    val velocityX = velocityTracker.calculateVelocity().x
                    velocityTracker.resetTracking()
                    val shouldDismiss =
                        velocityX > closeVelocity ||
                            fullscreenDrawerOffsetPx.value > fullscreenDrawerWidthPx * 0.38f
                    if (shouldDismiss) {
                        closeFullscreenSidePanel()
                    } else {
                        scope.launch {
                            fullscreenDrawerOffsetPx.animateTo(
                                targetValue = 0f,
                                animationSpec = openSpec,
                            )
                        }
                    }
                },
            )
        }
    val fullscreenSidePanelHeight = maxHeight
    val fullscreenPlayerWidth =
        if (fullscreenSidePanelVisible) {
            (maxWidth - fullscreenDrawerWidth).coerceAtLeast(sidePanelMinPlayerWidth(maxWidth, widthPreset))
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
        drawerOffsetPx = { fullscreenDrawerOffsetPx.value },
        panelHeight = fullscreenSidePanelHeight,
        playerWidth = fullscreenPlayerWidth,
        maxWidthPx = with(density) { maxWidth.toPx() },
        maxHeightPx = with(density) { maxHeight.toPx() },
        drawerWidthPx = fullscreenDrawerWidthPx,
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
                .offset { IntOffset(panelState.drawerOffsetPx().roundToInt(), 0) }
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
                ambientGlowEnabled = prefs.ambientGlowEnabled,
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
                exportTitle = video.title,
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
                isReplay = !playerUiState.isLive,
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
