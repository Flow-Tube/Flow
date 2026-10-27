package io.github.aedev.flow.ui.screens.player

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.dlna.DlnaCastManager
import io.github.aedev.flow.ui.components.shared.PlayerHaptics
import io.github.aedev.flow.ui.components.videoplayer.DraggablePlayerLayout
import io.github.aedev.flow.ui.components.videoplayer.PlayerDraggableState
import io.github.aedev.flow.ui.components.videoplayer.PlayerSheetValue
import io.github.aedev.flow.ui.components.videoplayer.isImmersivePlayer
import io.github.aedev.flow.ui.screens.player.effects.*
import io.github.aedev.flow.ui.screens.player.stage.*
import io.github.aedev.flow.ui.screens.player.state.*
import io.github.aedev.flow.ui.screens.player.state.supportingPaneReserve
import io.github.aedev.flow.ui.utils.LocalWindowIsLandscape
import io.github.aedev.flow.ui.utils.LocalWindowSizeClass
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import kotlinx.coroutines.launch

/**
 * VideoPlayerHost - The main video player overlay that sits above everything.
 *
 * This composable handles:
 * - Draggable player layout (expanded/collapsed states)
 * - All player effects (position tracking, controls, PiP, etc.)
 * - Dialogs and bottom sheets
 * - PiP mode rendering
 *
 * @param video The current video to play (null if no video)
 * @param isVisible Whether the player overlay should be visible
 * @param playerSheetState State of the draggable player (expanded/collapsed)
 * @param onClose Called when the player is closed
 * @param onNavigateToChannel Called when navigating to a channel
 * @param onNavigateToShorts Called when navigating to shorts
 */
