package io.github.aedev.flow.ui.screens.home

import io.github.aedev.flow.data.model.Video
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val HOME_SEARCH_TIMEOUT_MS = 6_000L

/**
 * Home's discovery searches. A page is fetched once per half hour however often load-more asks for
 * its query again, and the next page of a search that already filled Home with several cards is kept
 * at hand, so load-more digs deeper into a subject that works instead of padding with weaker ones (#907).
 * Only pages that came back with videos are kept: a failed or empty search is asked again next time
 * instead of answering empty for half an hour (#1317).
 */
internal class HomeSearchPages(
    private val search: suspend (query: String, continuation: String?) -> Result<Pair<List<Video>, String?>>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private companion object {
        const val CACHE_TTL_MS = 30L * 60L * 1000L
        const val CACHE_MAX = 20

        // A search proves itself with this many cards on Home before load-more asks for its next page.
        const val PRODUCTIVE_CARDS = 3
    }

    private class Page(
        val videos: List<Video>,
        val next: String?,
        val at: Long,
    )

    private val mutex = Mutex()
    private val pages =
        object : LinkedHashMap<String, Page>(CACHE_MAX, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Page>?) = size > CACHE_MAX
        }

    // The continuation of the last page fetched for each query; spent when that page is fetched.
    private val nextPage = LinkedHashMap<String, String>()

    /** The first page of [query]; throws when the request failed. */
    suspend fun firstPage(query: String): List<Video> = fetch(query, null)

    /** The next page of whichever search put the most cards in [origins], or null when none has proved itself or has one. */
    suspend fun nextPageOfProductive(origins: Map<String, HomeCardOrigin>): Pair<String, List<Video>>? {
        val (query, token) =
            mutex.withLock {
                val query = productiveQuery(origins) ?: return@withLock null
                nextPage.remove(query)?.let { query to it }
            } ?: return null
        return query to fetch(query, token)
    }

    /** The next page of the search whose next page was kept longest, or null when none is left. */
    suspend fun nextPageOfAny(): Pair<String, List<Video>>? {
        val (query, token) =
            mutex.withLock {
                val query = nextPage.keys.firstOrNull() ?: return@withLock null
                nextPage.remove(query)?.let { query to it }
            } ?: return null
        return query to fetch(query, token)
    }

    // Callers hold the mutex: it reads the kept next pages.
    private fun productiveQuery(origins: Map<String, HomeCardOrigin>): String? =
        origins.values
            .filter { it.kind == HomeCardOrigin.Kind.SEARCH && it.detail != null }
            .groupingBy { it.detail!! }
            .eachCount()
            .filter { (query, cards) -> cards >= PRODUCTIVE_CARDS && query in nextPage }
            .maxByOrNull { it.value }
            ?.key

    /** The search a cached page returned [videoId] in, or null once that page left the cache. */
    suspend fun queryOf(videoId: String): String? =
        mutex.withLock {
            pages.entries
                .firstOrNull { (_, page) -> page.videos.any { it.id == videoId } }
                ?.key
                ?.substringBefore('\u0000')
        }

    /** A refresh starts the searches over: it asks again, and the next pages it had kept belong to the old feed. */
    suspend fun reset() =
        mutex.withLock {
            pages.clear()
            nextPage.clear()
        }

    private suspend fun fetch(
        query: String,
        continuation: String?,
    ): List<Video> {
        val key = "$query\u0000${continuation.orEmpty()}"
        val now = clock()
        mutex.withLock { pages[key]?.takeIf { now - it.at < CACHE_TTL_MS } }?.let { cached ->
            remember(query, cached.next)
            return cached.videos
        }
        val (videos, next) =
            search(query, continuation).getOrElse { error ->
                // The next page was spent on this request; keep it for another try.
                if (continuation != null) remember(query, continuation)
                throw error
            }
        if (videos.isNotEmpty()) mutex.withLock { pages[key] = Page(videos, next, now) }
        remember(query, next)
        return videos
    }

    private suspend fun remember(
        query: String,
        next: String?,
    ) = mutex.withLock {
        if (next.isNullOrBlank()) nextPage.remove(query) else nextPage[query] = next
    }
}
