package io.github.aedev.flow.ui.screens.playlists

import io.github.aedev.flow.data.model.Video
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Start loading the next page once the list's last shown row is this close to its end. */
internal const val PLAYLIST_LOAD_AHEAD_ROWS = 30

/** Whether a list showing up to row [lastVisible] of [totalItems] should load its next page. */
internal fun nearListEnd(
    lastVisible: Int,
    totalItems: Int,
    aheadRows: Int = PLAYLIST_LOAD_AHEAD_ROWS,
): Boolean = totalItems > 0 && lastVisible >= totalItems - 1 - aheadRows

/**
 * A YouTube playlist read a page at a time as the list is scrolled, and to its end only when a
 * feature needs every video. Only a token YouTube sends ends the list; a failed page leaves it
 * where it was, so the next request tries the same page again.
 */
internal class RemotePlaylistPager(
    firstVideos: List<Video>,
    firstToken: String?,
    private val maxPages: Int,
    private val loadPage: suspend (token: String) -> Pair<List<Video>, String?>?,
) {
    private val mutex = Mutex()
    private val seenTokens = HashSet<String>()
    private val tokenAfter = HashMap<Int, String>()
    private var pages = 1
    private var truncated = false

    var videos: List<Video> = firstVideos.distinctBy { it.id }
        private set

    private var nextToken: String? = firstToken

    init {
        firstToken?.let { tokenAfter[videos.size] = it }
    }

    val hasMore: Boolean get() = nextToken != null

    /** Every page arrived: the list is the whole playlist. */
    val isComplete: Boolean get() = nextToken == null && !truncated

    /** The token for the videos after the first [count], so a queue holding them can go on from there. */
    fun tokenAfter(count: Int): String? = tokenAfter[count]

    /** Loads the next page; false when there is none or it failed. */
    suspend fun loadNext(): Boolean = mutex.withLock { loadOne() }

    /** Loads every page left, calling [onPage] after each; true when the list reached its end. */
    suspend fun loadAll(onPage: (List<Video>) -> Unit): Boolean =
        mutex.withLock {
            while (nextToken != null) {
                if (!loadOne()) break
                onPage(videos)
            }
            isComplete
        }

    private suspend fun loadOne(): Boolean {
        val token = nextToken ?: return false
        if (pages >= maxPages || token in seenTokens) {
            truncated = true
            nextToken = null
            return false
        }
        val (more, next) = loadPage(token) ?: return false
        seenTokens += token
        videos = (videos + more).distinctBy { it.id }
        pages++
        nextToken = next
        next?.let { tokenAfter[videos.size] = it }
        return true
    }
}
