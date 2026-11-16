package io.github.aedev.flow.ui.components.musicplayer.full

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.media3.common.Player
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.components.layout.navigation.LocalMediaNavigator
import io.github.aedev.flow.ui.components.musicplayer.queue.QueueActions
import io.github.aedev.flow.ui.components.musicplayer.queue.QueueList
import io.github.aedev.flow.ui.components.musicplayer.queue.QueuePullUpSheet
import io.github.aedev.flow.ui.components.musicplayer.queue.QueuePullUpState
import io.github.aedev.flow.ui.components.musicplayer.queue.QueueSheet
import io.github.aedev.flow.ui.components.musicplayer.queue.QueueSwipes
import io.github.aedev.flow.ui.components.shared.SkipDirection
import io.github.aedev.flow.ui.screens.music.MusicPlayerUiState
import io.github.aedev.flow.ui.screens.music.MusicPlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The artwork slot: the cover (or the music video under it), with the swipe-to-skip carousel. */
@Composable
internal fun NowPlayingArtwork(
    immersive: Boolean,
    isPlaying: Boolean,
    thumbnailUrl: String?,
    previousTrack: MusicTrack?,
    nextTrack: MusicTrack?,
    previewDirection: SkipDirection?,
    isLoading: Boolean,
    hideArtwork: Boolean,
    videoShowing: Boolean,
    videoPlayer: Player?,
    animatedArtwork: (@Composable () -> Unit)?,
    viewModel: MusicPlayerViewModel,
    onDragPreviewChange: (SkipDirection?) -> Unit,
    onVideoShowingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerArtworkFrame(
        immersive = immersive,
        isPlaying = isPlaying,
        modifier = modifier,
    ) {
        PlayerArtwork(
            thumbnailUrl = thumbnailUrl,
            previousThumbnailUrl = previousTrack?.highResThumbnailUrl,
            nextThumbnailUrl = nextTrack?.highResThumbnailUrl,
            previewDirection = previewDirection,
            isLoading = isLoading,
            hideArtwork = hideArtwork || immersive || videoShowing,
            hiddenArtworkColor =
                if (immersive || videoShowing) Color.Unspecified else MaterialTheme.colorScheme.surfaceContainerHigh,
            onSkipPrevious = { viewModel.skipToPrevious() },
            onSkipNext = { viewModel.skipToNext() },
            modifier = Modifier.fillMaxSize(),
            onDragPreviewChange = onDragPreviewChange,
            underlay =
                videoPlayer?.let { player ->
                    {
                        PlayerVideo(
                            player = player,
                            cornerRadius = PlayerArtworkCornerRadius,
                            onShowingChange = onVideoShowingChange,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                },
            overlay = animatedArtwork.takeUnless { immersive },
        )
    }
}

/** Title and artist with like, download and save; a tap on the artist opens them, or a picker for several. */
@Composable
internal fun NowPlayingHeader(
    title: String,
    artist: String,
    track: MusicTrack?,
    uiState: MusicPlayerUiState,
    animateTrackText: Boolean,
    controlScheme: ColorScheme,
    viewModel: MusicPlayerViewModel,
    sheets: FullPlayerSheetState,
    artistScope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalMediaNavigator.current
    MaterialTheme(colorScheme = controlScheme) {
        PlayerTrackHeader(
            title = title,
            artist = artist,
            onArtistClick = {
                if (track != null) {
                    artistScope.launch {
                        val artists = viewModel.resolveArtists(track)
                        when {
                            artists.size > 1 -> sheets.artists = artists
                            artists.size == 1 -> navigator.openArtist(artists.first().id.orEmpty())
                        }
                    }
                }
            },
            animateTrackText = animateTrackText,
            showLibraryActions = !LocalMediaIds.isLocal(uiState.currentTrack?.videoId),
            isLiked = uiState.isLiked,
            isDownloaded = uiState.downloadedTrackIds.contains(uiState.currentTrack?.videoId),
            onLikeClick = { viewModel.toggleLike() },
            onDownloadClick = { viewModel.downloadTrack() },
            onAddToPlaylist = { sheets.save = true },
            modifier = modifier,
        )
    }
}

/** The wide layout's side pane: Up next or the lyrics, which stop while the player is closed or the sheet covers them. */
@Composable
internal fun NowPlayingSidePane(
    tab: PlayerSidePaneTab,
    onTabChange: (PlayerSidePaneTab) -> Unit,
    onOpenFullLyrics: () -> Unit,
    lyricsBackdrop: Color,
    uiState: MusicPlayerUiState,
    viewModel: MusicPlayerViewModel,
    positionState: State<Long>,
    isPlayerSheetExpanded: Boolean,
    showLyricsSheet: Boolean,
    queueSwipes: QueueSwipes,
    queueActions: QueueActions,
    modifier: Modifier = Modifier,
) {
    PlayerSidePane(
        tab = tab,
        onTabChange = onTabChange,
        onOpenFullLyrics = onOpenFullLyrics,
        lyricsBackdrop = lyricsBackdrop,
        upNext = {
            QueueList(
                queue = uiState.queue,
                radioTracks = uiState.autoplaySuggestions,
                currentIndex = uiState.currentQueueIndex,
                // The warm tree stays composed while collapsed; the row waveform must not animate there.
                isPlaying = uiState.isPlaying && isPlayerSheetExpanded,
                isRadioLoading = uiState.isRadioLoading,
                endlessRadioEnabled = uiState.endlessRadioEnabled,
                downloadedTrackIds = uiState.downloadedTrackIds,
                swipes = queueSwipes,
                actions = queueActions,
                clearNavigationBar = false,
                selectEntry = true,
            )
        },
        lyrics = {
            NowPlayingLyricsPane(
                uiState = uiState,
                viewModel = viewModel,
                accentColor = MaterialTheme.colorScheme.primary,
                backdropColor = lyricsBackdrop,
                positionState = positionState,
                active = isPlayerSheetExpanded && !showLyricsSheet,
            )
        },
        modifier = modifier,
    )
}

/** The queue pulled up over the phone layouts. */
@Composable
internal fun NowPlayingQueueSheet(
    state: QueuePullUpState,
    uiState: MusicPlayerUiState,
    queueSwipes: QueueSwipes,
    queueActions: QueueActions,
    modifier: Modifier = Modifier,
) {
    QueuePullUpSheet(state, modifier) { cornerRadius, dragHandleModifier ->
        QueueSheet(
            sheetCornerRadius = cornerRadius,
            queue = uiState.queue,
            radioTracks = uiState.autoplaySuggestions,
            currentIndex = uiState.currentQueueIndex,
            isPlaying = uiState.isPlaying,
            isRadioLoading = uiState.isRadioLoading,
            endlessRadioEnabled = uiState.endlessRadioEnabled,
            shuffleEnabled = uiState.shuffleEnabled,
            repeatMode = uiState.repeatMode,
            downloadedTrackIds = uiState.downloadedTrackIds,
            swipes = queueSwipes,
            actions = queueActions,
            dragHandleModifier = dragHandleModifier,
        )
    }
}
