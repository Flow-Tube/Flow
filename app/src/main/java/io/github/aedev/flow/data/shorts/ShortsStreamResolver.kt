package io.github.aedev.flow.data.shorts

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.manifest.DashManifest
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.AppMemoryTrim
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.audio.LoudnessGain
import io.github.aedev.flow.player.shorts.ShortsStartupTrace
import io.github.aedev.flow.player.stream.InFlightRequestCoalescer
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor.VideoExtractionResult
import io.github.aedev.flow.player.stream.VideoCodecUtils
import io.github.aedev.flow.player.stream.durationMs
import io.github.aedev.flow.player.stream.playableAudioFormats
import io.github.aedev.flow.player.stream.playableVideoFormats
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** What one reel's `/player` response says about it, carried with the streams so nothing asks twice. */
data class ShortDetails(
    val title: String,
    val channelName: String,
    val channelId: String,
    val viewCount: Long?,
    val durationMs: Long?,
)

data class ShortPlaybackStreams(
    val videoUrl: String,
    val audioUrl: String?,
    val durationMs: Long?,
    /** See [ShortsDashManifest]. Null means play the URL progressively. */
    val videoDashManifest: String? = null,
    val audioDashManifest: String? = null,
    /** Auto's adaptive ladder, see [shortsAutoLadder]; null plays the single [videoUrl]. */
    val videoAdaptiveManifest: DashManifest? = null,
    val details: ShortDetails? = null,
    /** YouTube's loudness for the reel, in dB, for volume normalization. */
    val loudnessGainDb: Float? = null,
)

data class ShortVideoQuality(
    val heightClass: Int,
    val label: String,
    val videoUrl: String,
    val codecLabel: String,
    val codecKey: String = "",
    val dashManifest: String? = null,
)

data class ShortAudioTrack(
    val label: String,
    val url: String,
    val dashManifest: String?,
    val isOriginal: Boolean,
    val bitrate: Int,
    val loudnessGainDb: Float? = null,
)

/**
 * Streams for a reel, from the one extraction path the main player uses. The `/player` response is
 * kept for the reel's lifetime on screen so the quality, audio-track and download sheets read it
 * instead of fetching it again, and every URL expires with the response it came from.
 */
