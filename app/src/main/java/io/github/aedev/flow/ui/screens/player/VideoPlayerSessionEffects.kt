package io.github.aedev.flow.ui.screens.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.ui.components.videoplayer.PlayerDraggableState
import io.github.aedev.flow.ui.components.videoplayer.PlayerSheetValue
import io.github.aedev.flow.ui.components.videoplayer.isImmersivePlayer
import io.github.aedev.flow.ui.screens.player.effects.*
import io.github.aedev.flow.ui.screens.player.stage.*
import io.github.aedev.flow.ui.screens.player.state.*

/** The fullscreen, orientation and watch-session effects of [VideoPlayerHost], in the order it composes them. */
@UnstableApi
@Composable
internal fun VideoPlayerSessionEffects(
    video: Video,
    activity: ComponentActivity,
    lifecycleOwner: LifecycleOwner,
    playerSheetState: PlayerDraggableState,
    screenState: PlayerScreenState,
    playerState: EnhancedPlayerState,
    playerUiState: VideoPlayerUiState,
    playerViewModel: VideoPlayerViewModel,
    prefs: VideoPlayerPreferencesState,
    isLandscape: Boolean,
    isLargeWindow: Boolean,
    fullscreenShown: Boolean,
    effectiveVideoAspectRatio: Float,
    localIsInPipMode: Boolean,
    isEnteringPip: Boolean,
    pipForcedFullscreen: MutableState<Boolean>,
    expandedSurfacesMounted: Boolean,
    rememberSubtitleLanguage: (String) -> Unit,
) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    PlayerSheetCollapseSyncEffects(
        playerSheetState = playerSheetState,
        screenState = screenState,
        isFullscreenShown = fullscreenShown,
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
        isSheetCollapsed = playerSheetState.currentValue == PlayerSheetValue.Collapsed,
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

    SponsorSkipEffect(context, showSkipNotice = prefs.sbSkipNoticeEnabled, onSkipped = playerViewModel::onSponsorSegmentSkipped)
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

    LiveCountsRefreshEffect(
        videoId = video.id,
        refreshable = playerUiState.isLive && playerUiState.watchHeader?.updatedMetadata != null,
        onScreen = screenState.isPlayerExpanded && !screenState.isFullscreen && !fullscreenShown && !localIsInPipMode,
        viewModel = playerViewModel,
    )

    PlayerEndAndPipEffects(
        playerState = playerState,
        playerSheetState = playerSheetState,
        screenState = screenState,
        isInPipMode = localIsInPipMode,
        isEnteringPip = isEnteringPip,
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
}
