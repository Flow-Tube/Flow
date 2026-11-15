package io.github.aedev.flow.ui.screens.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.aedev.flow.data.contentfilter.aislist.AiVerdict
import io.github.aedev.flow.data.music.model.MusicTrack

/** The artist page for [channelId] through its loading and error states, as every shell opens it. */
@Composable
internal fun ArtistRoute(
    channelId: String,
    onBackClick: () -> Unit,
    onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onSeeAllClick: (browseId: String, params: String?) -> Unit,
    onPlayWatchPlaylist: suspend (playlistId: String, params: String?) -> Boolean,
    onStartRadio: (MusicTrack) -> Unit,
    musicViewModel: MusicViewModel = sharedMusicViewModel(),
) {
    val uiState by musicViewModel.uiState.collectAsState()
    val aiMatcher by musicViewModel.aiMatcher.collectAsState()

    LaunchedEffect(channelId) {
        musicViewModel.fetchArtistDetails(channelId)
    }

    if (uiState.isArtistLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (uiState.artistLoadFailed) {
        ArtistPageError(
            onBackClick = onBackClick,
            onRetry = { musicViewModel.fetchArtistDetails(channelId) },
        )
    } else {
        uiState.artistDetails?.let { details ->
            val aiNotice =
                remember(aiMatcher, details.channelId) {
                    val verdict = aiMatcher.artistVerdict(details.channelId)
                    when {
                        verdict == AiVerdict.NONE -> null
                        aiMatcher.isOnMusicList(details.channelId) -> ArtistAiNotice.MUSIC_LIST
                        verdict == AiVerdict.BLOCK -> ArtistAiNotice.CHANNEL_BLOCK_LIST
                        else -> ArtistAiNotice.CHANNEL_WARN_LIST
                    }
                }
            ArtistPage(
                artistDetails = details,
                aiNotice = aiNotice,
                onShowAiArtist = { musicViewModel.showAiArtist(details) },
                downloadedTrackIds = uiState.downloadedTrackIds,
                insights = uiState.artistInsights,
                knownRelatedArtistIds = uiState.knownRelatedArtistIds,
                onBackClick = onBackClick,
                onTrackClick = onTrackClick,
                onAlbumClick = { album -> onAlbumClick(album.id) },
                onArtistClick = onArtistClick,
                onFollowClick = { musicViewModel.toggleFollowArtist(details) },
                onSeeAllClick = onSeeAllClick,
                onPlayWatchPlaylist = onPlayWatchPlaylist,
                onStartRadio = onStartRadio,
            )
        }
    }
}
