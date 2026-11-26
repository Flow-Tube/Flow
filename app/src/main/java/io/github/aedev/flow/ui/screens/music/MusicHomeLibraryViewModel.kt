package io.github.aedev.flow.ui.screens.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.music.model.ArtistDetails
import io.github.aedev.flow.data.music.model.MusicPlaylist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.playlist.sortedFor
import io.github.aedev.flow.data.scrobble.LastFmDiscovery
import io.github.aedev.flow.data.scrobble.ScrobbleService
import io.github.aedev.flow.data.scrobble.ScrobbleStore
import io.github.aedev.flow.ui.components.music.section.MusicHomeShelf
import io.github.aedev.flow.ui.components.music.section.likedMusicCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val SHELF_LIMIT = 20

/**
 * The viewer's own corner of the music home: their liked songs and music playlists in the playlists page's order,
 * the music channels they subscribe to, newest first, the sections they chose to hide, and, for
 * Last.fm accounts, the discovery shelf. Only that shelf touches the network, and only once shown.
 */
@HiltViewModel
class MusicHomeLibraryViewModel
    @Inject
    constructor(
        playlistRepository: PlaylistRepository,
        likedVideosRepository: LikedVideosRepository,
        subscriptionRepository: SubscriptionRepository,
        preferences: PlayerPreferences,
        scrobbleStore: ScrobbleStore,
        private val lastFmDiscovery: LastFmDiscovery,
    ) : ViewModel() {
        private val sharing = SharingStarted.WhileSubscribed(5_000)

        val playlists: StateFlow<List<MusicPlaylist>> =
            combine(playlistRepository.getMusicPlaylistsFlow(), preferences.playlistListOrder) { playlists, order ->
                playlists.sortedFor(order).take(SHELF_LIMIT).map { playlist ->
                    MusicPlaylist(
                        id = playlist.id,
                        title = playlist.name,
                        thumbnailUrl = playlist.thumbnailUrl,
                        trackCount = playlist.videoCount,
                    )
                }
            }.stateIn(viewModelScope, sharing, emptyList())

        val likedMusic: StateFlow<MusicPlaylist?> =
            likedVideosRepository
                .getLikedMusicFlow()
                .map(::likedMusicCard)
                .distinctUntilChanged()
                .stateIn(viewModelScope, sharing, null)

        val subscriptions: StateFlow<List<ArtistDetails>> =
            subscriptionRepository
                .getAllSubscriptions()
                .map { subscriptions ->
                    subscriptions
                        .filter { it.isMusic }
                        .sortedByDescending { it.subscribedAt }
                        .map {
                            ArtistDetails(
                                name = it.channelName,
                                channelId = it.channelId,
                                thumbnailUrl = it.channelThumbnail,
                                subscriberCount = 0L,
                            )
                        }
                }.stateIn(viewModelScope, sharing, emptyList())

        val lastFmSignedIn: StateFlow<Boolean> =
            scrobbleStore.settings
                .map { ScrobbleService.LASTFM in it.accounts }
                .distinctUntilChanged()
                .stateIn(viewModelScope, sharing, false)

        private val _discovery = MutableStateFlow<List<MusicTrack>>(emptyList())
        val discovery: StateFlow<List<MusicTrack>> = _discovery.asStateFlow()
        private var discoveryJob: Job? = null

        /** Called when the shelf comes into view; the discovery service keeps its result while the seeds hold. */
        fun loadDiscovery() {
            if (discoveryJob?.isActive == true) return
            discoveryJob =
                viewModelScope.launch { _discovery.value = runCatching { lastFmDiscovery.tracks() }.getOrDefault(_discovery.value) }
        }

        val hiddenShelves: StateFlow<Set<MusicHomeShelf>> =
            preferences.hiddenMusicHomeShelves
                .map(MusicHomeShelf::fromStored)
                .stateIn(viewModelScope, sharing, emptySet())
    }
