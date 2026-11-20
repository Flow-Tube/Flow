package io.github.aedev.flow.innertube.models.response

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Where a stream's chat starts, live or replayed, read from the watch response the player already
 * fetched for the description, chapters and related videos, so finding it costs no request.
 */
object ChatReplaySeed {
    private val json = Json { ignoreUnknownKeys = true }

    fun token(watchNext: JsonElement?): String? = seedOf(watchNext)?.replayContinuation()

    fun liveToken(watchNext: JsonElement?): String? = seedOf(watchNext)?.seedContinuation()

    private fun seedOf(watchNext: JsonElement?): LiveChatSeedResponse? =
        watchNext?.let { runCatching { json.decodeFromJsonElement(LiveChatSeedResponse.serializer(), it) }.getOrNull() }
}
