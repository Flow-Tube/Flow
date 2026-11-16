package io.github.aedev.flow.ui.components.shorts

import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.PlayerView
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.ShortVideo
import io.github.aedev.flow.data.model.toVideo
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.shorts.ShortsPlayerPool
import io.github.aedev.flow.player.toDisplayAspectRatioOrNull
import io.github.aedev.flow.ui.components.shared.MediaBufferingIndicator
import io.github.aedev.flow.ui.components.shared.MediaSeekBar
import io.github.aedev.flow.ui.components.shared.sharedSubscriptionBellViewModel
import io.github.aedev.flow.ui.components.shared.toggleHapticType
import io.github.aedev.flow.ui.components.videoplayer.ambient.VideoAmbientBackground
import io.github.aedev.flow.ui.components.videoplayer.ambient.rememberAmbientFrame
import io.github.aedev.flow.ui.components.videoplayer.overlay.SpeedBoostOverlay
import io.github.aedev.flow.ui.screens.shorts.ShortsViewModel
import io.github.aedev.flow.ui.theme.PlayerScrim
import io.github.aedev.flow.utils.formatViewCount
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAUSE_INDICATOR_MS = 600L
private const val ON_DEMAND_CONTROLS_MS = 2_000L
private const val FAST_FORWARD_SPEED = 2.0f
private const val CENTRE_TAP_MIN = 0.25f
private const val CENTRE_TAP_MAX = 0.75f
private val ChromeStartPadding = 16.dp
private val ChromeEndPadding = 8.dp
private val MetadataEndPadding = 16.dp
private val AutoScrollBadgeEndPadding = 16.dp

