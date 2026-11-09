package io.github.aedev.flow.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffold
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.rememberSupportingPaneScaffoldNavigator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.ui.components.layout.flowWindowAdaptiveInfo
import io.github.aedev.flow.ui.components.rememberFeedGridLayout
import io.github.aedev.flow.ui.components.videoplayer.sheet.PlaylistQueueDock
import io.github.aedev.flow.ui.components.videoplayer.sheet.PlaylistQueueDockDefaults
import io.github.aedev.flow.ui.components.videoplayer.sheet.PlaylistQueuePaneCard
import io.github.aedev.flow.ui.screens.player.content.PlayerDetailSideColumn
import io.github.aedev.flow.ui.screens.player.content.VideoInfoContent
import io.github.aedev.flow.ui.screens.player.content.relatedListUsesGrid
import io.github.aedev.flow.ui.screens.player.content.relatedVideosContent
import io.github.aedev.flow.ui.screens.player.content.relatedVideosGridContent
import io.github.aedev.flow.ui.screens.player.content.relatedVideosSkeleton
import io.github.aedev.flow.ui.screens.player.content.rememberRelatedSkeleton
import io.github.aedev.flow.ui.screens.player.state.PlayerLayoutMode
import io.github.aedev.flow.ui.screens.player.state.PlayerScreenState
import io.github.aedev.flow.ui.screens.player.state.PlayerSheet
import io.github.aedev.flow.ui.screens.player.state.QueueDockPlacement
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerPreferencesState
import io.github.aedev.flow.ui.screens.player.state.hasVisibleQueue
import io.github.aedev.flow.ui.screens.player.state.nextQueueVideo
import io.github.aedev.flow.ui.screens.player.state.playerLayoutModeFor
import io.github.aedev.flow.ui.screens.player.state.queueDockPlacement
import io.github.aedev.flow.ui.screens.player.state.rememberPlayerCommentsUiState
import io.github.aedev.flow.ui.utils.LocalWindowIsLandscape
import io.github.aedev.flow.ui.utils.LocalWindowSizeClass
import kotlin.math.roundToInt

/** A readable cap for the dock, which would otherwise stretch across a tablet's whole width. */
private val QueueDockMaxWidth = 600.dp
private val QueueDockMargin = 16.dp
private val QueueDockGap = 8.dp
private val ListEndPadding = 80.dp

