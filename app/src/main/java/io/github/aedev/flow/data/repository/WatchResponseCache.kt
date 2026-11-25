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
 *
 * An entry lasts [maxAgeMs] and only under the [identity] (the content locale) it was fetched in, so
 * replaying a video later, or after a language change, brings its current counts, comments and related.
 */
internal class WatchResponseCache<V : Any>(
    capacity: Int,
    scope: CoroutineScope,
    private val maxAgeMs: Long = MAX_AGE_MS,
    private val now: () -> Long = System::currentTimeMillis,
    private val identity: () -> Any? = { null },
    private val fetch: suspend (String) -> V?,
) {
    private class Entry<V>(
        val value: V,
        val fetchedAtMs: Long,
        val identity: Any?,
    )

    private val entries = LruCache<String, Entry<V>>(capacity)
    private val inFlight = InFlightRequestCoalescer<String, V?>(scope)

    fun cached(videoId: String): V? {
        val entry = entries[videoId] ?: return null
        if (now() - entry.fetchedAtMs < maxAgeMs && entry.identity == identity()) return entry.value
        entries.remove(videoId)
        return null
    }

    fun size(): Int = entries.size()

    fun resize(capacity: Int) = entries.resize(capacity)

    /** Drops every entry but [videoId]'s, which the player is still reading. */
    fun retainOnly(videoId: String?) {
        entries
            .snapshot()
            .keys
            .filter { it != videoId }
            .forEach(entries::remove)
    }

    suspend fun forPlayer(videoId: String): V? = cached(videoId) ?: fetchShared(videoId)?.also { store(videoId, it) }

    suspend fun forFeed(videoId: String): V? = cached(videoId) ?: fetchShared(videoId)

    private suspend fun fetchShared(videoId: String): V? = inFlight.run(videoId) { fetch(videoId) }

    private fun store(
        videoId: String,
        value: V,
    ) {
        entries.put(videoId, Entry(value, now(), identity()))
    }

    companion object {
        // One viewing reads its response within minutes (description and related at open, comments when
        // scrolled to), so half an hour covers it, while a replay after that fetches the page as it is now.
        const val MAX_AGE_MS = 30L * 60L * 1_000L
    }
}
