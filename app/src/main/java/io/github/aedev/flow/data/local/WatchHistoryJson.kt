package io.github.aedev.flow.data.local

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import java.io.BufferedReader
import java.io.IOException
import java.io.Writer
import java.time.Instant
import kotlin.math.roundToLong

private data class FreeTubeHistoryExportEntry(
    val videoId: String? = null,
    val author: String? = null,
    val authorId: String? = null,
    val title: String? = null,
    val timeWatched: Long? = null,
    val lengthSeconds: Long? = null,
    val watchProgress: Double? = null,
)

private data class YouTubeTakeoutHistoryEntryOut(
    val header: String = "YouTube",
    val title: String,
    val titleUrl: String,
    val subtitles: List<YouTubeTakeoutSubtitleOut>,
    val time: String,
    val products: List<String> = listOf("YouTube"),
    val activityControls: List<String> = listOf("YouTube watch history"),
)

private data class YouTubeTakeoutSubtitleOut(
    val name: String,
    val url: String,
)

private data class YouTubeArchiveSubtitle(
    val name: String? = null,
    val url: String? = null,
)

private data class YouTubeArchiveHistoryEntry(
    val title: String? = null,
    val titleUrl: String? = null,
    val subtitles: List<YouTubeArchiveSubtitle>? = null,
    val time: String? = null,
    val details: Any? = null,
)

/**
 * Watch history as JSON files: a YouTube Takeout list or a FreeTube export (one object per line) in,
 * a Takeout list out. Both directions stream, so a history of tens of thousands of videos is never
 * held as one string (#2258).
 */
internal class WatchHistoryJson(
    private val gson: Gson,
) {
    /** The entries of [source]; empty for anything that is neither format. */
    fun read(source: BufferedReader): List<VideoHistoryEntry> {
        source.mark(PEEK_CHARS)
        val first = generateSequence { source.read().takeIf { it >= 0 } }.firstOrNull { !Character.isWhitespace(it) }
        source.reset()
        return when (first?.toChar()) {
            '[' -> readTakeout(source)
            '{' -> readFreeTube(source)
            else -> emptyList()
        }
    }

    fun writeTakeout(
        history: List<VideoHistoryEntry>,
        out: Writer,
    ) {
        val items =
            history.asSequence().mapNotNull { entry ->
                val videoId = entry.videoId.trim()
                if (videoId.isEmpty()) return@mapNotNull null
                val channelUrl =
                    UC_ID
                        .find(entry.channelId.trim())
                        ?.value
                        ?.let { "https://www.youtube.com/channel/$it" }
                        ?: "https://www.youtube.com/"
                YouTubeTakeoutHistoryEntryOut(
                    title = "Watched ${entry.title}",
                    titleUrl = "https://www.youtube.com/watch?v=$videoId",
                    subtitles = listOf(YouTubeTakeoutSubtitleOut(name = entry.channelName.ifBlank { "YouTube" }, url = channelUrl)),
                    time = Instant.ofEpochMilli(entry.timestamp).toString(),
                )
            }
        val writer = gson.newJsonWriter(out)
        writer.beginArray()
        items.forEach { gson.toJson(it, YouTubeTakeoutHistoryEntryOut::class.java, writer) }
        writer.endArray()
        writer.flush()
    }

    private fun readTakeout(source: BufferedReader): List<VideoHistoryEntry> {
        val entries = ArrayList<VideoHistoryEntry>()
        try {
            val reader = JsonReader(source)
            reader.beginArray()
            var index = 0
            while (reader.hasNext()) {
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    reader.skipValue()
                    continue
                }
                val item = gson.fromJson<YouTubeArchiveHistoryEntry>(reader, YouTubeArchiveHistoryEntry::class.java)
                item?.toVideoHistoryEntry(index)?.let(entries::add)
                index++
            }
        } catch (_: JsonParseException) {
            return emptyList()
        } catch (_: IOException) {
            return emptyList()
        } catch (_: IllegalStateException) {
            return emptyList()
        }
        return entries
    }

    private fun readFreeTube(source: BufferedReader): List<VideoHistoryEntry> =
        source
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .withIndex()
            .mapNotNull { (index, line) ->
                runCatching { gson.fromJson(line, FreeTubeHistoryExportEntry::class.java) }.getOrNull()?.toVideoHistoryEntry(index)
            }.toList()

    private fun FreeTubeHistoryExportEntry.toVideoHistoryEntry(index: Int): VideoHistoryEntry? {
        val normalizedVideoId = videoId?.trim().orEmpty()
        val normalizedTitle = title?.trim().orEmpty()
        if (normalizedVideoId.isEmpty() || normalizedTitle.isEmpty()) return null

        val durationMs = (lengthSeconds ?: 0L).coerceAtLeast(0L) * 1000L
        val progress = (watchProgress ?: 0.0).coerceIn(0.0, 1.0)
        val positionMs =
            if (durationMs > 0L && progress > 0.0) {
                (durationMs.toDouble() * progress).roundToLong().coerceIn(0L, durationMs)
            } else {
                0L
            }

        return VideoHistoryEntry(
            videoId = normalizedVideoId,
            position = positionMs,
            duration = durationMs,
            timestamp = timeWatched ?: (System.currentTimeMillis() - index),
            title = normalizedTitle,
            thumbnailUrl = ThumbnailUrlResolver.buildHighQualityYoutubeThumbnail(normalizedVideoId),
            channelName = author?.trim().orEmpty(),
            channelId = authorId?.trim().orEmpty(),
            isMusic = false,
        )
    }

    private fun YouTubeArchiveHistoryEntry.toVideoHistoryEntry(index: Int): VideoHistoryEntry? {
        if (details != null) return null

        val videoId = VIDEO_ID.firstGroup(titleUrl?.trim().orEmpty())
        if (videoId.isEmpty()) return null

        val normalizedTitle =
            title
                .orEmpty()
                .trim()
                .removePrefix("Watched ")
                .trim()
        if (normalizedTitle.isEmpty()) return null

        val subtitle = subtitles.orEmpty().firstOrNull()
        return VideoHistoryEntry(
            videoId = videoId,
            position = 0L,
            duration = 0L,
            timestamp = parseArchiveTimestamp(time) ?: (System.currentTimeMillis() - index),
            title = normalizedTitle,
            thumbnailUrl = ThumbnailUrlResolver.buildHighQualityYoutubeThumbnail(videoId),
            channelName = subtitle?.name?.trim().orEmpty(),
            channelId = CHANNEL_ID.firstGroup(subtitle?.url.orEmpty()),
            isMusic = false,
        )
    }

    companion object {
        private const val PEEK_CHARS = 4096
        private val UC_ID = Regex("UC[0-9A-Za-z_-]{22}")
        private val VIDEO_ID = Regex("""[?&]v=([\w-]{10,12})""")
        private val CHANNEL_ID = Regex("""/channel/([^/?#]+)""")

        private fun Regex.firstGroup(text: String): String =
            find(text)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()

        fun parseArchiveTimestamp(value: String?): Long? {
            val raw = value?.trim().orEmpty()
            if (raw.isEmpty()) return null
            return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
        }
    }
}