@Composable
internal fun ShortsReelPage(
    short: ShortVideo,
    isActive: Boolean,
    pageIndex: Int,
    viewModel: ShortsViewModel,
    settings: ShortsReelSettings,
    sheetInsets: ShortsSheetInsetState,
    screenSheetOpen: Boolean,
    actions: ShortsReelActions,
    modifier: Modifier = Modifier,
    bottomNavOverlayPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val style = settings.style
    val pageState = remember(short.id) { ShortsReelPageState() }
    // Keyed on identity only: including isActive reset hasRecordedWatched every time the page went
    // off screen, so swiping back and forth re-fed the same watch signal to the engine.
    val sessionState = remember(short.id) { ShortsReelSessionState() }
    val autoAdvanceState =
        remember(short.id, isActive, settings.playbackMode, settings.autoScrollSeconds) { ShortsReelAutoAdvanceState() }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val playerPool = remember { ShortsPlayerPool.getInstance() }
    val video = remember(short) { short.toVideo() }

    val isLiked by remember(short.id) { viewModel.isVideoLikedState(short.id) }.collectAsState()
    val isSubscribed by remember(short.channelId) { viewModel.isChannelSubscribedState(short.channelId) }.collectAsState()
    val bell = sharedSubscriptionBellViewModel()
    val notifyingChannelIds by bell.notifyingChannelIds.collectAsState()
    val isSaved by remember(short.id) { viewModel.isShortSavedState(short.id) }.collectAsState()

    // In Picture-in-Picture the window is a thumbnail: every overlay drawn at its authored size
    // would bury the reel it is meant to annotate, so the page renders the video and nothing else.
    val isInPip by GlobalPlayerState.isInPipMode.collectAsState()
    val controlsVisible = !isInPip && (!style.controlsOnDemand || sessionState.showOnDemandControls)
    val sheetOpen = screenSheetOpen
    val chromeAlpha =
        animateFloatAsState(
            targetValue = if (sheetOpen) 0f else 1f,
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "shorts_chrome_alpha",
        )
    val seekBarBottomPadding = bottomNavOverlayPadding.coerceAtLeast(0.dp)
    val controlsBottomPadding = seekBarBottomPadding + ShortsOverlayDefaults.ControlsBottomOffset
    val seekBarInteractionSource = remember { MutableInteractionSource() }

    val playerView =
        remember {
            PlayerView(context).apply {
                useController = false
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }
    val ambientActive = isActive && settings.ambientModeEnabled && !isInPip
    val ambientFrame = rememberAmbientFrame(playerView, ambientActive) { playerPool.ownedPlayer(pageIndex, short.id)?.isPlaying == true }
    var attachedPlayer by remember { mutableStateOf<Player?>(null) }
    var ambientVideoAspect by remember { mutableStateOf<Float?>(null) }

    ShortsReelPlaybackEffects(
        playerPool = playerPool,
        playerView = playerView,
        short = short,
        pageIndex = pageIndex,
        isActive = isActive,
        settings = settings,
        pageState = pageState,
        sessionState = sessionState,
        autoAdvanceState = autoAdvanceState,
        sheetOpen = sheetOpen,
        viewModel = viewModel,
        onVideoEnded = actions.onVideoEnded,
        onAttachedPlayerChange = { attachedPlayer = it },
    )

    DisposableEffect(attachedPlayer, ambientActive) {
        val player = attachedPlayer
        if (player == null || !ambientActive) return@DisposableEffect onDispose { }
        ambientVideoAspect = player.videoSize.toDisplayAspectRatioOrNull()
        val listener =
            object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    videoSize.toDisplayAspectRatioOrNull()?.let { ambientVideoAspect = it }
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(pageState.showPauseIndicator) {
        if (pageState.showPauseIndicator) {
            delay(PAUSE_INDICATOR_MS)
            pageState.showPauseIndicator = false
        }
    }

    LaunchedEffect(isActive, style.controlsOnDemand, short.id) {
        if (!isActive || !style.controlsOnDemand) sessionState.showOnDemandControls = false
    }

    LaunchedEffect(sessionState.showOnDemandControls, style.controlsOnDemand) {
        if (style.controlsOnDemand && sessionState.showOnDemandControls) {
            delay(ON_DEMAND_CONTROLS_MS)
            sessionState.showOnDemandControls = false
        }
    }

    fun togglePlaybackWithFeedback() {
        playerPool.togglePlayPause()
        playerPool.ownedPlayer(pageIndex, short.id)?.let { pageState.isPlaying = it.isPlaying }
        pageState.showPauseIndicator = true
        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
    }

    fun toggleSubscription() {
        scope.launch { viewModel.toggleSubscription(short.channelId, short.channelName, short.channelThumbnailUrl) }
        haptic.performHapticFeedback(toggleHapticType(nowOn = !isSubscribed))
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .shortsSheetInset(sheetInsets)
                .background(PlayerScrim),
    ) {
        if (ambientActive) {
            VideoAmbientBackground(
                frame = ambientFrame.frame,
                videoAspect = ambientVideoAspect,
                modifier = Modifier.fillMaxSize(),
            )
        }

        AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize())

        ShortsReelPoster(
            visible = !pageState.hasStartedPlaying && !pageState.isBuffering,
            videoId = short.id,
            thumbnailUrl = short.thumbnailUrl,
        )

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(style, isLiked, sessionState.showOnDemandControls) {
                        detectTapGestures(
                            onTap = { offset ->
                                if (style.controlsOnDemand && isCentreTap(offset, size) && !sessionState.showOnDemandControls) {
                                    sessionState.showOnDemandControls = true
                                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                } else {
                                    togglePlaybackWithFeedback()
                                }
                            },
                            onDoubleTap = {
                                if (!isLiked) {
                                    scope.launch { viewModel.toggleLike(short) }
                                    pageState.showLikeAnimation = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            },
                            onPress = {
                                try {
                                    awaitRelease()
                                } finally {
                                    if (pageState.isFastForwarding) {
                                        pageState.isFastForwarding = false
                                        playerPool.resetPlaybackSpeed()
                                    }
                                }
                            },
                            onLongPress = { offset ->
                                if (style.controlsOnDemand && isCentreTap(offset, size)) {
                                    actions.onCommentsClick()
                                } else {
                                    pageState.isFastForwarding = true
                                    playerPool.setPlaybackSpeed(FAST_FORWARD_SPEED)
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                        )
                    },
        )

        SpeedBoostOverlay(
            isVisible = pageState.isFastForwarding,
            speed = FAST_FORWARD_SPEED,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = ShortsOverlayDefaults.SpeedBoostTopPadding),
        )

        ShortsAutoScrollBadge(
            visible = controlsVisible && isActive && settings.playbackMode == SHORTS_PLAYBACK_AUTO_INTERVAL,
            seconds = settings.autoScrollSeconds,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = ShortsOverlayDefaults.AutoScrollBadgeTopPadding, end = AutoScrollBadgeEndPadding),
        )

        MediaBufferingIndicator(
            visible = pageState.isBuffering,
            modifier = Modifier.align(Alignment.Center),
        )

        ShortsPauseIndicator(
            visible = pageState.showPauseIndicator && !pageState.isBuffering,
            isPlaying = pageState.isPlaying,
            modifier = Modifier.align(Alignment.Center),
        )

        ShortsLikeBurst(
            visible = pageState.showLikeAnimation,
            onFinished = { pageState.showLikeAnimation = false },
            modifier = Modifier.align(Alignment.Center),
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(),
        ) {
            val likeCount = short.likeCount.takeIf { it > 0L }?.let(::formatViewCount)
            val commentCount = short.commentCount.takeIf { it > 0L }?.let(::formatViewCount)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = chromeAlpha.value }
                        .padding(bottom = controlsBottomPadding, start = ChromeStartPadding, end = ChromeEndPadding),
                verticalAlignment = Alignment.Bottom,
            ) {
                ShortsMetadataOverlay(
                    short = short,
                    video = video,
                    isSubscribed = isSubscribed,
                    isPlaying = isActive && pageState.isPlaying,
                    style = style,
                    onChannelClick = actions.onChannelClick,
                    onSubscribeToggle = ::toggleSubscription,
                    onDescriptionClick = actions.onDescriptionClick,
                    isNotificationsEnabled = short.channelId in notifyingChannelIds,
                    onNotificationChange = { bell.setNotifications(short.channelId, it) },
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(end = MetadataEndPadding),
                )
                ShortsActionRail(
                    isLiked = isLiked,
                    likeLabel = if (style.showRailLabels) likeCount ?: stringResource(R.string.action_like) else likeCount.orEmpty(),
                    onLikeClick = {
                        scope.launch { viewModel.toggleLike(short) }
                        haptic.performHapticFeedback(toggleHapticType(nowOn = !isLiked))
                    },
                    commentLabel =
                        if (style.showRailLabels) {
                            commentCount ?: stringResource(
                                R.string.action_comments,
                            )
                        } else {
                            commentCount.orEmpty()
                        },
                    onCommentsClick = actions.onCommentsClick,
                    isSaved = isSaved,
                    saveLabel = if (style.showRailLabels) stringResource(R.string.action_save) else "",
                    onSaveClick = {
                        viewModel.toggleSaveShort(short)
                        if (style.toastOnSave) {
                            val message = context.getString(if (isSaved) R.string.shorts_unsaved else R.string.shorts_saved)
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                        haptic.performHapticFeedback(toggleHapticType(nowOn = !isSaved))
                    },
                    shareLabel = if (style.showRailLabels) stringResource(R.string.action_share) else "",
                    onShareClick = actions.onShareClick,
                    moreLabel = if (style.showRailLabels) stringResource(R.string.cd_more_options) else "",
                    onMoreClick = actions.onMoreClick,
                    channelAvatarUrl = short.channelThumbnailUrl,
                    channelName = short.channelName,
                    isDiscSpinning = isActive && pageState.isPlaying,
                )
            }
        }

        if (pageState.duration > 0 && !isInPip) {
            MediaSeekBar(
                value = {
                    if (pageState.isDragging) {
                        pageState.dragProgress
                    } else {
                        (pageState.currentPosition.toFloat() / pageState.duration.toFloat()).coerceIn(0f, 1f)
                    }
                },
                onValueChange = { progress ->
                    pageState.isDragging = true
                    pageState.dragProgress = progress.coerceIn(0f, 1f)
                },
                onValueChangeFinished = {
                    playerPool.seekTo((pageState.dragProgress.coerceIn(0f, 1f) * pageState.duration).toLong())
                    pageState.isDragging = false
                },
                interactionSource = seekBarInteractionSource,
                duration = pageState.duration,
                edgeAligned = true,
                enabled = isActive,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .graphicsLayer { alpha = chromeAlpha.value }
                        .padding(bottom = seekBarBottomPadding)
                        .height(ShortsOverlayDefaults.SeekBarTouchHeight)
                        .zIndex(1f),
            )
        }
    }
}

private fun isCentreTap(
    offset: Offset,
    size: IntSize,
): Boolean =
    offset.x in (size.width * CENTRE_TAP_MIN)..(size.width * CENTRE_TAP_MAX) &&
        offset.y in (size.height * CENTRE_TAP_MIN)..(size.height * CENTRE_TAP_MAX)
