package io.github.aedev.flow.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.video.DownloadedVideo
import io.github.aedev.flow.data.video.VideoDownloadManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The downloaded videos Home offers when it cannot load. Read only while that state is on
 * screen, from the downloads table the Downloads page reads; it fetches nothing.
 */
@HiltViewModel
internal class HomeOfflineViewModel
    @Inject
    constructor(
        downloads: VideoDownloadManager,
    ) : ViewModel() {
        val downloads: StateFlow<List<DownloadedVideo>> =
            downloads.downloadedVideos
                .map { list -> list.sortedByDescending { it.downloadedAt }.take(MAX_SHOWN) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        private companion object {
            const val MAX_SHOWN = 6
        }
    }
