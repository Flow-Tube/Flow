package io.github.aedev.flow.ui.components.musicplayer.full

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.aedev.flow.data.local.MusicPlayerBackgroundStyle
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.SleepTimerManager
import io.github.aedev.flow.ui.components.musicplayer.common.PlayerBackdropColors
import io.github.aedev.flow.ui.components.musicplayer.controls.PlayerProgressSlider
import io.github.aedev.flow.ui.components.musicplayer.lyrics.DEFAULT_LYRICS_TEXT_SIZE
import io.github.aedev.flow.ui.components.musicplayer.lyrics.LARGE_LYRICS_TEXT_SIZE
import io.github.aedev.flow.ui.components.musicplayer.lyrics.lyricsBackdrop
import io.github.aedev.flow.ui.components.musicplayer.motion.MusicSideSwipeHud
import io.github.aedev.flow.ui.components.musicplayer.motion.musicSideSwipes
import io.github.aedev.flow.ui.components.musicplayer.motion.rememberMusicSideSwipes
import io.github.aedev.flow.ui.components.musicplayer.queue.queuePullUpGesture
import io.github.aedev.flow.ui.components.musicplayer.queue.rememberQueuePullUpState
import io.github.aedev.flow.ui.components.musicplayer.sheet.PlayerPanel
import io.github.aedev.flow.ui.components.musicplayer.sheet.PlayerPanelRequestEffect
import io.github.aedev.flow.ui.components.shared.MediaPalette
import io.github.aedev.flow.ui.components.shared.MediaPlaybackControls
import io.github.aedev.flow.ui.components.shared.MediaSleepTimerSheet
import io.github.aedev.flow.ui.components.shared.SkipDirection
import io.github.aedev.flow.ui.screens.music.MusicPlayerViewModel
import io.github.aedev.flow.ui.screens.music.MusicVideoSwitchViewModel
import io.github.aedev.flow.ui.screens.music.sharedMusicPlayerViewModel
import io.github.aedev.flow.ui.utils.LocalWindowIsLandscape
import io.github.aedev.flow.ui.utils.LocalWindowSizeClass

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullMusicPlayerContent(
    track: MusicTrack,
    isPlayerSheetExpanded: Boolean,
    palette: MediaPalette,
    backdrop: PlayerBackdropColors,
    backgroundStyle: MusicPlayerBackgroundStyle,
    hideArtwork: Boolean,
    sleepTimerButton: Boolean,
    splitTransport: Boolean = false,
    lyricsOnCover: Boolean = false,
    // The transport, seek bar, like and download and the action row draw with this scheme.
    controlScheme: ColorScheme = MaterialTheme.colorScheme,
    onLyricsShownChange: (Boolean) -> Unit = {},
    panelRequest: PlayerPanel? = null,
    onPanelRequestHandled: () -> Unit = {},
    viewModel: MusicPlayerViewModel = sharedMusicPlayerViewModel(),
    videoSwitch: MusicVideoSwitchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val artistScope = rememberCoroutineScope()
    val positionState = viewModel.currentPositionMs.collectAsState()
    val density = LocalDensity.current
    val colorScheme = MaterialTheme.colorScheme

    val thumbnailUrl = uiState.currentTrack?.highResThumbnailUrl ?: track.highResThumbnailUrl
    val sheets = remember { FullPlayerSheetState() }
    var previewDirection by remember { mutableStateOf<SkipDirection?>(null) }
    val musicPlayer by EnhancedMusicPlayerManager.playerInstance.collectAsState()
    val videoSwitchEnabled by videoSwitch.isEnabled.collectAsState()
    val videoMode by videoSwitch.videoMode.collectAsState()
    val videoLoading by videoSwitch.isLoading.collectAsState()
    var videoShowing by remember { mutableStateOf(false) }
    // The surface mounts only on the open player: mounting it is what makes the service decode video.
    val videoPlayer = musicPlayer?.takeIf { videoSwitchEnabled && videoMode && isPlayerSheetExpanded }

    val previousTrack = uiState.queue.getOrNull(uiState.currentQueueIndex - 1)
    val nextTrack = uiState.queue.getOrNull(uiState.currentQueueIndex + 1)
    val previewTrack =
        when (previewDirection) {
            SkipDirection.NEXT -> nextTrack
            SkipDirection.PREVIOUS -> previousTrack
            null -> null
        }

    // Both the hold-press preview and the artwork drag carousel swap the background art too,
    // so the immersive/blur backdrops track whatever cover the user is currently peeking at.
    var artworkDragPreview by remember { mutableStateOf<SkipDirection?>(null) }
    LaunchedEffect(thumbnailUrl) { artworkDragPreview = null }
    val backgroundPreviewTrack =
        when (artworkDragPreview ?: previewDirection) {
            SkipDirection.NEXT -> nextTrack
            SkipDirection.PREVIOUS -> previousTrack
            null -> null
        }
    val backgroundThumbnailUrl = backgroundPreviewTrack?.highResThumbnailUrl ?: thumbnailUrl

    LaunchedEffect(musicPlayer) {
        SleepTimerManager.attachToPlayer(
            player = musicPlayer,
        ) {
            EnhancedMusicPlayerManager.player?.pause()
        }
    }

    var showLyricsSheet by remember { mutableStateOf(false) }
    DisposableEffect(showLyricsSheet) {
        onLyricsShownChange(showLyricsSheet)
        onDispose { onLyricsShownChange(false) }
    }

    FullPlayerSheets(sheets, uiState.currentTrack, isPlayerSheetExpanded)

    LaunchedEffect(track.videoId) {
        // A song swapped for its video keeps the song's related list rather than fetching another.
        viewModel.fetchRelatedContent(videoSwitch.listenId(track.videoId))
        // [track] comes from the manager a frame late, so a different song here only means a newer one was chosen.
        if (EnhancedMusicPlayerManager.currentTrack.value?.videoId == track.videoId) viewModel.warmLyrics(track)
    }

    DisposableEffect(isPlayerSheetExpanded) {
        viewModel.setPlayerOpen(isPlayerSheetExpanded)
        onDispose { viewModel.setPlayerOpen(false) }
    }

    if (isPlayerSheetExpanded) {
        DisposableEffect(Unit) {
            EnhancedMusicPlayerManager.acquirePreciseProgress()
            onDispose { EnhancedMusicPlayerManager.releasePreciseProgress() }
        }
    }

    val queueSwipes by viewModel.queueSwipes.collectAsState()
    val queueActions = rememberPlayerQueueActions(viewModel, sheets)

    val immersiveBackground = backgroundStyle == MusicPlayerBackgroundStyle.IMMERSIVE
    var lyricsInPlace by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(immersiveBackground) { if (!immersiveBackground) lyricsInPlace = false }
    val displayTitle = previewTrack?.title ?: uiState.currentTrack?.title ?: track.title
    val displayArtist = previewTrack?.artist ?: uiState.currentTrack?.artist ?: track.artist

    val panes = rememberPlayerPanes()
    val layout =
        musicPlayerLayoutFor(LocalWindowSizeClass.current, LocalWindowIsLandscape.current).let {
            // The side pane needs two partitions; a window the scaffold keeps to one splits instead.
            if (it == MusicPlayerLayout.WIDE && !panes.showsSidePane) MusicPlayerLayout.SPLIT else it
        }
    val isWide = layout == MusicPlayerLayout.WIDE
    val inPlaceAvailable = lyricsShowInPlace(lyricsOnCover, backgroundStyle, layout)
    val lyricsInPlaceShown = lyricsInPlace && inPlaceAvailable
    var sidePaneTab by rememberSaveable { mutableStateOf(PlayerSidePaneTab.UP_NEXT) }
    val selectSidePaneTab: (PlayerSidePaneTab) -> Unit = { tab ->
        if (tab == PlayerSidePaneTab.LYRICS) uiState.currentTrack?.let { viewModel.ensureLyricsLoaded(it) }
        sidePaneTab = tab
    }
    val openLyricsSheet = {
        uiState.currentTrack?.let { viewModel.ensureLyricsLoaded(it) }
        showLyricsSheet = true
    }
    val showLyricsInPlace = {
        uiState.currentTrack?.let { viewModel.ensureLyricsLoaded(it) }
        lyricsInPlace = true
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().musicPlayerKeyboardShortcuts(isPlayerSheetExpanded && !sheets.sleepTimer, viewModel),
    ) {
        val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBarPx = with(density) { navBarPadding.toPx() }

        val reservedHeight = statusBarPadding + 56.dp + 32.dp + 32.dp + 20.dp + 72.dp + 64.dp + navBarPadding
        val availableForArtwork = maxHeight - reservedHeight
        val columnWidth = if (layout == MusicPlayerLayout.PORTRAIT_LARGE) min(maxWidth, PortraitLargeMaxWidth) else maxWidth
        val artworkMaxWidth = columnWidth - (PlayerHorizontalPadding * 2)
        val artworkSize = min(availableForArtwork, artworkMaxWidth).coerceAtLeast(160.dp)

        val queueState =
            rememberQueuePullUpState(
                enabled = isPlayerSheetExpanded && !isWide,
                hiddenY = constraints.maxHeight.toFloat() + navBarPx,
            )
        LaunchedEffect(isPlayerSheetExpanded) {
            if (!isPlayerSheetExpanded) showLyricsSheet = false
        }
        PlayerPanelRequestEffect(panelRequest, isPlayerSheetExpanded, onPanelRequestHandled) { panel ->
            val queue = panel == PlayerPanel.QUEUE
            val tab = if (queue) PlayerSidePaneTab.UP_NEXT else PlayerSidePaneTab.LYRICS
            if (isWide) {
                selectSidePaneTab(tab)
            } else if (queue) {
                queueState.open()
            } else if (inPlaceAvailable) {
                showLyricsInPlace()
            } else {
                openLyricsSheet()
            }
        }
        val queueCoversArtwork by remember(queueState) { derivedStateOf { queueState.fraction() > QUEUE_COVERS_ARTWORK } }
        val animatedArtwork =
            rememberAnimatedArtwork(
                track = uiState.currentTrack,
                // Immersive mode plays it as the background, so hiding the artwork box does not hide it there.
                allowed = videoPlayer == null && (immersiveBackground || !hideArtwork),
                running = isPlayerSheetExpanded && !showLyricsSheet && !queueCoversArtwork,
                tallSpace = immersiveBackground && layout != MusicPlayerLayout.WIDE && layout != MusicPlayerLayout.SPLIT,
                shown = artworkDragPreview == null && previewDirection == null,
            )

        val slots =
            NowPlayingSlots(
                topBar = { modifier ->
                    PlayerTopBar(
                        playingFrom = uiState.playingFrom,
                        modifier = modifier,
                        contentColor = colorScheme.onSurface,
                        modeSwitch =
                            if (videoSwitchEnabled) {
                                { PlayerModeSwitch(showsVideo = videoMode, onSelect = videoSwitch::select) }
                            } else {
                                null
                            },
                    )
                },
                artwork = { modifier ->
                    NowPlayingArtwork(
                        immersive = immersiveBackground,
                        isPlaying = uiState.isPlaying,
                        thumbnailUrl = thumbnailUrl,
                        previousTrack = previousTrack,
                        nextTrack = nextTrack,
                        previewDirection = previewDirection,
                        // Spinners in the warm, collapsed tree animate at alpha 0 otherwise.
                        isLoading = (uiState.isLoading || videoLoading) && isPlayerSheetExpanded,
                        hideArtwork = hideArtwork,
                        videoShowing = videoShowing,
                        videoPlayer = videoPlayer,
                        animatedArtwork = animatedArtwork,
                        viewModel = viewModel,
                        onDragPreviewChange = { artworkDragPreview = it },
                        onVideoShowingChange = { videoShowing = it },
                        modifier = modifier,
                    )
                },
                header = { modifier ->
                    NowPlayingHeader(
                        title = displayTitle,
                        artist = displayArtist,
                        track = previewTrack ?: uiState.currentTrack,
                        uiState = uiState,
                        animateTrackText = isPlayerSheetExpanded && uiState.isPlaying && !showLyricsSheet && !queueCoversArtwork,
                        controlScheme = controlScheme,
                        viewModel = viewModel,
                        sheets = sheets,
                        artistScope = artistScope,
                        modifier = modifier,
                    )
                },
                progress = { modifier ->
                    MaterialTheme(colorScheme = controlScheme) {
                        PlayerProgressSlider(
                            positionProvider = { positionState.value },
                            duration = uiState.duration,
                            onSeekTo = { viewModel.seekTo(it) },
                            // The tree stays composed while collapsed (warm for a jank-free expand) and under the
                            // lyrics sheet and the queue, so the squiggly/wavy per-frame wave animations must stop
                            // when nobody can see them; they otherwise burn a frame budget for the whole session.
                            isPlaying = uiState.isPlaying && isPlayerSheetExpanded && !showLyricsSheet && !queueCoversArtwork,
                            modifier = modifier,
                        )
                    }
                },
                controls = { modifier ->
                    MaterialTheme(colorScheme = controlScheme) {
                        MediaPlaybackControls(
                            isPlaying = uiState.isPlaying,
                            isBuffering = uiState.isBuffering && isPlayerSheetExpanded,
                            onPreviousClick = { viewModel.skipToPrevious() },
                            onPlayPauseToggle = { viewModel.togglePlayPause() },
                            onNextClick = { viewModel.skipToNext() },
                            modifier = modifier,
                            onPreviewDirectionChange = { previewDirection = it },
                            split = splitTransport,
                        )
                    }
                },
                actions = { modifier ->
                    MaterialTheme(colorScheme = controlScheme) {
                        // Wide windows show lyrics and the queue in the side pane, so the buttons pick its tab.
                        PlayerActionRow(
                            lyricsActive = if (isWide) sidePaneTab == PlayerSidePaneTab.LYRICS else showLyricsSheet || lyricsInPlaceShown,
                            shuffleEnabled = uiState.shuffleEnabled,
                            repeatMode = uiState.repeatMode,
                            onLyricsClick = {
                                when {
                                    isWide -> selectSidePaneTab(PlayerSidePaneTab.LYRICS)
                                    lyricsInPlaceShown -> lyricsInPlace = false
                                    inPlaceAvailable -> showLyricsInPlace()
                                    else -> openLyricsSheet()
                                }
                            },
                            onShuffleClick = { viewModel.toggleShuffle() },
                            onRepeatClick = { viewModel.toggleRepeat() },
                            onQueueClick = { if (isWide) selectSidePaneTab(PlayerSidePaneTab.UP_NEXT) else queueState.open() },
                            onMoreClick = { sheets.moreOptions = true },
                            modifier = modifier,
                            queueActive = isWide && sidePaneTab == PlayerSidePaneTab.UP_NEXT,
                            sleepTimerActive = SleepTimerManager.isActive,
                            onSleepTimerClick = if (sleepTimerButton) ({ sheets.sleepTimer = true }) else null,
                        )
                    }
                },
            )

        val lyricsDim =
            animateFloatAsState(
                targetValue = if (lyricsInPlaceShown) IN_PLACE_LYRICS_DIM else 0f,
                animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                label = "inPlaceLyricsDim",
            )
        PlayerBackground(
            thumbnailUrl = backgroundThumbnailUrl,
            style = backgroundStyle,
            backdrop = backdrop,
            artworkAtStart = layout == MusicPlayerLayout.WIDE || layout == MusicPlayerLayout.SPLIT,
            animatedArtwork = animatedArtwork.takeIf { immersiveBackground },
            preloadCovers =
                listOfNotNull(nextTrack?.highResThumbnailUrl, previousTrack?.highResThumbnailUrl)
                    .takeIf { immersiveBackground && isPlayerSheetExpanded }
                    .orEmpty(),
            trackId = track.videoId,
            motionRunning = isPlayerSheetExpanded && uiState.isPlaying && !showLyricsSheet && !queueCoversArtwork,
            dim = { lyricsDim.value },
        )

        val sideSwipes =
            rememberMusicSideSwipes(layout, isPlayerSheetExpanded) {
                queueState.fraction() > 0f || showLyricsSheet || lyricsInPlaceShown
            }
        val pullUpQueue = Modifier.musicSideSwipes(sideSwipes).queuePullUpGesture(queueState, enabled = isPlayerSheetExpanded && !isWide)
        val paneLyricsBackdrop = remember(palette.base) { lyricsBackdrop(palette.base) }
        when (layout) {
            MusicPlayerLayout.COMPACT -> {
                val inPlaceLyrics: @Composable (Modifier) -> Unit = { modifier ->
                    NowPlayingLyricsInPlace(
                        uiState = uiState,
                        viewModel = viewModel,
                        accentColor = colorScheme.primary,
                        backdropColor = paneLyricsBackdrop,
                        contentColor = colorScheme.onSurface,
                        positionState = positionState,
                        active = inPlaceLyricsActive(isPlayerSheetExpanded, lyricsInPlaceShown, showLyricsSheet, queueCoversArtwork),
                        onOpenFullScreen = openLyricsSheet,
                        modifier = modifier,
                    )
                }
                CompactPlayerLayout(
                    slots = slots,
                    artworkSize = artworkSize,
                    queueFraction = queueState::fraction,
                    bottomInset = navBarPadding,
                    modifier = pullUpQueue,
                    lyrics = inPlaceLyrics.takeIf { lyricsInPlaceShown },
                )
            }

            MusicPlayerLayout.PORTRAIT_LARGE -> {
                CompactPlayerLayout(
                    slots = slots,
                    artworkSize = artworkSize,
                    queueFraction = queueState::fraction,
                    bottomInset = navBarPadding,
                    modifier = Modifier.align(Alignment.TopCenter).widthIn(max = PortraitLargeMaxWidth).then(pullUpQueue),
                )
            }

            MusicPlayerLayout.SPLIT -> {
                SplitPlayerLayout(slots, queueState::fraction, pullUpQueue)
            }

            MusicPlayerLayout.WIDE -> {
                WidePlayerLayout(slots, panes, immersive = immersiveBackground) { modifier ->
                    NowPlayingSidePane(
                        tab = sidePaneTab,
                        onTabChange = selectSidePaneTab,
                        onOpenFullLyrics = openLyricsSheet,
                        lyricsBackdrop = paneLyricsBackdrop,
                        uiState = uiState,
                        viewModel = viewModel,
                        positionState = positionState,
                        isPlayerSheetExpanded = isPlayerSheetExpanded,
                        showLyricsSheet = showLyricsSheet,
                        queueSwipes = queueSwipes,
                        queueActions = queueActions,
                        modifier = modifier,
                    )
                }
            }
        }

        MusicSideSwipeHud(sideSwipes)
        val queueSheetModifier =
            if (layout == MusicPlayerLayout.PORTRAIT_LARGE) {
                Modifier.align(Alignment.TopCenter).widthIn(max = PortraitLargeQueueMaxWidth)
            } else {
                Modifier
            }
        NowPlayingQueueSheet(queueState, uiState, queueSwipes, queueActions, queueSheetModifier)

        val largeLyrics = layout == MusicPlayerLayout.WIDE || layout == MusicPlayerLayout.PORTRAIT_LARGE
        NowPlayingLyricsSheet(
            uiState = uiState,
            viewModel = viewModel,
            visible = showLyricsSheet,
            retainContent = isPlayerSheetExpanded,
            backdropBaseColor = palette.base,
            accentColor = colorScheme.primary,
            fallbackTitle = track.title,
            fallbackArtist = track.artist,
            artworkUrl = thumbnailUrl,
            positionState = positionState,
            baseTextSize = if (largeLyrics) LARGE_LYRICS_TEXT_SIZE else DEFAULT_LYRICS_TEXT_SIZE,
            onDismiss = { showLyricsSheet = false },
        )

        // Hosted here rather than at app level so it picks up the palette-derived scheme.
        if (sheets.sleepTimer) {
            MediaSleepTimerSheet(onDismiss = { sheets.sleepTimer = false })
        }
    }
}

// Past this the queue sheet hides the artwork, so nothing behind it needs to move.
private const val QUEUE_COVERS_ARTWORK = 0.95f

/** Upright tablets keep the phone column, centred at this width. */
private val PortraitLargeMaxWidth = 600.dp
private val PortraitLargeQueueMaxWidth = 640.dp
