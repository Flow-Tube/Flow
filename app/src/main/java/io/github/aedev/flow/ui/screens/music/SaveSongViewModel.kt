package io.github.aedev.flow.ui.screens.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

private const val SHARING_TIMEOUT_MS = 5_000L

/** A song as the playlist tables store it: music playlists hold [Video] rows flagged as music. */
internal fun MusicTrack.toPlaylistVideo(addedAt: Long): Video =
    Video(
        id = videoId,
        title = title,
        channelName = artist,
        channelId = channelId,
        thumbnailUrl = thumbnailUrl,
        duration = duration,
        viewCount = 0,
        uploadDate = "",
        timestamp = addedAt,
        description = album,
        isMusic = true,
    )

/** Which of your music playlists hold one song, and adding it to or removing it from them. */
@HiltViewModel
class SaveSongViewModel
    @Inject
    constructor(
        private val repository: PlaylistRepository,
    ) : ViewModel() {
        val playlists: StateFlow<List<PlaylistInfo>> =
            repository
                .getMusicPlaylistsFlow()
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARING_TIMEOUT_MS), emptyList())

        private val _savedIds = MutableStateFlow<Set<String>>(emptySet())
        val savedIds: StateFlow<Set<String>> = _savedIds.asStateFlow()

        private val membershipLoaded = MutableStateFlow(false)

        fun loadMembership(videoId: String) {
            membershipLoaded.value = false
            _savedIds.value = emptySet()
            viewModelScope.launch {
                _savedIds.value = repository.getPlaylistIdsForVideo(videoId).toSet()
                membershipLoaded.value = true
            }
        }

        fun toggle(
            track: MusicTrack,
            playlistId: String,
        ) {
            if (!membershipLoaded.value) return
            val wasSaved = playlistId in _savedIds.value
            _savedIds.update { if (wasSaved) it - playlistId else it + playlistId }
            viewModelScope.launch {
                runCatching {
                    if (wasSaved) {
                        repository.removeVideoFromPlaylist(playlistId, track.videoId)
                    } else {
                        repository.addVideoToPlaylist(playlistId, track.toPlaylistVideo(System.currentTimeMillis()))
                    }
                }.onFailure {
                    _savedIds.update { ids -> if (wasSaved) ids + playlistId else ids - playlistId }
                }
            }
        }

        /** Adds [tracks] to the playlist in their order; songs already in it stay where they are. */
        fun addAll(
            tracks: List<MusicTrack>,
            playlistId: String,
        ) {
            viewModelScope.launch {
                val now = System.currentTimeMillis()
                repository.addVideosToPlaylist(playlistId, tracks.mapIndexed { i, track -> track.toPlaylistVideo(now + i) })
                _savedIds.update { it + playlistId }
            }
        }

        fun createAndAddAll(
            tracks: List<MusicTrack>,
            name: String,
            description: String,
        ) {
            viewModelScope.launch {
                val playlistId = UUID.randomUUID().toString()
                repository.createPlaylist(playlistId, name, description, isPrivate = false, isMusic = true)
                val now = System.currentTimeMillis()
                repository.addVideosToPlaylist(playlistId, tracks.mapIndexed { i, track -> track.toPlaylistVideo(now + i) })
                _savedIds.update { it + playlistId }
            }
        }

        fun createAndAdd(
            track: MusicTrack,
            name: String,
            description: String,
        ) {
            viewModelScope.launch {
                val playlistId = UUID.randomUUID().toString()
                repository.createPlaylist(playlistId, name, description, isPrivate = false, isMusic = true)
                repository.addVideoToPlaylist(playlistId, track.toPlaylistVideo(System.currentTimeMillis()))
                _savedIds.update { it + playlistId }
            }
        }
    }
