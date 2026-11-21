package io.github.aedev.flow.ui.components.music.section

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.ArtistDetails
import io.github.aedev.flow.data.music.model.MusicPlaylist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.components.music.header.MusicSectionAction

/** The viewer's own playlists and music subscriptions, and the sections they chose to hide. */
@Immutable
class MusicHomeLibrary(
    val playlists: List<MusicPlaylist> = emptyList(),
    val subscriptions: List<ArtistDetails> = emptyList(),
    val hidden: Set<MusicHomeShelf> = emptySet(),
    val onPlaylistClick: (String) -> Unit = {},
    val onAllPlaylistsClick: () -> Unit = {},
    val onAllSubscriptionsClick: () -> Unit = {},
    val discoveryAvailable: Boolean = false,
    val discovery: List<MusicTrack> = emptyList(),
    val onDiscoveryShown: () -> Unit = {},
)

internal fun LazyListScope.yourLibrary(
    library: MusicHomeLibrary,
    onArtistClick: (String) -> Unit,
) {
    if (MusicHomeShelf.YOUR_PLAYLISTS !in library.hidden && library.playlists.isNotEmpty()) {
        item(key = "your_playlists", contentType = MusicShelfContentType.Collections) {
            MusicCollectionShelf(
                title = stringResource(R.string.music_home_your_playlists),
                collections = library.playlists,
                keyNamespace = "your_playlists",
                onCollectionClick = { library.onPlaylistClick(it.id) },
                onCollectionMenu = { library.onPlaylistClick(it.id) },
                action = MusicSectionAction.Navigate(library.onAllPlaylistsClick),
                collectionSubtitle = { stringResource(R.string.tracks_count_template, it.trackCount) },
            )
        }
    }
    if (MusicHomeShelf.YOUR_SUBSCRIPTIONS !in library.hidden && library.subscriptions.isNotEmpty()) {
        item(key = "your_subscriptions", contentType = MusicShelfContentType.Artists) {
            MusicArtistShelf(
                title = stringResource(R.string.music_home_your_subscriptions),
                artists = library.subscriptions,
                key = { "your_subscriptions:${it.channelId}" },
                name = { it.name },
                thumbnailUrl = { it.thumbnailUrl },
                onArtistClick = { onArtistClick(it.channelId) },
                action = MusicSectionAction.Navigate(library.onAllSubscriptionsClick),
            )
        }
    }
}

/** Asks for its songs only once it is scrolled to, and draws nothing until they arrive. */
internal fun LazyListScope.lastFmDiscovery(
    library: MusicHomeLibrary,
    downloaded: Set<String>,
    onSongClick: (MusicTrack, List<MusicTrack>, String?) -> Unit,
    onTrackMenu: (MusicTrack) -> Unit,
) {
    if (!library.discoveryAvailable || MusicHomeShelf.LASTFM_DISCOVER in library.hidden) return
    item(key = "lastfm_discover", contentType = MusicShelfContentType.Discovery) {
        LaunchedEffect(Unit) { library.onDiscoveryShown() }
        val tracks = library.discovery
        if (tracks.isNotEmpty()) {
            val title = stringResource(R.string.music_home_lastfm_discover)
            MusicTrackCardShelf(
                title = title,
                subtitle = stringResource(R.string.music_home_lastfm_discover_subtitle),
                tracks = tracks,
                keyNamespace = "lastfm_discover",
                downloadedTrackIds = downloaded,
                onTrackClick = { onSongClick(it, tracks, title) },
                onTrackMenu = onTrackMenu,
            )
        }
    }
}
