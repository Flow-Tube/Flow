package io.github.aedev.flow.ui.screens.player.stage

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.ui.components.shared.MediaBufferingIndicator
import io.github.aedev.flow.ui.components.shared.VideoThumbnailImage
import io.github.aedev.flow.ui.components.videoplayer.MINI_PLAYER_CORNER_RADIUS_DP
import io.github.aedev.flow.ui.components.videoplayer.VideoPlayerSurface
import io.github.aedev.flow.ui.components.videoplayer.gesture.chapterSkipTarget
import io.github.aedev.flow.ui.components.videoplayer.gesture.videoPlayerControls
import io.github.aedev.flow.ui.components.videoplayer.gesture.videoPlayerZoom
import io.github.aedev.flow.ui.components.videoplayer.overlay.ChapterSkipFeedback
import io.github.aedev.flow.ui.components.videoplayer.placedWhen
import io.github.aedev.flow.ui.components.videoplayer.subtitle.Media3SubtitleOverlay
import io.github.aedev.flow.ui.screens.player.saveChosenPlaybackSpeed
import io.github.aedev.flow.ui.screens.player.state.PlayerSheet
import io.github.aedev.flow.ui.screens.player.state.effectiveResizeMode

/**
 * The player's video slot: the surface itself, the gesture and zoom transforms wrapped around it,
 * and the overlays and controls that mount on top once the sheet is at rest near expanded.
 */
