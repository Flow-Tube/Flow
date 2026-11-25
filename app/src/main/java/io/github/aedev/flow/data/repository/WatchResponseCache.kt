package io.github.aedev.flow.data.repository

import androidx.collection.LruCache
import io.github.aedev.flow.player.stream.InFlightRequestCoalescer
import kotlinx.coroutines.CoroutineScope

/**
 * The watch responses for the few videos in play, fetched once and shared.
 *
 * Only player-side reads store an entry. A feed lookup (playlist or history backfill, a home lane
 * seed) reuses an entry and joins a request already in flight, but never stores what it fetched:
 * the cache is a few entries deep, and a home load filling it would push out the playing video.
 */
internal class WatchResponseCache<V : Any>(
    capacity: Int,
    scope: CoroutineScope,
    private val fetch: suspend (String) -> V?,
) {
    private val entries = LruCache<String, V>(capacity)
    private val inFlight = InFlightRequestCoalescer<String, V?>(scope)

    fun cached(videoId: String): V? = entries[videoId]

    fun size(): Int = entries.size()

    suspend fun forPlayer(videoId: String): V? =
        entries[videoId] ?: inFlight.run(videoId) { fetch(videoId) }?.also { entries.put(videoId, it) }

    suspend fun forFeed(videoId: String): V? = entries[videoId] ?: inFlight.run(videoId) { fetch(videoId) }
}