@UnstableApi
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun VideoPlayerHost(
    video: Video?,
    isVisible: Boolean,
    playerSheetState: PlayerDraggableState,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    startInset: androidx.compose.ui.unit.Dp = 0.dp,
    miniPlayerScale: Float = 0.45f,
    miniPlayerShowSkipControls: Boolean = false,
    miniPlayerShowNextPrevControls: Boolean = false,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onNavigateToChannel: (String) -> Unit,
    onNavigateToShorts: (String) -> Unit,
) {
    if (video == null || !isVisible) return

    val context = LocalContext.current
    val activity = context as ComponentActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val playerViewModel: VideoPlayerViewModel = hiltViewModel(activity)
    val playerUiState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val playerState by EnhancedPlayerManager.getInstance().playerState.collectAsStateWithLifecycle()
    val sponsorSegments by EnhancedPlayerManager.getInstance().sponsorSegments.collectAsStateWithLifecycle()

    val screenState = rememberPlayerScreenState()
    val audioSystemInfo = rememberAudioSystemInfo(context)
    val pipPreferences = rememberPipPreferences(context)
    val canGoPrevious by playerViewModel.canGoPrevious.collectAsStateWithLifecycle()
    val commentsUiState = rememberPlayerCommentsUiState(playerViewModel)

    val prefs = rememberVideoPlayerPreferences(context)
    val rememberSubtitleLanguage: (String) -> Unit = { language ->
        scope.launch { prefs.preferences.setPreferredSubtitleLanguage(language) }
    }
    val isCommentsAvailable = prefs.commentsEnabled && playerUiState.hlsUrl.isNullOrEmpty()

    PlayerVolumeEffects(
        videoId = video.id,
        screenState = screenState,
        audioSystemInfo = audioSystemInfo,
        allowVolumeBoost = prefs.allowVolumeBoost,
        volumeSwipeGesturesEnabled = prefs.volumeSwipeGesturesEnabled,
    )

    var decodedVideoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }
    val videoAspectRatio =
        playerState.sourceVideoAspectRatio
            .takeIf { playerState.currentVideoId == video.id }
            ?: decodedVideoAspectRatio
    val localIsInPipMode by GlobalPlayerState.isInPipMode.collectAsStateWithLifecycle()
    val config = LocalConfiguration.current
    val isLandscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val windowSizeClass = LocalWindowSizeClass.current
    val isLandscapeWindow = LocalWindowIsLandscape.current
    val windowLayoutMode = playerWindowLayoutModeFor(windowSizeClass, isLandscapeWindow)
    val isLargeWindow = windowLayoutMode != PlayerLayoutMode.COMPACT
    val isTwoPaneWindow = windowLayoutMode == PlayerLayoutMode.WIDE
    val paneScaffoldDirective = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())
    val detailPaneWidth = if (isTwoPaneWindow) paneScaffoldDirective.supportingPaneReserve() else 0.dp
    val playerLayoutMode =
        playerLayoutModeFor(
            windowSizeClass = windowSizeClass,
            isLandscapeWindow = isLandscapeWindow,
            isFullscreen = screenState.isFullscreen,
            isInPipMode = localIsInPipMode,
        )
    val mediaSheetGeometry =
        rememberPlayerMediaSheetGeometry(
            screenState = screenState,
            adaptivePlayerSizeEnabled = prefs.adaptivePlayerSizeEnabled,
            videoAspectRatio = videoAspectRatio,
            sheetsHostedBesideVideo = playerLayoutMode == PlayerLayoutMode.WIDE,
        )
    val effectiveVideoAspectRatio = mediaSheetGeometry.effectiveVideoAspectRatio
    var expandedPlayerBottom by remember { mutableStateOf(0.dp) }
    val pipForcedFullscreen = remember { mutableStateOf(false) }

    PlayerZoomEffects(
        videoId = video.id,
        screenState = screenState,
        onResetVideoAspectRatio = { decodedVideoAspectRatio = 16f / 9f },
    )

    PlayerSubtitleEffects(
        screenState = screenState,
        savedSubtitleStyle = prefs.savedSubtitleStyle,
    )

    PlayerBrightnessRestoreEffect(
        screenState = screenState,
        rememberBrightnessEnabled = prefs.rememberBrightnessEnabled,
        rememberedBrightnessLevel = prefs.rememberedBrightnessLevel,
    )

    TouchLockDisableEffect(
        screenState = screenState,
        lockModeEnabled = prefs.lockModeEnabled,
    )

    val progressProvider = remember(screenState) { { screenState.playbackFraction() } }
    PlayerSheetCollapseSyncEffects(
        playerSheetState = playerSheetState,
        screenState = screenState,
    )

    val windowInsetDensity = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val sponsorSkipEndPadding =
        with(windowInsetDensity) {
            maxOf(
                WindowInsets.displayCutout.getRight(this, layoutDirection),
                WindowInsets.systemBars.getRight(this, layoutDirection),
            ).toDp() + 16.dp
        }
    val sponsorSkipBottomInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

    val updateBrightnessLevel =
        brightnessLevelUpdater(
            screenState = screenState,
            playerPreferences = prefs.preferences,
            rememberBrightnessEnabled = prefs.rememberBrightnessEnabled,
            scope = scope,
        )

    PhoneLandscapeFullscreenEffect(
        isLandscape = isLandscape,
        isLargeWindow = isLargeWindow,
        isInPipMode = localIsInPipMode,
        playerSheetState = playerSheetState,
        screenState = screenState,
    )

    LiveChatVisibilityEffect(
        playerSheetState = playerSheetState,
        screenState = screenState,
        layoutMode = playerLayoutMode,
        viewModel = playerViewModel,
    )

    AutoplayCommentsHoldEffect(screenState = screenState, viewModel = playerViewModel)

    // ===== EFFECTS =====
    PlayerSheetSessionEffects(
        playerSheetState = playerSheetState,
        uiState = playerUiState,
        queueTitle = { playerState.queueTitle },
        viewModel = playerViewModel,
        onMinimize = onMinimize,
    )

    val fullscreenOpen by playerViewModel.fullscreenOpen.collectAsStateWithLifecycle()
    OpenInFullscreenEffect(
        videoId = video.id,
        requestedVideoId = fullscreenOpen,
        playerSheetState = playerSheetState,
        screenState = screenState,
        isInPipMode = localIsInPipMode,
        isVideoShapeKnown = {
            playerState.currentVideoId == video.id && (playerState.sourceVideoAspectRatio != null || playerState.isPrepared)
        },
        onConsumed = playerViewModel::consumeFullscreenOpen,
    )

    PlayerBackEffects(
        playerSheetState = playerSheetState,
        screenState = screenState,
        isInPipMode = localIsInPipMode,
    )

    val isMinimized by remember(playerSheetState) {
        derivedStateOf { playerSheetState.fraction > 0.5f }
    }

    val expandedSurfacesMounted by rememberExpandedSurfacesMounted(
        playerSheetState = playerSheetState,
        isMinimized = isMinimized,
    )
    val expandedSurfacesPlaced = remember(playerSheetState) { { playerSheetState.fraction <= 0.5f } }

    SeekDragResetEffect(
        screenState = screenState,
        isMinimized = isMinimized,
        isInPipMode = localIsInPipMode,
    )

    PositionTrackingEffect(
        isPlaying = playerState.playWhenReady,
        screenState = screenState,
        showsPreciseProgress =
            !isMinimized && !localIsInPipMode &&
                (screenState.showControls || !screenState.isFullscreen),
    )

    PlaybackRefocusEffect(
        screenState = screenState,
        lifecycleOwner = lifecycleOwner,
    )

    AutoHideControlsEffect(
        showControls = screenState.showControls,
        isPlaying = playerState.playWhenReady,
        hasEnded = playerState.hasEnded,
        lastInteractionTimestamp = screenState.lastInteractionTimestamp,
        isTouchLocked = screenState.isTouchLocked,
        isScrubbing = screenState.isScrubbing || screenState.isSeekDragging,
        onHideControls = { screenState.showControls = false },
    )

    GestureOverlayAutoHideEffect(screenState)

    SetupPipEffects(
        context = context,
        activity = activity,
        isPlaying = playerState.playWhenReady,
        isBackgroundPlaybackMode = playerUiState.isBackgroundPlaybackMode,
        videoAspectRatio = videoAspectRatio,
        pipPreferences = pipPreferences,
    )

    val fullscreenShown =
        rememberShownFullscreen(
            isFullscreen = screenState.isFullscreen,
            isFullscreenPortrait = screenState.isFullscreenPortrait,
            videoAspectRatio = effectiveVideoAspectRatio,
            windowIsLandscape = isLandscape,
            rotationHonoured = !isLargeWindow && !context.orientationRequestIgnored(config),
        )
    FullscreenEffect(
        isFullscreen = screenState.isFullscreen,
        activity = activity,
        videoAspectRatio = effectiveVideoAspectRatio,
        lifecycleOwner = lifecycleOwner,
        fullscreenBrightnessLevel = {
            if (prefs.rememberBrightnessEnabled) screenState.brightnessLevel else null
        },
        suppressFullscreenRequest = pipForcedFullscreen.value,
        isPortrait = screenState.isFullscreenPortrait,
        isLargeWindow = isLargeWindow,
        hideSystemBars =
            isImmersivePlayer(
                isExpanded = playerSheetState.currentValue == PlayerSheetValue.Expanded,
                isFullscreen = fullscreenShown,
                isLandscape = isLandscape,
                isLargeWindow = isLargeWindow,
            ),
    )

    OrientationResetEffect(activity)

    WatchProgressSaveEffect(
        video = video,
        isPlaying = playerState.playWhenReady,
        currentPosition = { screenState.currentPosition },
        duration = { screenState.duration },
        uiState = playerUiState,
        viewModel = playerViewModel,
    )

    if (!playerUiState.isRestoredSession) {
        PlayerFreshSessionEffects(
            videoId = video.id,
            context = context,
            screenState = screenState,
            uiState = playerUiState,
            viewModel = playerViewModel,
        )
    }

    val globalCurrentVideo by GlobalPlayerState.currentVideo.collectAsStateWithLifecycle()
    GlobalVideoSyncEffect(
        currentVideoId = globalCurrentVideo?.id,
        currentVideo = { globalCurrentVideo },
        uiState = playerUiState,
        commentsEnabled = prefs.commentsEnabled,
        expandedBodyVisible = expandedSurfacesMounted,
        viewModel = playerViewModel,
    )

    SubscriptionAndLikeEffect(
        videoId = video.id,
        uiState = playerUiState,
        viewModel = playerViewModel,
    )

    // Short video prompt
    ShortVideoPromptEffect(
        videoDuration = video.duration,
        screenState = screenState,
        isInQueue = playerState.queueSize > 1,
        disableShortsPlayer = prefs.disableShortsPlayer,
        showShortsPlayerPrompt = prefs.showShortsPlayerPrompt,
    )

    SponsorSkipEffect(context, onSkipped = playerViewModel::onSponsorSegmentSkipped)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playerViewModel.checkpointWatchSession() }

    SubtitleLoadErrorEffect(
        context = context,
        subtitles = playerState.availableSubtitles,
        rememberLanguage = rememberSubtitleLanguage,
    )

    OrientationListenerEffect(
        context = context,
        isExpanded = playerSheetState.currentValue == PlayerSheetValue.Expanded,
        isFullscreen = screenState.isFullscreen,
        videoAspectRatio = effectiveVideoAspectRatio,
        isPortraitFullscreen = screenState.isFullscreenPortrait,
        ignoreRotationLock =
            prefs.tiltFullscreenRotationLocked && !isLargeWindow && !context.orientationRequestIgnored(config),
        onEnterFullscreen = { screenState.isFullscreen = true },
        onExitFullscreen = {
            screenState.isFullscreen = false
            screenState.isFullscreenPortrait = false
        },
    )

    KeepScreenOnEffect(
        isPlaying = playerState.playWhenReady && !playerState.hasEnded,
        activity = activity,
        lifecycleOwner = lifecycleOwner,
    )

    UpcomingVideoRefreshEffect(
        videoId = video.id,
        isUpcoming = playerUiState.isUpcoming,
        upcomingReleaseTimeMs = playerUiState.upcomingReleaseTimeMs,
        viewModel = playerViewModel,
    )

    PlayerEndAndPipEffects(
        playerState = playerState,
        playerSheetState = playerSheetState,
        screenState = screenState,
        isInPipMode = localIsInPipMode,
        isLandscape = isLandscape,
        pipForcedFullscreen = pipForcedFullscreen,
    )

    val uiStateAtDispose by rememberUpdatedState(playerUiState)
    DisposableEffect(video.id) {
        onDispose {
            saveWatchProgress(
                viewModel = playerViewModel,
                video = video,
                uiState = uiStateAtDispose,
                position = screenState.currentPosition,
                duration = screenState.duration,
            )
        }
    }

    // ===== UI =====
    val floatingSponsorSkipBottomPadding =
        if (screenState.isFullscreen) {
            maxOf(sponsorSkipBottomInset + 128.dp, 136.dp)
        } else {
            56.dp
        }

    val stageSession =
        VideoPlayerStageSession(
            video = video,
            context = context,
            activity = activity,
            scope = scope,
            screenState = screenState,
            playerState = playerState,
            uiState = playerUiState,
            viewModel = playerViewModel,
            sheetState = playerSheetState,
            prefs = prefs,
            audioSystemInfo = audioSystemInfo,
            pipPreferences = pipPreferences,
            isFullscreenShown = fullscreenShown,
        )

    PlayerHaptics(enabled = prefs.hapticsEnabled) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val mediaSheetHeights =
                rememberMediaSheetHeights(
                    geometry = mediaSheetGeometry,
                    isFullscreen = screenState.isFullscreen,
                    fullScreenHeightPx = constraints.maxHeight.toFloat(),
                    playerWidthPx = constraints.maxWidth.toFloat(),
                    expandedPlayerBottom = expandedPlayerBottom,
                    fallbackScreenHeight = config.screenHeightDp.dp,
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                )
            val canUseFullscreenSidePanel = screenState.isFullscreen && maxWidth > maxHeight
            val sidePanelState =
                rememberFullscreenSidePanelState(
                    screenState = screenState,
                    playerUiState = playerUiState,
                    commentsEnabled = prefs.commentsEnabled,
                    canUseFullscreenSidePanel = canUseFullscreenSidePanel,
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                    scope = scope,
                )

            DraggablePlayerLayout(
                state = playerSheetState,
                progress = progressProvider,
                isFullscreen = fullscreenShown,
                thumbnailUrl =
                    video.thumbnailUrl.takeIf { it.isNotEmpty() }
                        ?: ThumbnailUrlResolver.buildHighQualityYoutubeThumbnail(video.id),
                videoAspectRatio = effectiveVideoAspectRatio,
                expandedPlayerHeightFractionOverride = mediaSheetGeometry.playerHeightFractionOverride,
                bottomPadding = bottomPadding,
                miniPlayerScale = miniPlayerScale,
                isLargeWindow = isLargeWindow,
                isTwoPaneWindow = isTwoPaneWindow,
                detailPaneWidth = detailPaneWidth,
                startInset = startInset,
                tapToExpand = true,
                onDismiss = onClose,
                onCollapseGesture = {
                    screenState.isFullscreen = false
                    screenState.isFullscreenPortrait = false
                    screenState.dismissMediaSheets()
                    GlobalPlayerState.showMiniPlayer()
                },
                onFullscreenGesture = {
                    screenState.dismissMediaSheets()
                    screenState.isFullscreenPortrait = false
                    screenState.isFullscreen = true
                },
                onEnterPortraitFullscreen = {
                    screenState.dismissMediaSheets()
                    screenState.isFullscreenPortrait = true
                    screenState.isFullscreen = true
                },
                onExpandedPlayerBottomChanged = { bottom ->
                    expandedPlayerBottom = bottom
                },
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(sidePanelState.playerWidth)
                        .fillMaxHeight(),
                videoContent = { modifier ->
                    VideoStage(
                        modifier = modifier,
                        session = stageSession,
                        isMinimized = isMinimized,
                        localIsInPipMode = localIsInPipMode,
                        expandedSurfacesMounted = expandedSurfacesMounted,
                        expandedSurfacesPlaced = expandedSurfacesPlaced,
                        videoAspectRatio = videoAspectRatio,
                        canGoPrevious = canGoPrevious,
                        isCommentsAvailable = isCommentsAvailable,
                        canUseFullscreenSidePanel = canUseFullscreenSidePanel,
                        updateBrightnessLevel = updateBrightnessLevel,
                        rememberSubtitleLanguage = rememberSubtitleLanguage,
                        onDecodedVideoAspectRatioChanged = { decodedVideoAspectRatio = it },
                        onSbSubmitClick = {
                            screenState.showControls = false
                            screenState.open(PlayerSheet.SbSubmit)
                        },
                        onCastClick = {
                            DlnaCastManager.startDiscovery(context)
                            screenState.open(PlayerSheet.Dlna)
                        },
                    )
                },
                bodyContent = { alpha, videoHeightPx ->
                    PlayerBodySlot(
                        session = stageSession,
                        alpha = alpha,
                        videoHeightPx = videoHeightPx,
                        isLandscape = isLandscape,
                        localIsInPipMode = localIsInPipMode,
                        onClose = onClose,
                        onNavigateToChannel = onNavigateToChannel,
                        onNavigateToShorts = onNavigateToShorts,
                    )
                },
                miniControls = { _ ->
                    MiniPlayerSlot(
                        session = stageSession,
                        miniPlayerShowSkipControls = miniPlayerShowSkipControls,
                        miniPlayerShowNextPrevControls = miniPlayerShowNextPrevControls,
                        onClose = onClose,
                    )
                },
            )

            if (!playerUiState.isUpcoming && expandedSurfacesMounted && !localIsInPipMode && sponsorSegments.isNotEmpty()) {
                SponsorSkipLayer(
                    session = stageSession,
                    sponsorSegments = sponsorSegments,
                    expandedPlayerBottom = expandedPlayerBottom,
                    playerWidth = sidePanelState.playerWidth,
                    expandedSurfacesPlaced = expandedSurfacesPlaced,
                    endPadding = sponsorSkipEndPadding,
                    bottomPadding = floatingSponsorSkipBottomPadding,
                )
            }

            BackHandler(enabled = sidePanelState.visible, onBack = sidePanelState.close)

            if (sidePanelState.visible) {
                FullscreenSidePanel(
                    session = stageSession,
                    panelState = sidePanelState,
                    commentsUiState = commentsUiState,
                    videoAspectRatio = videoAspectRatio,
                    rememberSubtitleLanguage = rememberSubtitleLanguage,
                    onNavigateToChannel = onNavigateToChannel,
                )
            }

            VideoPlayerDialogs(
                session = stageSession,
                completeVideo = video,
                mediaSheetHeights = mediaSheetHeights,
                onMediaSheetProgressChange = mediaSheetGeometry.onProgressChange,
                canUseFullscreenSidePanel = canUseFullscreenSidePanel,
                playerLayoutMode = playerLayoutMode,
                commentsUiState = commentsUiState,
                onNavigateToChannel = onNavigateToChannel,
                onNavigateToShorts = onNavigateToShorts,
                onClose = onClose,
            )
        }
    }
}
