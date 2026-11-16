package io.github.aedev.flow.utils

import android.util.Log
import io.github.aedev.flow.FlowApplication
import io.github.aedev.flow.data.local.MusicAudioQuality
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.innertube.models.YouTubeClient
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.audio.LoudnessGain
import io.github.aedev.flow.player.error.PlayerDiagnostics
import io.github.aedev.flow.player.error.StreamDenialClassifier
import io.github.aedev.flow.player.stream.AudioStreamSelector
import io.github.aedev.flow.player.stream.ClientGateTracker
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor
import io.github.aedev.flow.player.stream.PlaybackBlockedException
import io.github.aedev.flow.player.stream.VideoUnavailableException
import io.github.aedev.flow.player.stream.preferNonDrc
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

object MusicPlayerUtils {
    private const val TAG = "MusicPlayerUtils"

    // Request deduplication - prevents duplicate fetches for same video
    private val activeRequests = ConcurrentHashMap<String, CompletableDeferred<Result<PlaybackData>>>()

    private data class CachedResult(
        val result: Result<PlaybackData>,
        val expiryMs: Long,
    ) {
        // A url prefetched before its client was demoted would stall the same way ~30 s in.
        fun isFromGatedClient(): Boolean = ClientGateTracker.isGated(StreamDenialClassifier.clientOf(result.getOrNull()?.streamUrl))
    }

    private val resultCache = ConcurrentHashMap<String, CachedResult>()
    private const val MAX_RESULT_CACHE_TTL_MS = 600_000L // 10 minutes

