package io.github.aedev.flow.innertube.pages

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class TimedLyricLine(
    val startMs: Long,
    val text: String,
)

private val LYRICS_DATA_PATH =
    listOf(
        "contents",
        "elementRenderer",
        "newElement",
        "type",
        "componentType",
        "model",
        "timedLyricsModel",
        "lyricsData",
        "timedLyricsData",
    )

/**
 * The timed lines of an ANDROID_MUSIC lyrics browse. Cues without a start time are dropped, so a
 * music video row, which sends its lines with no times at all, reads as empty. So does a `♪` row,
 * which only marks the gap between lines.
 */
fun timedLyricLines(response: JsonObject): List<TimedLyricLine> {
    val lines = LYRICS_DATA_PATH.fold<String, JsonElement?>(response) { node, key -> (node as? JsonObject)?.get(key) } as? JsonArray
    return lines.orEmpty().mapNotNull { element ->
        val line = element as? JsonObject ?: return@mapNotNull null
        val text = line.string("lyricLine")?.trim().orEmpty()
        val startMs = (line["cueRange"] as? JsonObject)?.string("startTimeMilliseconds")?.toLongOrNull()
        if (startMs == null || text.all { it == '♪' || it.isWhitespace() }) null else TimedLyricLine(startMs, text)
    }
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