/**
 * EnhancedVideoPlayerScreen - Simplified version for DraggablePlayerLayout
 *
 * This composable only renders the VIDEO DETAILS (description, comments, related videos).
 * The video player surface and all effects are handled by FlowApp.kt
 */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun EnhancedVideoPlayerScreen(
    viewModel: VideoPlayerViewModel,
    video: Video,
    alpha: () -> Float,
    videoPlayerHeightPx: () -> Float = { 0f },
    screenState: PlayerScreenState, // Shared screenState from FlowApp
    isFullscreenShown: Boolean,
    prefs: VideoPlayerPreferencesState,
    onVideoClick: (Video) -> Unit,
    onChannelClick: (String) -> Unit,
) {
    val context = LocalContext.current
    val windowSizeClass = LocalWindowSizeClass.current
    val isLandscapeWindow = LocalWindowIsLandscape.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val commentsUiState = rememberPlayerCommentsUiState(viewModel)

    val isLocalMedia = LocalMediaIds.isLocal(video.id)
    val showRelatedVideos = prefs.showRelatedVideos && !isLocalMedia
    val commentsEnabled = prefs.commentsEnabled && !isLocalMedia
    val showCommentsPreview = prefs.commentsPreviewEnabled
    val relatedCardStyle = prefs.relatedCardStyle
    val isInPipMode by GlobalPlayerState.isInPipMode.collectAsStateWithLifecycle()
    val playerState by EnhancedPlayerManager.getInstance().playerState.collectAsStateWithLifecycle()
    val showsRelatedSkeleton = rememberRelatedSkeleton(video, uiState, screenState)
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha() }
                .background(MaterialTheme.colorScheme.background),
    ) {
        val layoutMode =
            playerLayoutModeFor(
                windowSizeClass = windowSizeClass,
                isLandscapeWindow = isLandscapeWindow,
                isFullscreen = isFullscreenShown,
                isInPipMode = isInPipMode,
            )
        val isWideLayout = layoutMode == PlayerLayoutMode.WIDE
        val isMediumLayout = layoutMode == PlayerLayoutMode.MEDIUM

        val queueVideos by EnhancedPlayerManager.getInstance().queueVideos.collectAsStateWithLifecycle(initialValue = emptyList())
        val currentQueueIndex by EnhancedPlayerManager.getInstance().currentQueueIndexState.collectAsStateWithLifecycle(
            initialValue = -1,
        )
        val dockPlacement = queueDockPlacement(hasVisibleQueue(playerState.queueTitle, queueVideos.size), layoutMode)
        val showsQueueDock = dockPlacement == QueueDockPlacement.FLOATING
        val nextVideo = nextQueueVideo(queueVideos, currentQueueIndex, playerState.isQueueLooping)
        val dockReserve =
            if (showsQueueDock) {
                PlaylistQueueDockDefaults.Height + QueueDockMargin +
                    WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
            } else {
                0.dp
            }

        if (isWideLayout) {
            // The host sizes the video from the same directive (PlayerDetailPanes.supportingPaneReserve),
            // so the video row above the main pane and the scaffold's main pane keep one width.
            val navigator =
                rememberSupportingPaneScaffoldNavigator(
                    scaffoldDirective = calculatePaneScaffoldDirective(flowWindowAdaptiveInfo()),
                )
            SupportingPaneScaffold(
                directive = navigator.scaffoldDirective,
                scaffoldState = navigator.scaffoldState,
                mainPane = {
                    AnimatedPane {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Spacer(
                                Modifier
                                    .fillMaxWidth()
                                    .layout { measurable, constraints ->
                                        val height = videoPlayerHeightPx().roundToInt().coerceAtLeast(0)
                                        val placeable =
                                            measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                                        layout(placeable.width, height) { placeable.place(0, 0) }
                                    },
                            )

                            VideoInfoContent(
                                video = video,
                                uiState = uiState,
                                viewModel = viewModel,
                                screenState = screenState,
                                commentsUiState = commentsUiState,
                                commentsEnabled = commentsEnabled,
                                showCommentsPreview = showCommentsPreview,
                                deArrowEnabled = prefs.deArrowEnabled,
                                context = context,
                                scope = scope,
                                snackbarHostState = snackbarHostState,
                                onChannelClick = onChannelClick,
                            )
                        }
                    }
                },
                supportingPane = {
                    AnimatedPane {
                        PlayerDetailSideColumn(
                            video = video,
                            uiState = uiState,
                            playerState = playerState,
                            viewModel = viewModel,
                            screenState = screenState,
                            prefs = prefs,
                            commentsEnabled = commentsEnabled,
                            showRelatedVideos = showRelatedVideos,
                            relatedCardStyle = relatedCardStyle,
                            onVideoClick = onVideoClick,
                            onChannelClick = onChannelClick,
                            modifier = Modifier.fillMaxSize(),
                            queueCard =
                                if (dockPlacement == QueueDockPlacement.IN_PANE) {
                                    {
                                        PlaylistQueuePaneCard(
                                            nextVideo = nextVideo?.value,
                                            nextPosition = (nextVideo?.index ?: 0) + 1,
                                            playlistName = playerState.queueTitle.orEmpty(),
                                            queueSize = queueVideos.size,
                                            onClick = { screenState.open(PlayerSheet.Queue) },
                                        )
                                    }
                                } else {
                                    null
                                },
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val relatedLayout = rememberFeedGridLayout(maxWidth, prefs.feedColumns)
                // Kept through fullscreen: the layout hides the page there, and keeping the list
                // means leaving fullscreen, or waiting for the turn into it, never shows it empty.
                if (!isInPipMode) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = maxOf(ListEndPadding, dockReserve + QueueDockGap)),
                    ) {
                        item {
                            VideoInfoContent(
                                video = video,
                                uiState = uiState,
                                viewModel = viewModel,
                                screenState = screenState,
                                commentsUiState = commentsUiState,
                                commentsEnabled = commentsEnabled,
                                showCommentsPreview = showCommentsPreview,
                                deArrowEnabled = prefs.deArrowEnabled,
                                context = context,
                                scope = scope,
                                snackbarHostState = snackbarHostState,
                                onChannelClick = onChannelClick,
                            )
                        }
                        if (showRelatedVideos) {
                            if (showsRelatedSkeleton) {
                                relatedVideosSkeleton(video.thumbnailUrl, relatedCardStyle)
                            } else if (relatedListUsesGrid(isMediumLayout, relatedCardStyle, relatedLayout)) {
                                relatedVideosGridContent(
                                    relatedVideos = uiState.relatedVideos,
                                    layout = relatedLayout,
                                    onVideoClick = onVideoClick,
                                    cardStyle = relatedCardStyle,
                                )
                            } else {
                                relatedVideosContent(
                                    relatedVideos = uiState.relatedVideos,
                                    onVideoClick = onVideoClick,
                                    cardStyle = relatedCardStyle,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showsQueueDock) {
            PlaylistQueueDock(
                nextVideo = nextVideo?.value,
                nextPosition = (nextVideo?.index ?: 0) + 1,
                playlistName = playerState.queueTitle.orEmpty(),
                queueSize = queueVideos.size,
                onClick = { screenState.open(PlayerSheet.Queue) },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = QueueDockMargin)
                        .widthIn(max = QueueDockMaxWidth),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = dockReserve),
        )
    }
}
