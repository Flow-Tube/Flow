package io.github.aedev.flow.innertube

import io.github.aedev.flow.innertube.models.YouTubeLocale
import io.github.aedev.flow.innertube.models.responseMaxAgeSeconds
import io.github.aedev.flow.utils.ExpiringLruCache
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Channel browse responses, reused for as long as the response's own `maxAgeSeconds` allows (300 s on
 * channel and posts browses), so reopening a channel inside that window sends nothing. A response that
 * names no lifetime is never kept.
 *
 * Entries are keyed by the locale and visitor they were fetched under: after a language, region or identity
 * change the old answer is simply never matched again. A profile switch restarts the process.
 *
 * The body text is kept rather than its parsed tree, which is several times its size.
 */
internal class ChannelBrowseCache(
    capacity: Int = CAPACITY,
    now: () -> Long = System::currentTimeMillis,
) {
    data class Key(
        val browseId: String?,
        val params: String?,
        val continuation: String?,
        val locale: YouTubeLocale,
        val visitorData: String?,
    )

    private val responses = ExpiringLruCache<Key, String>(capacity, now)

    suspend fun read(
        key: Key,
        fetch: suspend () -> String,
    ): JsonElement {
        responses.fresh(key)?.let { return Json.parseToJsonElement(it) }
        val body = fetch()
        val json = Json.parseToJsonElement(body)
        json.responseMaxAgeSeconds()?.let { seconds -> responses.put(key, body, seconds * MS_PER_SECOND) }
        return json
    }

    private companion object {
        // A channel open is the landing, its About panel and a tab or two; this holds about two channels.
        const val CAPACITY = 6
        const val MS_PER_SECOND = 1_000L
    }
}
