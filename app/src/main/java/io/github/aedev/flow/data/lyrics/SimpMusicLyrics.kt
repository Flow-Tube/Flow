// ============================================================================
// THIS IMPLEMENTATION WAS INSPIRED BY METROLIST
// ============================================================================

package io.github.aedev.flow.data.lyrics

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.math.abs

/**
 * Service for fetching lyrics from the SimpMusic API.
 * Returns structured LyricsEntry objects.
 * Prioritizes richSyncLyrics (word-by-word JSON) > syncedLyrics (LRC) > plainLyrics.
 *
 * API endpoint: https://api-lyrics.simpmusic.org/v1/{videoId}
 * Response: {"type":"success","data":[{...lyrics data...}]}
 */
class SimpMusicLyrics(
    private val client: OkHttpClient,
) {
    private val gson = Gson()

    /**
     * Rich sync JSON item structure from SimpMusic API.
     */
    private data class RichSyncItem(
        val ts: Double = 0.0,
        val te: Double = 0.0,
        val l: List<RichSyncWord>? = null,
        val x: String? = null,
    )

    private data class RichSyncWord(
        val c: String = "",
        val o: Double = 0.0,
    )

    /**
     * Fetch lyrics by video ID and return structured entries.
     */
    suspend fun getLyrics(
        videoId: String,
        duration: Int? = null,
    ): Result<List<LyricsEntry>> =
        withContext(Dispatchers.IO) {
            try {
                val url = BASE_URL + videoId
                Log.d(TAG, "Fetching: $url")

                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("User-Agent", "SimpMusicLyrics/1.0")
                        .header("Accept", "application/json")
                        .build()

                client.newCall(request).execute().use { response ->
                    if (response.code == 404) return@withContext Result.failure(LyricsNotFoundException())
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            Exception("SimpMusic API returned ${response.code}"),
                        )
                    }

                    val body = response.body?.string()
                    if (body.isNullOrBlank()) {
                        return@withContext Result.failure(Exception("Empty response from SimpMusic"))
                    }

                    val entries = parseResponse(body, duration)
                    if (entries != null && entries.isNotEmpty()) {
                        Log.d(TAG, "Got ${entries.size} lines, ${entries.count { it.words != null }} with word sync")
                        Result.success(entries)
                    } else {
                        Result.failure(LyricsNotFoundException("No lyrics found in SimpMusic response"))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "SimpMusic fetch failed: ${e.message}")
                Result.failure(e)
            }
        }

    /** Null when the response is fine and has nothing usable. A response that isn't one throws. */
    internal fun parseResponse(
        json: String,
        duration: Int?,
    ): List<LyricsEntry>? {
        val root = JsonParser.parseString(json)

        val lyricsArray =
            when {
                root.isJsonArray -> {
                    root.asJsonArray
                }

                root.isJsonObject -> {
                    val obj = root.asJsonObject
                    val type = obj.text("type")
                    if (type != null && type != "success") throw IOException("SimpMusic returned type=$type")
                    obj.getAsJsonArray("data") ?: return null
                }

                else -> {
                    throw IOException("SimpMusic sent no lyrics list")
                }
            }

        if (lyricsArray.size() == 0) return null

        val bestMatch = closestRow(lyricsArray.map { it.asJsonObject }, duration) ?: return null

        val richSyncStr = bestMatch.text("richSyncLyrics")
        if (!richSyncStr.isNullOrBlank()) {
            val richEntries = parseRichSyncJson(richSyncStr, duration)
            if (richEntries != null && richEntries.isNotEmpty()) {
                Log.d(TAG, "Using richSyncLyrics (${richEntries.size} lines)")
                return richEntries
            }
        }

        val syncedStr = bestMatch.text("syncedLyrics")
        if (!syncedStr.isNullOrBlank()) {
            val entries = LyricsUtils.parseLyrics(syncedStr)
            if (entries.isNotEmpty()) {
                Log.d(TAG, "Using syncedLyrics (${entries.size} lines)")
                return entries
            }
        }

        val plainStr = bestMatch.text("plainLyrics") ?: bestMatch.text("plainLyric")
        if (!plainStr.isNullOrBlank()) {
            Log.d(TAG, "Using plainLyrics")
            return listOf(LyricsEntry(time = 0L, text = plainStr))
        }
        return null
    }

    /**
     * The video id can still list lyrics for a shorter or longer upload, whose lines would drift, so
     * a row more than [MAX_DURATION_GAP_SECONDS] from the song's length is never used.
     */
    private fun closestRow(
        rows: List<JsonObject>,
        duration: Int?,
    ): JsonObject? {
        val votes = compareByDescending<JsonObject> { it.number("vote") }
        if (duration == null || duration <= 0) return rows.minWithOrNull(votes)
        return rows
            .filter { abs(it.length() - duration) <= MAX_DURATION_GAP_SECONDS }
            .minWithOrNull(compareBy<JsonObject> { abs(it.length() - duration) }.then(votes))
    }

    private fun JsonObject.length(): Int = number("durationSeconds").takeIf { it > 0 } ?: number("duration")

    private fun JsonObject.number(key: String): Int = get(key)?.takeUnless { it.isJsonNull }?.asInt ?: 0

    private fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString

    /**
     * Parse SimpMusic's richSyncLyrics JSON format into LyricsEntry with word timestamps.
     *
     * Format: [{"ts": 1.23, "te": 5.67, "l": [{"c": "word", "o": 0.0}, ...], "x": "full line"}, ...]
     */
    private fun parseRichSyncJson(
        richSyncJson: String,
        duration: Int?,
    ): List<LyricsEntry>? {
        val trimmedJson = richSyncJson.trim()
        if (trimmedJson.isBlank()) return null

        if (!looksLikeRichSyncJson(trimmedJson)) {
            return LyricsUtils.parseLyrics(trimmedJson).takeIf { it.isNotEmpty() }
        }

        return try {
            // Detect API format changes: if first element is a primitive string the format is
            // no longer the expected object array — fall through to syncedLyrics silently.
            val parsed = JsonParser.parseString(trimmedJson)
            if (parsed.isJsonArray && parsed.asJsonArray.size() > 0 &&
                parsed.asJsonArray[0].isJsonPrimitive
            ) {
                Log.w(TAG, "richSyncLyrics is string-array (API format changed), skipping")
                return null
            }
            val type = object : TypeToken<List<RichSyncItem>>() {}.type
            val items: List<RichSyncItem> = gson.fromJson(trimmedJson, type)

            items.mapNotNull { item ->
                val lineWords = item.l.orEmpty()
                val lineText = buildLineText(lineWords, item.x)
                if (lineText.isBlank()) return@mapNotNull null

                val lineStartMs = normalizeTimeToMs(item.ts, duration)
                val lineEndMs =
                    normalizeTimeToMs(item.te, duration)
                        .takeIf { it > lineStartMs }
                        ?: (lineStartMs + 2_000L)

                val wordTimestamps =
                    lineWords
                        .mapNotNull { word ->
                            val trimmed = word.c.trim()
                            if (trimmed.isBlank()) {
                                null
                            } else {
                                WordTimestamp(
                                    text = trimmed,
                                    startTime = resolveWordStartMs(lineStartMs, lineEndMs, word.o),
                                    endTime = lineEndMs,
                                )
                            }
                        }.sortedBy { it.startTime }

                val refinedWords =
                    wordTimestamps.mapIndexed { index, wt ->
                        val nextStart =
                            if (index < wordTimestamps.lastIndex) {
                                wordTimestamps[index + 1].startTime
                            } else {
                                lineEndMs
                            }
                        wt.copy(endTime = nextStart.coerceAtLeast(wt.startTime + 1))
                    }

                LyricsEntry(
                    time = lineStartMs,
                    text = lineText.trim(),
                    words = refinedWords.takeIf { it.isNotEmpty() },
                )
            }
        } catch (e: Exception) {
            val lrcFallback = LyricsUtils.parseLyrics(trimmedJson).takeIf { it.isNotEmpty() }
            if (lrcFallback != null) {
                Log.d(TAG, "richSyncLyrics was not JSON; parsed as timed lyrics")
                lrcFallback
            } else {
                Log.w(TAG, "Failed to parse richSync JSON: ${e.message}")
                null
            }
        }
    }

    private fun looksLikeRichSyncJson(value: String): Boolean {
        val first = value.firstOrNull { !it.isWhitespace() } ?: return false
        if (first == '{') return true
        if (first != '[') return false
        val afterBracket = value.drop(1).firstOrNull { !it.isWhitespace() } ?: return false
        return afterBracket == '{' || afterBracket == ']'
    }

    private fun normalizeTimeToMs(
        value: Double,
        duration: Int?,
    ): Long {
        val isProbablyMillis = value > 1_000 || (duration != null && duration > 0 && value > duration + 60)
        return if (isProbablyMillis) value.toLong() else (value * 1_000).toLong()
    }

    private fun resolveWordStartMs(
        lineStartMs: Long,
        lineEndMs: Long,
        rawOffset: Double,
    ): Long {
        val candidates =
            listOf(
                lineStartMs + (rawOffset * 1_000).toLong(),
                (rawOffset * 1_000).toLong(),
                lineStartMs + rawOffset.toLong(),
                rawOffset.toLong(),
            ).distinct()

        return candidates
            .filter { it in (lineStartMs - 1_000)..(lineEndMs + 1_000) }
            .minByOrNull { abs(it - lineStartMs) }
            ?: (lineStartMs + (rawOffset * 1_000).toLong()).coerceAtLeast(lineStartMs)
    }

    private fun buildLineText(
        words: List<RichSyncWord>,
        fallback: String?,
    ): String {
        val fromWords =
            words
                .map { it.c.trim() }
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .replace(Regex("\\s+([,.;:!?%])"), "$1")
                .replace(Regex("([([{])\\s+"), "$1")
                .trim()

        val fallbackText = fallback?.trim().orEmpty()
        return when {
            fromWords.isBlank() -> fallbackText
            fallbackText.isBlank() -> fromWords
            !fallbackText.any { it.isWhitespace() } && fromWords.any { it.isWhitespace() } -> fromWords
            else -> fallbackText
        }
    }

    private companion object {
        const val TAG = "SimpMusicLyrics"
        const val BASE_URL = "https://api-lyrics.simpmusic.org/v1/"
        const val MAX_DURATION_GAP_SECONDS = 10
    }
}