    // Matches the video player's load budget. The ladder can reach TV_TIZEN, whose remote decoder
    // calls and pre-roll wait have no bound of their own, and an unbounded resolve is a spinner
    // that never turns into an error.
    private const val RESOLVE_TIMEOUT_MS = 30_000L

    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        val usedClient: YouTubeClient,
        val videoFormats: List<PlayerResponse.StreamingData.Format> = emptyList(),
        val audioFormats: List<PlayerResponse.StreamingData.Format> = emptyList(),
    )

    private data class AudioSelectionPreferences(
        val preferredAudioLanguage: String,
        val musicAudioQuality: MusicAudioQuality,
    )

    private suspend fun loadAudioSelectionPreferences(): AudioSelectionPreferences {
        val playerPreferences = PlayerPreferences(FlowApplication.appContext)
        return AudioSelectionPreferences(
            preferredAudioLanguage = playerPreferences.preferredAudioLanguage.first(),
            musicAudioQuality =
                playerPreferences.musicAudioQuality
                    .first()
                    .resolve(onWifi = NetworkState.isOnWifi(FlowApplication.appContext)),
        )
    }

    fun forceRefreshForVideo(videoId: String) {
        Log.d(TAG, "Force refresh requested for $videoId")
        activeRequests.remove(videoId)
        resultCache.remove(videoId)
    }

    fun clearPlaybackCache() {
        resultCache.clear()
        Log.d(TAG, "Cleared all cached playback results")
    }

    fun cachedLoudnessGainDb(videoId: String): Float? {
        val data = resultCache[videoId]?.result?.getOrNull() ?: return null
        return LoudnessGain.gainDb(data.audioConfig, data.format.loudnessDb)
    }

    /** The resolved playback of [videoId] while it is still fresh, without waiting on a request. */
    fun cachedPlayback(videoId: String): PlaybackData? =
        resultCache[videoId]
            ?.takeIf { System.currentTimeMillis() < it.expiryMs && !it.isFromGatedClient() }
            ?.result
            ?.getOrNull()

    suspend fun playerResponseForPlayback(videoId: String): Result<PlaybackData> =
        withContext(Dispatchers.IO) {
            val cached = resultCache[videoId]
            if (cached != null) {
                if (System.currentTimeMillis() < cached.expiryMs && cached.result.isSuccess && !cached.isFromGatedClient()) {
                    Log.d(TAG, "Returning cached result for $videoId (expires in ${cached.expiryMs - System.currentTimeMillis()}ms)")
                    return@withContext cached.result
                } else {
                    resultCache.remove(videoId)
                }
            }

            val existingRequest = activeRequests[videoId]
            if (existingRequest != null && existingRequest.isActive) {
                Log.d(TAG, "Reusing existing request for $videoId")
                return@withContext existingRequest.await()
            }

            val deferred = CompletableDeferred<Result<PlaybackData>>()
            val previousRequest = activeRequests.putIfAbsent(videoId, deferred)

            if (previousRequest != null && previousRequest.isActive) {
                Log.d(TAG, "Another thread started request for $videoId, waiting...")
                return@withContext previousRequest.await()
            }

            try {
                val result = fetchPlaybackData(videoId)
                deferred.complete(result)

                if (result.isSuccess) {
                    val expiresInSec = result.getOrNull()?.streamExpiresInSeconds ?: 300
                    val ttlMs = minOf(expiresInSec * 1000L - 60_000L, MAX_RESULT_CACHE_TTL_MS).coerceAtLeast(30_000L)
                    resultCache[videoId] = CachedResult(result, System.currentTimeMillis() + ttlMs)
                    Log.d(TAG, "Cached result for $videoId, TTL=${ttlMs / 1000}s")
                }

                result
            } catch (e: Exception) {
                val failure = Result.failure<PlaybackData>(e)
                deferred.complete(failure)
                failure
            } finally {
                activeRequests.remove(videoId, deferred)
            }
        }

    /**
     * Resolves through the video player's client ladder rather than one of its own, so a client GVS
     * has started refusing (see [ClientGateTracker]) is skipped here too and the attested web path
     * takes over. A separate music ladder that ignored those demotions restarted every song on the
     * refused client and stalled each one ~30 s in.
     */
    private fun resolveFailure(videoId: String): IOException {
        val block = InnerTubeVideoStreamExtractor.blockOf(videoId)
        return when {
            InnerTubeVideoStreamExtractor.isGone(videoId) -> VideoUnavailableException(videoId)
            block != null -> IOException("YouTube refused $videoId to this viewer: $block", PlaybackBlockedException(block))
            else -> IOException("Failed to resolve stream for $videoId after trying all clients")
        }
    }

    private suspend fun fetchPlaybackData(videoId: String): Result<PlaybackData> {
        val startTime = System.currentTimeMillis()
        return runCatching {
            val extraction =
                withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                    InnerTubeVideoStreamExtractor.extract(videoId, audioOnly = true)
                        ?: throw resolveFailure(videoId)
                } ?: throw MusicResolveTimeoutException(videoId, RESOLVE_TIMEOUT_MS)
            val format =
                findBestAudioFormat(extraction.audioFormats, loadAudioSelectionPreferences())
                    ?: throw IOException("No playable audio format for $videoId via ${extraction.usedClient.clientName}")
            val streamUrl = format.url ?: throw IOException("Audio format ${format.itag} of $videoId has no url")
            val response = extraction.playerResponse

            val message =
                "music resolve OK $videoId in ${System.currentTimeMillis() - startTime}ms " +
                    "via ${extraction.usedClient.clientName} itag=${format.itag}"
            Log.i(TAG, message)
            PlayerDiagnostics.logInfo(TAG, message)
            PlaybackData(
                audioConfig = response.playerConfig?.audioConfig,
                videoDetails = response.videoDetails,
                playbackTracking = response.playbackTracking,
                format = format,
                streamUrl = streamUrl,
                streamExpiresInSeconds = response.streamingData?.expiresInSeconds ?: 21600,
                usedClient = extraction.usedClient,
                videoFormats = extraction.videoFormats,
                audioFormats = extraction.audioFormats,
            )
        }.onFailure { e ->
            val message =
                "music resolve FAILED $videoId after ${System.currentTimeMillis() - startTime}ms: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, message)
            PlayerDiagnostics.logWarning(TAG, message)
        }
    }

    private fun findBestAudioFormat(
        formats: List<PlayerResponse.StreamingData.Format>,
        audioPreferences: AudioSelectionPreferences,
    ): PlayerResponse.StreamingData.Format? {
        val audioFormats =
            formats
                .filter { format -> format.audioTrack?.isAutoDubbed != true && !format.url.isNullOrEmpty() }
                .preferNonDrc()

        if (audioFormats.isEmpty()) {
            Log.d(TAG, "No audio formats found")
            return null
        }

        val preferredFormats = preferredAudioFormats(audioFormats, audioPreferences.preferredAudioLanguage)

        val bestFormat = selectPreferredMusicFormat(preferredFormats, audioPreferences.musicAudioQuality)

        Log.d(TAG, "Selected format: ${bestFormat?.mimeType}, bitrate: ${bestFormat?.bitrate}")
        return bestFormat
    }

    private fun selectPreferredMusicFormat(
        formats: List<PlayerResponse.StreamingData.Format>,
        preferredMusicAudioQuality: MusicAudioQuality,
    ): PlayerResponse.StreamingData.Format? {
        if (formats.isEmpty()) return null

        val formatsWithKnownBitrate = formats.filter { it.audioBitrate() > 0 }.ifEmpty { formats }
        return when (preferredMusicAudioQuality) {
            MusicAudioQuality.AUTO,
            MusicAudioQuality.HIGH,
            -> formatsWithKnownBitrate.maxByOrNull { it.audioQualityScore() }

            MusicAudioQuality.MEDIUM -> formatsWithKnownBitrate.minByOrNull { abs(it.audioBitrate() - MEDIUM_BITRATE_TARGET) }

            MusicAudioQuality.LOW -> formatsWithKnownBitrate.minByOrNull { it.audioBitrate() }
        }
    }

    private fun PlayerResponse.StreamingData.Format.audioQualityScore(): Int = audioBitrate() + if (mimeType.contains("webm")) 10_240 else 0

    private fun PlayerResponse.StreamingData.Format.audioBitrate(): Int = averageBitrate?.takeIf { it > 0 } ?: bitrate

    internal fun preferredAudioFormats(
        formats: List<PlayerResponse.StreamingData.Format>,
        preferredAudioLanguage: String,
    ): List<PlayerResponse.StreamingData.Format> {
        val normalizedPreference = preferredAudioLanguage.trim().lowercase(Locale.ROOT)

        if (normalizedPreference.isBlank() || normalizedPreference == "original") {
            val originals = formats.filter { it.isOriginal }
            if (originals.isNotEmpty()) return originals
            return formats
        }

        val languageMatches =
            formats.filter { format ->
                AudioStreamSelector.languageMatches(format.audioLanguageTag, normalizedPreference)
            }
        if (languageMatches.isNotEmpty()) return languageMatches

        val originals = formats.filter { it.isOriginal }
        if (originals.isNotEmpty()) return originals

        return formats
    }

    private const val MEDIUM_BITRATE_TARGET = 128_000
}

/** The music resolve outlived its budget; the player is told so rather than left buffering. */
class MusicResolveTimeoutException(
    videoId: String,
    timeoutMs: Long,
) : IOException("Resolving $videoId timed out after ${timeoutMs}ms")
