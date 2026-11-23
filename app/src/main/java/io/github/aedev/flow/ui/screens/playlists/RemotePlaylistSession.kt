package io.github.aedev.flow.ui.screens.playlists

import io.github.aedev.flow.data.repository.RemotePlaylistPage
import io.github.aedev.flow.data.repository.YouTubePlaylistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The YouTube playlist a page shows: its first page at once, a page more whenever the list nears its
 * end, and every page only when [loadAll] is asked for (a sort, a search, a save or a download).
 */
internal class RemotePlaylistSession(
    private val scope: CoroutineScope,
    private val playlistId: String,
    private val playlists: YouTubePlaylistRepository,
    private val uiState: MutableStateFlow<PlaylistDetailUiState>,
) {
    private var first: RemotePlaylistPage? = null
    private var pager: RemotePlaylistPager? = null
    private var moreJob: Job? = null
    private var allJob: Job? = null

    /** Shows [page], the first page or a whole cached list, and makes the rest loadable. */
    fun start(page: RemotePlaylistPage) {
        first = page
        pager =
            RemotePlaylistPager(page.videos, page.continuation, YouTubePlaylistRepository.PAGE_LIMIT) { playlists.nextPage(playlistId, it) }
        uiState.update {
            it.copy(
                playlistName = page.title,
                ownerName = page.ownerName,
                isCourse = page.isCourse,
                description = page.description,
                isPrivate = false,
                videos = page.videos,
                videoCount = page.videoCount,
                thumbnailUrl = page.thumbnailUrl,
                isLocalPlaylist = false,
                isSaved = false,
                isWatchLater = false,
                isLoading = false,
                hasMorePages = page.continuation != null,
                hidesUnavailable = page.continuation == null && page.hidesUnavailable(),
                errorMessage = null,
            )
        }
    }

    /** The page token after the first [count] videos, for a queue that holds them. */
    fun tokenAfter(count: Int): String? = pager?.tokenAfter(count)

    fun loadMore() {
        val pager = pager?.takeIf { it.hasMore } ?: return
        if (moreJob?.isActive == true || allJob?.isActive == true) return
        moreJob =
            scope.launch {
                uiState.update { it.copy(isLoadingMore = true) }
                pager.loadNext()
                publish(pager)
            }
    }

    /** Loads every page left, once; null when there is nothing left to load. */
    fun loadAll(): Job? {
        val pager = pager?.takeIf { it.hasMore } ?: return null
        allJob?.takeIf { it.isActive }?.let { return it }
        return scope
            .launch {
                moreJob?.join()
                uiState.update { it.copy(isLoadingAll = true) }
                pager.loadAll { videos -> uiState.update { it.copy(videos = videos) } }
                publish(pager)
            }.also { allJob = it }
    }

    /** Waits for every page; true when the list on show is the whole playlist. */
    suspend fun awaitAll(): Boolean {
        loadAll()?.join()
        return pager?.isComplete ?: true
    }

    private fun publish(pager: RemotePlaylistPager) {
        val complete = pager.isComplete
        uiState.update {
            it.copy(
                videos = pager.videos,
                isLoadingMore = false,
                isLoadingAll = false,
                hasMorePages = pager.hasMore,
                hidesUnavailable = complete && first?.hidesUnavailable(pager.videos.size) == true,
            )
        }
        val page = first ?: return
        if (complete && page.continuation != null) playlists.rememberComplete(playlistId, page.copy(videos = pager.videos))
    }
}
