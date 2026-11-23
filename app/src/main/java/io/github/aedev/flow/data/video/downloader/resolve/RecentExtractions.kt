package io.github.aedev.flow.data.video.downloader.resolve

import io.github.aedev.flow.player.error.StreamDenialClassifier
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor.VideoExtractionResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The formats a download sheet just loaded, handed to the download it starts so the queue does not
 * ask `/player` a second time. A result is used once, and only while every URL in it has at least
 * ten minutes left and it is under an hour old (YouTube's offline player-response cache keeps one an
 * hour); anything else is extracted fresh. A few at most are kept: each holds a whole player response.
 */
@Singleton
class RecentExtractions internal constructor(
    private val clock: () -> Long,
) {
    @Inject
    constructor() : this(System::currentTimeMillis)

    private class Entry(
        val result: VideoExtractionResult,
        val atMs: Long,
    )

    private val entries = LinkedHashMap<String, Entry>()

    fun remember(
        videoId: String,
        result: VideoExtractionResult,
    ) {
        synchronized(entries) {
            entries.remove(videoId)
            entries[videoId] = Entry(result, clock())
            while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        }
    }

    fun take(videoId: String): VideoExtractionResult? {
        val entry = synchronized(entries) { entries.remove(videoId) } ?: return null
        val now = clock()
        if (now - entry.atMs > MAX_AGE_MS) return null
        val urls = (entry.result.videoFormats + entry.result.audioFormats).mapNotNull { it.url }
        if (urls.isEmpty()) return null
        val nowSeconds = now / 1000
        val fresh = urls.all { url -> (StreamDenialClassifier.expiresInSeconds(url, nowSeconds) ?: 0L) >= MIN_LEFT_SECONDS }
        return entry.result.takeIf { fresh }
    }

    internal companion object {
        const val MAX_ENTRIES = 4
        const val MAX_AGE_MS = 3_600_000L
        const val MIN_LEFT_SECONDS = 10 * 60L
    }
}
