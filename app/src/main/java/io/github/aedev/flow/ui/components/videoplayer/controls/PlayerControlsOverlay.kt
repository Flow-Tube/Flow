package io.github.aedev.flow.ui.components.videoplayer.controls

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerOverlayPreferences
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.ui.theme.PlayerScrim
import io.github.aedev.flow.utils.formatMultiplierLabel

private val OverlayActionButtonSize = 40.dp
private val OverlayActionIconSize = 24.dp
private val OverlayActionSpacing = 8.dp
internal val OverlayPillHeight = 28.dp
private val OverlayExpandIconSize = 18.dp
private val OverlayControlRowMinHeight = 44.dp
private val OverlayActionIconInset = (OverlayActionButtonSize - OverlayActionIconSize) / 2f

/** Tint over the video while the controls are up; the loading state blacks it out entirely. */
private const val CONTROLS_BACKDROP_ALPHA = 0.24f

/**
 * The on-video controls.
 *
 * The overlay is deliberately kept composed while it is hidden — the expanded player holds it warm
 * so the first reveal has nothing to mount — which makes [isLayerPlaced] and the internal placement
 * flag the only honest signal that anything inside is on screen. Everything that animates on its
 * own clock is gated on that signal rather than on being composed.
 */
@Composable
internal fun PlayerControlsOverlay(
    state: PlayerControlsUiState,
    actions: PlayerControlActions,
    currentPosition: () -> Long,
    modifier: Modifier = Modifier,
    bufferedPercentage: Float = 0f,
    windowInsets: WindowInsets = WindowInsets.systemBars,
    isLayerPlaced: () -> Boolean = { true },
) {
    val resizeModes =
        listOf(
            stringResource(R.string.resize_fit),
            stringResource(R.string.resize_fill),
            stringResource(R.string.resize_zoom),
        )

    val scrubController =
        rememberPlayerScrubController(
            currentPosition = currentPosition,
            isLive = state.isLive,
            onSeek = actions.onSeek,
            onScrubbingChange = actions.onScrubbingChange,
        )
    val isScrubbing = scrubController.isScrubbing
    val displayedPosition = scrubController.displayedPosition
    val onScrubProgress = scrubController.onScrubProgress
    val onScrubFinished = scrubController.onScrubFinished

    val lockOverlay =
        rememberLockOverlayVisibility(
            isTouchLocked = state.isTouchLocked,
            revealSignal = state.lockOverlayRevealSignal,
        )
    val isLockOverlayVisible = lockOverlay.isVisible
    val revealLockOverlay = lockOverlay.reveal

    val currentChapter by remember(state.chapters) {
        derivedStateOf {
            val positionSeconds = displayedPosition() / 1000
            state.chapters.lastOrNull { it.startTimeSeconds <= positionSeconds }
        }
    }

    val sponsorSegments by EnhancedPlayerManager.getInstance().sponsorSegments.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val playerPreferences = remember { PlayerPreferences(context) }
    val overlayPreferences by playerPreferences.overlayPreferences.collectAsStateWithLifecycle(
        initialValue = remember { PlayerOverlayPreferences() },
    )
    val sponsorSegmentColors =
        remember(overlayPreferences.sponsorCategoryColors) {
            overlayPreferences.sponsorCategoryColors.mapValues { (_, argb) -> Color(argb) }
        }
    val overlayCommentsEnabled = overlayPreferences.commentsEnabled
    val fullscreenSeekbarHorizontalPaddingDp = overlayPreferences.fullscreenSeekbarHorizontalPaddingDp
    val portraitSeekbarHorizontalPaddingDp = overlayPreferences.portraitSeekbarHorizontalPaddingDp
    val isFullscreen = state.isFullscreen
    val isPortraitFullscreen = isFullscreen && state.isPortraitFullscreen
    val isLandscapeFullscreen = isFullscreen && !isPortraitFullscreen
    // The stage hands this overlay no insets, so landscape fullscreen clears the camera cutout
    // itself: the side one on phones, the top one on tablets (#968).
    val cutoutPadding = WindowInsets.displayCutout.asPaddingValues()
    val cutoutHorizontalPadding =
        maxOf(
            cutoutPadding.calculateLeftPadding(LayoutDirection.Ltr),
            cutoutPadding.calculateRightPadding(LayoutDirection.Ltr),
        )
    // Landscape fullscreen insets the controls well clear of the rounded corners and the gesture
    // bar. Portrait fullscreen is the same width as the portrait player, so it keeps the portrait
    // insets and only the vertical breathing room changes.
    // Eased rather than switched (#1065). Leaving fullscreen flips this flag immediately, but the
    // window only turns a few hundred milliseconds later, so a hard switch repainted the controls
    // with portrait insets while the window was still landscape — the seek bar visibly snapping out
    // to the edges before anything rotated. Easing carries them across that gap instead.
    val fullscreenSeekbarBottomPadding =
        animatedInset(
            target =
                when {
                    isPortraitFullscreen -> 12.dp
                    isFullscreen -> 30.dp
                    else -> 0.dp
                },
            label = "seekbarBottomPadding",
        )
    val bottomControlHorizontalPadding =
        animatedInset(
            target =
                when {
                    isPortraitFullscreen -> 16.dp
                    isFullscreen -> maxOf(56.dp, cutoutHorizontalPadding)
                    else -> 12.dp
                },
            label = "bottomControlPadding",
        )
    val topControlHorizontalPadding = (bottomControlHorizontalPadding - OverlayActionIconInset).coerceAtLeast(0.dp)
    val topControlVerticalPadding = if (isFullscreen) 8.dp else 4.dp
    val fullscreenTopPadding =
        when {
            isPortraitFullscreen -> cutoutPadding.calculateTopPadding().coerceAtLeast(16.dp)
            isLandscapeFullscreen -> cutoutPadding.calculateTopPadding()
            else -> 0.dp
        }
    val seekbarHorizontalPadding =
        animatedInset(
            target =
                if (isLandscapeFullscreen) {
                    maxOf(fullscreenSeekbarHorizontalPaddingDp.dp, cutoutHorizontalPadding)
                } else {
                    portraitSeekbarHorizontalPaddingDp.dp
                },
            label = "seekbarHorizontalPadding",
        )
    val pillsRowMinHeight = if (isFullscreen) OverlayControlRowMinHeight else 30.dp
    val chapterMaxWidth = if (isLandscapeFullscreen) 200.dp else 96.dp
    val qualityBadge = remember(state.qualityLabel) { state.qualityLabel?.let(::compactPlayerQualityBadge) }
    val compactQualityLabel = qualityBadge?.let { playerQualityBadgeLabel(it) }
    val speedIndicatorLabel = remember(state.playbackSpeed) { formatMultiplierLabel(state.playbackSpeed) }

    val showControlsWhileLoading = overlayPreferences.showControlsWhileLoading
    val isInitialLoading by remember(state.isBuffering, state.duration) {
        derivedStateOf { state.isBuffering && state.duration <= 0L && displayedPosition() <= 0L }
    }
    // When the user opts in, keep the controls visible during the initial load so volume/brightness/
    // back/etc. can be used before the first frame arrives.
    val hideControlsForLoading = isInitialLoading && !showControlsWhileLoading

    val seekbarContent =
        remember(
            state.chapters,
            sponsorSegments,
            sponsorSegmentColors,
            state.commentMarks,
            bufferedPercentage,
            state.storyboard,
            state.heatmap,
            overlayPreferences.scrubPreviewStyle,
        ) {
            PlayerSeekbarContent(
                chapters = state.chapters,
                sponsorSegments = sponsorSegments,
                sponsorColors = sponsorSegmentColors,
                commentMarks = state.commentMarks,
                bufferedPercentage = bufferedPercentage,
                storyboard = state.storyboard,
                heatmap = state.heatmap,
                previewStyle = overlayPreferences.scrubPreviewStyle,
            )
        }
    val bottomBarMetrics =
        PlayerBottomBarMetrics(
            pillHeight = OverlayPillHeight,
            pillsRowMinHeight = pillsRowMinHeight,
            actionSpacing = OverlayActionSpacing,
            horizontalPadding = bottomControlHorizontalPadding,
            seekbarHorizontalPadding = seekbarHorizontalPadding,
            seekbarBottomPadding = fullscreenSeekbarBottomPadding,
            expandIconSize = OverlayExpandIconSize,
            chapterMaxWidth = chapterMaxWidth,
        )

    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    // A scrub, a seek swipe, a speed boost or a pinch each clear the overlay down to the bar and what sits on it:
    // whatever the gesture is showing has the picture to itself for as long as it lasts.
    val isCleared = isScrubbing || state.isGestureReadoutActive
    // Held as State and read in the draw lambda below so the backdrop fades without recomposing the
    // controls under it.
    val uncleared =
        animateFloatAsState(
            targetValue = if (isCleared) 0f else 1f,
            animationSpec = fadeSpec,
            label = "overlayCleared",
        )

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .windowInsetsPadding(windowInsets),
    ) {
        val isVisible = state.isVisible
        val controlsAlpha = remember { Animatable(if (isVisible) 1f else 0f) }
        var controlsPlaced by remember { mutableStateOf(isVisible) }
        LaunchedEffect(isVisible) {
            if (isVisible) controlsPlaced = true
            controlsAlpha.animateTo(
                targetValue = if (isVisible) 1f else 0f,
                animationSpec = fadeSpec,
            )
            if (!isVisible) controlsPlaced = false
        }
        // The one signal that anything inside this layer is actually on screen: the placement flag
        // and the caller's own answer for the stage that hosts it. Remembered so the gates below
        // keep their derived state across recompositions of this overlay.
        val currentIsLayerPlaced by rememberUpdatedState(isLayerPlaced)
        val isLayerOnScreen = remember { { controlsPlaced && currentIsLayerPlaced() } }

        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = controlsAlpha.value }
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            if (controlsPlaced) placeable.place(0, 0)
                        }
                    }.drawBehind {
                        drawRect(
                            when {
                                state.isTouchLocked -> Color.Transparent
                                isInitialLoading -> PlayerScrim
                                else -> PlayerScrim.copy(alpha = CONTROLS_BACKDROP_ALPHA * uncleared.value)
                            },
                        )
                    },
        ) {
            if (isPortraitFullscreen && !state.isTouchLocked) {
                PortraitFullscreenEdgeScrims(modifier = Modifier.matchParentSize())
            }
            if (state.isTouchLocked) {
                PlayerLockedControls(
                    isOverlayVisible = isLockOverlayVisible,
                    positionProvider = displayedPosition,
                    duration = state.duration,
                    isLive = state.isLive,
                    isFullscreen = isFullscreen,
                    showRemainingTime = state.showRemainingTime,
                    seekbarContent = seekbarContent,
                    pillHeight = OverlayPillHeight,
                    topPadding = fullscreenTopPadding,
                    seekbarHorizontalPadding = seekbarHorizontalPadding,
                    seekbarBottomPadding = fullscreenSeekbarBottomPadding,
                    onRevealUnlock = revealLockOverlay,
                    onUnlock = actions.onTouchLockToggle,
                )
            } else {
                if (!hideControlsForLoading) {
                    AnimatedVisibility(
                        visible = !isCleared,
                        enter = fadeIn(fadeSpec),
                        exit = fadeOut(fadeSpec),
                        modifier = Modifier.align(Alignment.TopStart),
                    ) {
                        VideoPlayerTopBar(
                            preferences = overlayPreferences,
                            isFullscreen = isFullscreen,
                            isPortraitFullscreen = isPortraitFullscreen,
                            videoTitle = state.videoTitle,
                            channelName = state.channelName,
                            resizeMode = state.resizeMode,
                            resizeModeLabels = resizeModes,
                            isPipSupported = state.isPipSupported,
                            sbSubmitEnabled = state.sbSubmitEnabled,
                            isSponsorBlockAvailable = state.isSponsorBlockAvailable,
                            isSponsorBlockOffForVideo = state.isSponsorBlockOffForVideo,
                            isCasting = state.isCasting,
                            isSubtitlesEnabled = state.isSubtitlesEnabled,
                            isAutoplayOn = state.autoplayEnabled,
                            isLooping = state.isLooping,
                            isSleepTimerActive = state.isSleepTimerActive,
                            lockModeEnabled = state.lockModeEnabled,
                            isLiveChatAvailable = state.isLiveChatAvailable,
                            hasQueue = state.hasQueue,
                            topPadding = fullscreenTopPadding,
                            horizontalPadding = topControlHorizontalPadding,
                            verticalPadding = topControlVerticalPadding,
                            rowMinHeight = OverlayControlRowMinHeight,
                            pillHeight = OverlayPillHeight,
                            actionButtonSize = OverlayActionButtonSize,
                            actionIconSize = OverlayActionIconSize,
                            actionSpacing = OverlayActionSpacing,
                            actions = actions,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = !isCleared,
                    enter = fadeIn(fadeSpec),
                    exit = fadeOut(fadeSpec),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    PlayerTransportControls(
                        isPlaying = state.isPlaying,
                        hasEnded = state.hasEnded,
                        showBufferingSpinner = state.isBuffering || isInitialLoading,
                        hasPrevious = state.hasPrevious,
                        hasNext = state.hasNext,
                        showSkipButtons = !hideControlsForLoading && overlayPreferences.nextPrevButtonsEnabled,
                        // Stepping a running player just fights playback, and a live edge has nothing
                        // to step through.
                        showFrameStep =
                            overlayPreferences.frameStepButtonsEnabled &&
                                !state.isPlaying && !state.isLive && !state.hasEnded && state.duration > 0L,
                        actions = actions,
                        isLayerVisible = isLayerOnScreen,
                    )
                }

                if (!hideControlsForLoading) {
                    PlayerBottomBar(
                        positionProvider = displayedPosition,
                        duration = state.duration,
                        isLive = state.isLive,
                        isFullscreen = isFullscreen,
                        isPortraitFullscreen = isPortraitFullscreen,
                        showRemainingTime = state.showRemainingTime,
                        showCommentsButton =
                            overlayCommentsEnabled &&
                                state.isCommentsAvailable &&
                                isFullscreen &&
                                !isPortraitFullscreen,
                        isCommentsPanelOpen = state.isCommentsPanelOpen,
                        currentChapter = currentChapter,
                        compactQualityLabel = compactQualityLabel,
                        speedIndicatorLabel = speedIndicatorLabel.takeIf { overlayPreferences.speedIndicatorEnabled },
                        seekbarContent = seekbarContent,
                        metrics = bottomBarMetrics,
                        actions = actions,
                        onScrubProgress = onScrubProgress,
                        onScrubFinished = onScrubFinished,
                        isScrubbing = scrubController.isScrubbing,
                        hidePills = isCleared,
                        isLayerVisible = isLayerOnScreen,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }

                // The second tap of a double tap, a tap during a double-tap seek and a tap on a
                // fading-out button all belong to the player, not to the button under the finger.
                if (!isVisible || state.yieldsTapsToPlayer) {
                    PlayerTapShield(modifier = Modifier.matchParentSize())
                }
            }
        }

        PlayerIdleProgressStrip(
            position = overlayPreferences.idleProgressBar,
            visible = !isVisible && !isFullscreen && !isInitialLoading && !state.isTouchLocked,
            fadeSpec = fadeSpec,
            positionProvider = displayedPosition,
            duration = state.duration,
            isLive = state.isLive,
            content = seekbarContent,
            horizontalPadding = seekbarHorizontalPadding,
            onScrubProgress = onScrubProgress,
            onScrubFinished = onScrubFinished,
            isScrubbing = scrubController.isScrubbing,
        )
    }
}

/**
 * Eases one of the control insets between its fullscreen and windowed values (#1065).
 *
 * On an effects spec, not a spatial one: the Expressive spatial springs overshoot their target,
 * and an inset that undershoots zero on the way down is a negative padding, which Modifier.padding
 * rejects outright. The clamp keeps that true even if the theme's spec is changed later.
 */
@Composable
private fun animatedInset(
    target: Dp,
    label: String,
): Dp {
    val value by animateDpAsState(
        targetValue = target,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = label,
    )
    return value.coerceAtLeast(0.dp)
}
