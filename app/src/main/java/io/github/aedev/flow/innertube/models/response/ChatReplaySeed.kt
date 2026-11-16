package io.github.aedev.flow.innertube.models.response

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Where a finished stream's chat replay starts, read from the watch response the player already
 * fetched for the description, chapters and related videos, so finding it costs no request.
 */
object ChatReplaySeed {
    private val json = Json { ignoreUnknownKeys = true }

    fun token(watchNext: JsonElement?): String? =
        watchNext?.let {
            runCatching { json.decodeFromJsonElement(LiveChatSeedResponse.serializer(), it) }
                .getOrNull()
                ?.replayContinuation()
        }
}