@Singleton
class ShortsStreamResolver internal constructor(
    private val playerPreferences: PlayerPreferences,
    private val extract: suspend (String) -> VideoExtractionResult?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        playerPreferences: PlayerPreferences,
    ) : this(
        playerPreferences = playerPreferences,
        extract = { videoId -> InnerTubeVideoStreamExtractor.extract(videoId) },
    ) {
        // Listening here rather than being trimmed from the application means a trim never creates a
        // resolver for a user who has not opened Shorts.
        context.registerComponentCallbacks(TrimOnMemoryPressure(::trimMemory))
    }

    private class Expiring<T>(
        val value: T,
        val expiresAtMillis: Long,
    )

    private val extractions = lruCache<String, Expiring<VideoExtractionResult>>(MAX_EXTRACTIONS)
    private val streams = lruCache<String, Expiring<ShortPlaybackStreams>>(MAX_STREAMS)
    private val scope = CoroutineScope(SupervisorJob() + PerformanceDispatcher.networkIO)
    private val extractionCoalescer = InFlightRequestCoalescer<String, VideoExtractionResult?>(scope)
    private val streamCoalescer = InFlightRequestCoalescer<String, ShortPlaybackStreams?>(scope)

    fun trimMemory() {
        synchronized(extractions) { extractions.clear() }
        synchronized(streams) { streams.clear() }
    }

    suspend fun resolve(
        videoId: String,
        targetHeight: Int,
        preferredAudioLanguage: String,
    ): ShortPlaybackStreams? {
        ShortsStartupTrace.onRequested(videoId)
        val codecKey = playerPreferences.videoCodecPriority.first()
        val key = "$videoId|$targetHeight|$preferredAudioLanguage|$codecKey"
        cached(streams, key)?.let {
            ShortsStartupTrace.onStreamsResolved(videoId, cached = true, strategy = "cache")
            return it
        }
        return streamCoalescer.run(key) {
            val result = extraction(videoId) ?: return@run null
            val resolved = result.toPlaybackStreams(targetHeight, preferredAudioLanguage, codecKey) ?: return@run null
            synchronized(streams) { streams[key] = Expiring(resolved, expiryOf(result)) }
            ShortsStartupTrace.onStreamsResolved(videoId, cached = false, strategy = result.usedClient.clientName)
            resolved
        }
    }

    suspend fun availableQualities(videoId: String): List<ShortVideoQuality> {
        val result = extraction(videoId) ?: return emptyList()
        val durationMs = result.durationMs()
        return result
            .playableVideoFormats()
            .groupBy { "${shortsQualityClass(it)}_${VideoCodecUtils.codecKeyFromMimeType(it.mimeType)}" }
            .mapNotNull { (_, group) ->
                val best = group.maxByOrNull { it.averageBitrate ?: it.bitrate } ?: return@mapNotNull null
                val url = best.url ?: return@mapNotNull null
                val heightClass = shortsQualityClass(best)
                val codecKey = VideoCodecUtils.codecKeyFromMimeType(best.mimeType)
                ShortVideoQuality(
                    heightClass = heightClass,
                    label = VideoCodecUtils.qualityLabelWithFrameRate(heightClass, best.fps ?: 0),
                    videoUrl = url,
                    codecLabel = VideoCodecUtils.codecLabelFromKey(codecKey),
                    codecKey = codecKey,
                    dashManifest = ShortsDashManifest.forVideo(best, url, durationMs),
                )
            }.sortedWith(
                compareByDescending<ShortVideoQuality> { it.heightClass }
                    .thenBy { VideoCodecUtils.playbackCodecRank(it.codecKey) },
            )
    }

    /** One entry per audio track, best bitrate of each, the original first. */
    suspend fun availableAudioTracks(videoId: String): List<ShortAudioTrack> {
        val result = extraction(videoId) ?: return emptyList()
        val durationMs = result.durationMs()
        val audioConfig = result.playerResponse.playerConfig?.audioConfig
        return result
            .playableAudioFormats()
            .groupBy { it.audioTrack?.id ?: it.audioLanguageTag ?: "default" }
            .mapNotNull { (_, group) ->
                val best = group.maxByOrNull { it.averageBitrate ?: it.bitrate } ?: return@mapNotNull null
                val url = best.url ?: return@mapNotNull null
                ShortAudioTrack(
                    label = best.audioTrackLabel() ?: return@mapNotNull null,
                    url = url,
                    dashManifest = ShortsDashManifest.forAudio(best, url, durationMs),
                    isOriginal = best.isOriginal,
                    bitrate = (best.averageBitrate ?: best.bitrate).coerceAtLeast(0),
                    loudnessGainDb = LoudnessGain.gainDb(audioConfig, best.loudnessDb, best.trackAbsoluteLoudnessLkfs),
                )
            }.sortedWith(compareByDescending<ShortAudioTrack> { it.isOriginal }.thenBy { it.label })
    }

    suspend fun downloadFormats(
        videoId: String,
    ): Pair<List<PlayerResponse.StreamingData.Format>, List<PlayerResponse.StreamingData.Format>> {
        val result = extraction(videoId) ?: return emptyList<PlayerResponse.StreamingData.Format>() to emptyList()
        return result.playableVideoFormats() to result.playableAudioFormats()
    }

    suspend fun durationMs(videoId: String): Long? = extraction(videoId)?.durationMs()

    private suspend fun extraction(videoId: String): VideoExtractionResult? {
        cached(extractions, videoId)?.let { return it }
        return extractionCoalescer.run(videoId) {
            val result =
                try {
                    withTimeoutOrNull(EXTRACT_TIMEOUT_MS) { extract(videoId) }
                } catch (e: Exception) {
                    Log.w(TAG, "Extraction failed for $videoId: ${e.message}")
                    null
                } ?: return@run null
            synchronized(extractions) { extractions[videoId] = Expiring(result, expiryOf(result)) }
            result
        }
    }

    private fun <T> cached(
        cache: MutableMap<String, Expiring<T>>,
        key: String,
    ): T? =
        synchronized(cache) {
            val entry = cache[key] ?: return null
            if (nowMillis() < entry.expiresAtMillis) entry.value else null.also { cache.remove(key) }
        }

    /** A googlevideo URL outlives its `expiresInSeconds` by nothing; stop trusting it well before. */
    private fun expiryOf(result: VideoExtractionResult): Long {
        val ttlSeconds =
            result.playerResponse.streamingData
                ?.expiresInSeconds
                ?.takeIf { it > 0 }
                ?.toLong() ?: DEFAULT_TTL_SECONDS
        return nowMillis() + ttlSeconds * 1_000L * EXPIRY_FRACTION_PERCENT / 100L
    }

    @OptIn(UnstableApi::class)
    private fun VideoExtractionResult.toPlaybackStreams(
        targetHeight: Int,
        preferredAudioLanguage: String,
        codecKey: String,
    ): ShortPlaybackStreams? {
        val videoFormats = playableVideoFormats()
        val video = selectShortVideoFormat(videoFormats, targetHeight, codecKey) ?: return null
        val videoUrl = video.url ?: return null
        val audio = selectShortAudioFormat(playableAudioFormats(), preferredAudioLanguage)
        val durationMs = durationMs()
        return ShortPlaybackStreams(
            videoUrl = videoUrl,
            audioUrl = audio?.url,
            durationMs = durationMs,
            videoDashManifest = ShortsDashManifest.forVideo(video, videoUrl, durationMs),
            audioDashManifest = audio?.url?.let { ShortsDashManifest.forAudio(audio, it, durationMs) },
            videoAdaptiveManifest = if (targetHeight == 0) shortsAutoLadder(videoFormats, video, durationMs) else null,
            details = playerResponse.videoDetails?.toShortDetails(durationMs),
            loudnessGainDb =
                LoudnessGain.gainDb(
                    playerResponse.playerConfig?.audioConfig,
                    audio?.loudnessDb,
                    audio?.trackAbsoluteLoudnessLkfs,
                ),
        )
    }

    private fun PlayerResponse.VideoDetails.toShortDetails(durationMs: Long?) =
        ShortDetails(
            title = title.orEmpty(),
            channelName = author.orEmpty(),
            channelId = channelId,
            viewCount = viewCount?.toLongOrNull(),
            durationMs = durationMs,
        )

    private companion object {
        const val TAG = "ShortsStreamResolver"
        const val MAX_EXTRACTIONS = 20
        const val MAX_STREAMS = 50
        const val EXTRACT_TIMEOUT_MS = 8_000L
        const val DEFAULT_TTL_SECONDS = 60L * 60L
        const val EXPIRY_FRACTION_PERCENT = 80L

        fun <K, V> lruCache(maxEntries: Int): MutableMap<K, V> =
            object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
            }
    }
}

internal class TrimOnMemoryPressure(
    private val trim: () -> Unit,
) : ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
        if (AppMemoryTrim.releasesVolatileMemory(level)) trim()
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = trim()
}