@UnstableApi
@Composable
internal fun VideoStage(
    modifier: Modifier,
    session: VideoPlayerStageSession,
    isMinimized: Boolean,
    localIsInPipMode: Boolean,
    expandedSurfacesMounted: Boolean,
    expandedSurfacesPlaced: () -> Boolean,
    videoAspectRatio: Float,
    canGoPrevious: Boolean,
    isCommentsAvailable: Boolean,
    canUseFullscreenSidePanel: Boolean,
    updateBrightnessLevel: (Float) -> Unit,
    rememberSubtitleLanguage: (String) -> Unit,
    onDecodedVideoAspectRatioChanged: (Float) -> Unit,
    onSbSubmitClick: () -> Unit,
    onCastClick: () -> Unit,
) {
    val video = session.video
    val screenState = session.screenState
    val playerState = session.playerState
    val playerUiState = session.uiState
    val playerSheetState = session.sheetState
    val prefs = session.prefs
    val scope = session.scope
    val activity = session.activity
    val audioSystemInfo = session.audioSystemInfo
    val isLandscapeWindow = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val playerPreferences = remember(session.context) { PlayerPreferences(session.context) }
    val spherical = playerState.showsSpherical
    val resizeMode =
        effectiveResizeMode(
            isFullscreen = session.isFullscreenShown && !localIsInPipMode,
            spherical = spherical,
            override = screenState.resizeModeOverride,
        )
    val gesturesActive = !isMinimized && !localIsInPipMode && !screenState.isTouchLocked
    val haptics = LocalHapticFeedback.current
    val chapters = playerUiState.chapters
    val chapterStartsMs = remember(chapters) { chapters.map { it.startTimeSeconds * 1000L } }
    val canSkipChapters = prefs.chapterSkipGestureEnabled && chapterStartsMs.isNotEmpty() && !playerState.isLive
    val skipChapter by rememberUpdatedState { forward: Boolean ->
        val manager = EnhancedPlayerManager.getInstance()
        val position = manager.getPlayer()?.currentPosition ?: screenState.currentPosition
        chapterSkipTarget(chapterStartsMs, position, forward)?.let { index ->
            manager.seekTo(chapterStartsMs[index])
            screenState.showSeekBackAnimation = false
            screenState.showSeekForwardAnimation = false
            val title = chapters[index].title.orEmpty().ifBlank { session.context.getString(R.string.player_chapter_number, index + 1) }
            screenState.chapterSkip = ChapterSkipFeedback(title = title, forward = forward)
            screenState.showChapterSkip = true
            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        }
    }

    // ALWAYS use the same video surface
    val gestureModifier =
        if (gesturesActive) {
            modifier
                .videoPlayerControls(
                    isSpeedBoostActive = screenState.isSpeedBoostActive,
                    onSpeedBoostChange = { screenState.isSpeedBoostActive = it },
                    showControls = screenState.showControls,
                    onShowControlsChange = { screenState.showControls = it },
                    onShowSeekBackChange = { shown ->
                        screenState.showSeekBackAnimation = shown
                        if (shown) screenState.showChapterSkip = false
                    },
                    onShowSeekForwardChange = { shown ->
                        screenState.showSeekForwardAnimation = shown
                        if (shown) screenState.showChapterSkip = false
                    },
                    onSeekAccumulate = { screenState.seekAccumulation = kotlin.math.abs(it) },
                    currentPosition = { screenState.currentPosition },
                    duration = screenState.duration,
                    scope = scope,
                    isFullscreen = screenState.isFullscreen,
                    onBrightnessChange = updateBrightnessLevel,
                    onShowBrightnessChange = { screenState.showBrightnessOverlay = it },
                    onVolumeChange = { level ->
                        screenState.volumeLevel = level
                        EnhancedPlayerManager
                            .getInstance()
                            .setVolumeBoost(if (level > 1f) level else 1f)
                    },
                    onShowVolumeChange = { screenState.showVolumeOverlay = it },
                    onSeekDragChange = { dragging ->
                        screenState.isSeekDragging = dragging
                        screenState.onInteraction()
                    },
                    onSeekDragUpdate = { targetMs, deltaMs ->
                        screenState.seekDragTargetMs = targetMs
                        screenState.seekDragDeltaMs = deltaMs
                    },
                    brightnessLevel = { screenState.brightnessLevel },
                    volumeLevel = { screenState.volumeLevel },
                    maxVolume = audioSystemInfo.maxVolume,
                    audioManager = audioSystemInfo.audioManager,
                    activity = activity,
                    brightnessSwipeGesturesEnabled = prefs.brightnessSwipeGesturesEnabled,
                    volumeSwipeGesturesEnabled = prefs.volumeSwipeGesturesEnabled,
                    seekSwipeGesturesEnabled = prefs.seekSwipeGesturesEnabled,
                    allowVolumeBoost = prefs.allowVolumeBoost,
                    speedSwipeSide = prefs.speedSwipeSide,
                    speedSwipeStep = prefs.speedSwipeStep,
                    onSpeedSwipe = { speed ->
                        screenState.speedSwipeLevel = speed
                        screenState.showSpeedSwipeOverlay = true
                    },
                    onSpeedSwipeEnd = { speed ->
                        screenState.normalSpeed = speed
                        scope.saveChosenPlaybackSpeed(
                            playerPreferences = playerPreferences,
                            speed = speed,
                            rememberPlaybackSpeed = prefs.rememberPlaybackSpeed,
                            channelId = playerUiState.cachedVideo?.channelId,
                        )
                    },
                    doubleTapSeekMs = prefs.doubleTapSeekSeconds * 1000L,
                    seekZoneFraction = prefs.doubleTapSeekZone.sideFraction,
                    seekZoneReachesEdges = prefs.doubleTapSeekZone.reachesEdges,
                    longPressPlaybackSpeed = prefs.longPressPlaybackSpeed,
                    onExitFullscreen = { releaseOffsetPx, releaseVelocityPx ->
                        if (!isLandscapeWindow) playerSheetState.exitPortraitFullscreenFrom(releaseOffsetPx, releaseVelocityPx)
                        screenState.isFullscreen = false
                        screenState.isFullscreenPortrait = false
                    },
                    onExitFullscreenDrag = { offsetPx -> screenState.exitDragOffsetY = offsetPx },
                    onMoreVideos =
                        if (canUseFullscreenSidePanel && prefs.moreVideosSwipeEnabled) {
                            {
                                screenState.showControls = false
                                screenState.open(PlayerSheet.MoreVideos)
                            }
                        } else {
                            null
                        },
                    onMoreVideosDrag = { offsetPx -> screenState.moreVideosDragY = offsetPx },
                    isSeekForwardActive = screenState.showSeekForwardAnimation,
                    isSeekBackActive = screenState.showSeekBackAnimation,
                    onTapWindowChange = { screenState.isTapWindowOpen = it },
                    // A drag on a 360 video looks around instead.
                    dragGesturesEnabled = !spherical,
                ).videoPlayerZoom(
                    scope = scope,
                    scale = { screenState.zoomScale },
                    offsetX = { screenState.zoomOffsetX },
                    offsetY = { screenState.zoomOffsetY },
                    // A 360 video is moved by looking around, so a pinch only zooms it.
                    panEnabled = !spherical,
                    onTwoFingerDoubleTap = if (canSkipChapters) { forward -> skipChapter(forward) } else null,
                    onTransform = { newScale, newOffsetX, newOffsetY ->
                        screenState.zoomScale = newScale
                        screenState.zoomOffsetX = newOffsetX
                        screenState.zoomOffsetY = newOffsetY
                        screenState.showZoomIndicator = true
                        screenState.zoomIndicatorSequence += 1
                    },
                ).graphicsLayer {
                    if (screenState.isFullscreen) translationY = screenState.exitDragOffsetY
                }
        } else {
            modifier
        }

    // Removed rather than just unplaced while the player is small, so the panel's reads stop with it.
    val expandedSurfacesPlacedNow by remember { derivedStateOf(expandedSurfacesPlaced) }
    val configuration = LocalConfiguration.current
    val firstFrameVideoId by EnhancedPlayerManager.getInstance().firstFrameVideoId.collectAsStateWithLifecycle()

    Box(modifier = gestureModifier) {
        // Zoomable layer: video + subtitles scale together with the pinch transform
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .graphicsLayer {
                        if (!isMinimized) {
                            scaleX = screenState.zoomScale
                            scaleY = screenState.zoomScale
                            translationX = screenState.zoomOffsetX
                            translationY = screenState.zoomOffsetY
                        }
                    },
        ) {
            VideoPlayerSurface(
                video = video,
                resizeMode = resizeMode,
                modifier = Modifier.fillMaxSize(),
                onVideoAspectRatioChanged = onDecodedVideoAspectRatioChanged,
                cornerRadiusDp =
                    if (isMinimized && !localIsInPipMode) {
                        MINI_PLAYER_CORNER_RADIUS_DP / playerSheetState.miniVisualScale
                    } else {
                        0f
                    },
                ambientMode = prefs.ambientModeEnabled && !isMinimized && !localIsInPipMode,
                ambientFrames = session.ambient.frames,
                ambientGlow = session.ambient.glow,
                spherical = spherical,
                sphericalMotion = prefs.sphericalMotionEnabled,
                sphericalTouch = gesturesActive,
                sphericalStill = !playerState.playWhenReady || playerState.hasEnded,
                sphericalZoom = { screenState.zoomScale },
                sphericalDragSpeed = prefs.sphericalDragSpeed,
            )
            if (expandedSurfacesMounted && !localIsInPipMode) {
                Media3SubtitleOverlay(
                    enabled = playerState.selectedSubtitleUrl != null,
                    isAutoGenerated =
                        playerState.availableSubtitles
                            .firstOrNull { it.url == playerState.selectedSubtitleUrl }
                            ?.isAutoGenerated == true,
                    style = screenState.subtitleStyle,
                    bottomPadding =
                        screenState.subtitleStyle.bottomPaddingFor(
                            isFullscreen = screenState.isFullscreen,
                            isVertical = configuration.screenHeightDp > configuration.screenWidthDp,
                        ),
                    videoAspectRatio = videoAspectRatio,
                    resizeMode = resizeMode,
                    modifier = Modifier.fillMaxSize().placedWhen(expandedSurfacesPlaced),
                )
            }
            // Until this video's first frame, the box shows its thumbnail: the card it grew out of,
            // rather than black or the previous video's last frame.
            if (firstFrameVideoId != video.id) {
                VideoThumbnailImage(
                    videoId = video.id,
                    model = video.thumbnailUrl,
                    contentDescription = null,
                    modifier =
                        Modifier.fillMaxSize().graphicsLayer {
                            shape = RoundedCornerShape(playerSheetState.morphCornerRadiusPx())
                            clip = true
                        },
                    contentScale = ContentScale.Crop,
                    placeholderKey = playerSheetState.posterPlaceholderKey,
                )
            }
        } // end zoomable layer

        // Non-zoomable UI overlays (always at full-screen position)
        if (expandedSurfacesMounted && !localIsInPipMode) {
            Box(modifier = Modifier.matchParentSize().placedWhen(expandedSurfacesPlaced)) {
                VideoStageOverlays(session = session)
            }
        }

        // Controls overlay - fully expanded only
        var showRemainingTime by rememberSaveable { mutableStateOf(false) }
        if (!playerUiState.isUpcoming && expandedSurfacesMounted && !localIsInPipMode) {
            VideoStageControls(
                session = session,
                expandedSurfacesPlaced = expandedSurfacesPlaced,
                videoAspectRatio = videoAspectRatio,
                resizeMode = resizeMode,
                canGoPrevious = canGoPrevious,
                isCommentsAvailable = isCommentsAvailable,
                canUseFullscreenSidePanel = canUseFullscreenSidePanel,
                showRemainingTime = showRemainingTime,
                onToggleRemainingTime = { showRemainingTime = !showRemainingTime },
                rememberSubtitleLanguage = rememberSubtitleLanguage,
                onSbSubmitClick = onSbSubmitClick,
                onCastClick = onCastClick,
            )
        }

        // The controls carry their own spinner in the play slot; the touch lock hides it with them.
        if (expandedSurfacesMounted && !isMinimized && !localIsInPipMode) {
            MediaBufferingIndicator(
                visible = playerState.isBuffering && (!screenState.showControls || screenState.isTouchLocked),
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (expandedSurfacesPlacedNow && expandedSurfacesMounted && !localIsInPipMode) {
            Box(modifier = Modifier.matchParentSize()) {
                PlayerStatsLayer(session = session)
            }
        }
    }
}
