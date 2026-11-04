package io.github.aedev.flow.data.repository

import android.util.Log
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.VideoPlaylistPage
import io.github.aedev.flow.innertube.pages.isMix
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "YouTubePlaylistRepo"
private const val MAX_PAGES = 60
private const val FULL_LIST_TTL_MS = 10 * 60 * 1000L

/** A YouTube playlist as the page shows it: the first page now, the rest through [YouTubePlaylistRepository.nextPage]. */
data class RemotePlaylistPage(
    val title: String,
    val ownerName: String?,
    val ownerId: String?,
    val description: String,
    val thumbnailUrl: String,
    val videos: List<Video>,
    val continuation: String?,
    val isCourse: Boolean = false,
    /** The header's count, unavailable videos included. */
    val videoCount: Int? = null,
) {
    /** The whole list is shorter than the header's count: YouTube leaves out unavailable videos. */
    fun hidesUnavailable(loaded: Int = videos.size): Boolean = videoCount != null && videoCount > loaded
}

/**
 * YouTube playlists from InnerTube, one request per page. The first page shows at once and later
 * pages load on demand; a complete list is kept for [FULL_LIST_TTL_MS] so reopening a playlist or
 * syncing a saved one does not walk it again.
 */
@Singleton
class YouTubePlaylistRepository
    @Inject
    constructor() {
        private data class CachedList(
            val page: RemotePlaylistPage,
            val loadedAtMs: Long,
        )

        private val completeLists = ConcurrentHashMap<String, CachedList>()

        /** The whole playlist if it was loaded recently, so the page can show it without a request. */
        fun cachedComplete(playlistId: String): RemotePlaylistPage? =
            completeLists[playlistId]
                ?.takeIf { System.currentTimeMillis() - it.loadedAtMs < FULL_LIST_TTL_MS }
                ?.page

        fun rememberComplete(
            playlistId: String,
            page: RemotePlaylistPage,
        ) {
            completeLists[playlistId] = CachedList(page.copy(continuation = null), System.currentTimeMillis())
        }

        suspend fun firstPage(playlistId: String): RemotePlaylistPage? {
            val page =
                fetch { if (isMix(playlistId)) YouTube.mixPlaylistPage(playlistId) else YouTube.videoPlaylistPage(playlistId) }
                    ?.takeIf { it.title != null || it.videos.isNotEmpty() } ?: return null
            // A Shorts grid stops at 100 with no next page; its "show unavailable" view is a list
            // that pages through every video. Only grids ask for it: on a list it would add
            // unplayable entries that look playable.
            val list =
                if (page.isShortsGrid) {
                    fetch { YouTube.videoPlaylistPage(playlistId, params = YouTube.PLAYLIST_SHOW_UNAVAILABLE_PARAMS) }
                        ?.takeIf { it.videos.isNotEmpty() }
                } else {
                    null
                } ?: page
            return RemotePlaylistPage(
                title = page.title.orEmpty(),
                ownerName = page.ownerName,
                ownerId = page.ownerId?.takeIf(String::isNotBlank),
                description = page.description.orEmpty(),
                thumbnailUrl =
                    page.thumbnailUrl ?: list.videos
                        .firstOrNull()
                        ?.thumbnailUrl
                        .orEmpty(),
                videos = list.videos,
                continuation = list.continuation,
                isCourse = page.isCourse,
                videoCount = page.videoCount ?: list.videoCount,
            )
        }

        /** The videos behind [continuation] and the token after them. */
        suspend fun nextPage(
            playlistId: String,
            continuation: String,
        ): Pair<List<Video>, String?>? = fetch { YouTube.videoPlaylistPage(playlistId, continuation) }?.let { it.videos to it.continuation }

        /**
         * Every video, page by page, for a caller that needs the whole list (syncing a saved copy).
         * Null unless every page arrived: a partial list would cut the saved copy short.
         */
        suspend fun complete(playlistId: String): RemotePlaylistPage? {
            cachedComplete(playlistId)?.let { return it }
            val first = firstPage(playlistId) ?: return null
            val walk = walkPlaylistPages(first.videos, first.continuation, MAX_PAGES, { nextPage(playlistId, it) })
            if (!walk.finished) return null
            val complete = first.copy(videos = walk.videos, continuation = null)
            rememberComplete(playlistId, complete)
            return complete
        }

        private suspend fun fetch(block: suspend () -> Result<VideoPlaylistPage>): VideoPlaylistPage? =
            withContext(PerformanceDispatcher.networkIO) {
                block()
                    .onFailure { if (it is CancellationException) throw it }
                    .onFailure { Log.w(TAG, "Playlist request failed: ${it.message}") }
                    .getOrNull()
            }

        companion object {
            /** Upper bound on pages walked for one playlist, about 6,000 videos. */
            const val PAGE_LIMIT = MAX_PAGES
        }
    }
