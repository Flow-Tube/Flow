package io.github.aedev.flow.ui.screens.playlists

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.playlist.PlaylistImport
import io.github.aedev.flow.data.playlist.PlaylistListOrder
import io.github.aedev.flow.data.playlist.PlaylistTransfer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel
    @Inject
    constructor(
        private val repository: PlaylistRepository,
        private val transfer: PlaylistTransfer,
        private val preferences: PlayerPreferences,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(PlaylistsUiState(isLoading = true))
        val uiState: StateFlow<PlaylistsUiState> = _uiState.asStateFlow()

        private val sharing = SharingStarted.WhileSubscribed(5_000)

        val listOrder: StateFlow<PlaylistListOrder> =
            preferences.playlistListOrder.stateIn(viewModelScope, sharing, PlaylistListOrder.NEWEST)

        val compactLayout: StateFlow<Boolean> = preferences.playlistsCompactLayout.stateIn(viewModelScope, sharing, false)

        /** Null until read, so the page never flashes Videos before switching to a remembered Music. */
        val showMusic: StateFlow<Boolean?> = preferences.playlistsShowMusic.stateIn(viewModelScope, sharing, null)

        init {
            observePlaylists()
        }

        private fun observePlaylists() {
            viewModelScope.launch {
                launch {
                    repository.getUserCreatedVideoPlaylistsFlow().collect { playlists ->
                        _uiState.update { it.copy(isLoading = false, playlists = playlists) }
                    }
                }
                launch {
                    repository.getSavedVideoPlaylistsFlow().collect { savedPlaylists ->
                        _uiState.update { it.copy(savedPlaylists = savedPlaylists) }
                    }
                }
            }
        }

        fun createPlaylist(
            name: String,
            description: String,
        ) {
            viewModelScope.launch {
                repository.createPlaylist(
                    playlistId = System.currentTimeMillis().toString(),
                    name = name,
                    description = description,
                    isPrivate = true,
                )
            }
        }

        /** Imports the playlist files or zips at [sources]; the result says what to tell the viewer and where to go. */
        suspend fun importPlaylists(
            sources: List<Uri>,
            fallbackName: String,
        ): PlaylistImport = transfer.importAll(sources, fallbackName)

        /** Saves every playlist into [target] as one zip; null when it could not be written, else how many it holds. */
        suspend fun exportAll(target: Uri): Int? = transfer.writeLibraryTo(target)

        fun setListOrder(order: PlaylistListOrder) {
            viewModelScope.launch { preferences.setPlaylistListOrder(order) }
        }

        fun setCompactLayout(compact: Boolean) {
            viewModelScope.launch { preferences.setPlaylistsCompactLayout(compact) }
        }

        fun setShowMusic(showMusic: Boolean) {
            viewModelScope.launch { preferences.setPlaylistsShowMusic(showMusic) }
        }

        fun reorderPlaylists(orderedIds: List<String>) {
            viewModelScope.launch { repository.reorderPlaylists(orderedIds) }
        }

        fun deletePlaylist(playlistId: String) {
            viewModelScope.launch {
                repository.deletePlaylist(playlistId)
            }
        }
    }

data class PlaylistsUiState(
    val isLoading: Boolean = false,
    val playlists: List<PlaylistInfo> = emptyList(),
    val savedPlaylists: List<PlaylistInfo> = emptyList(),
)
