package io.github.aedev.flow.player.live

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.datasource.YouTubeHttpDataSource
import io.github.aedev.flow.player.error.PlayerDiagnostics
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor
import io.github.aedev.flow.player.stream.VideoCodecUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/**
 * The live streams Flow plays from their segments, by video id.
 *
 * A live player response lists every format with a direct URL, and googlevideo serves any segment of
 * the last 12 hours from it by number (`&sq=N`). A session is opened from one response and one
 * request for the newest segment's headers, then serves Media3 a manifest built from them, so the
 * whole DVR window plays with no manifest traffic at all.
 */
@OptIn(UnstableApi::class)
object LiveSegmentSessions {
    private const val TAG = "LiveSegmentSessions"
    private const val MAX_SESSIONS = 3
    private const val PROBE_RANGE_BYTES = 1L

    private val sessions = LinkedHashMap<String, LiveSegmentSession>()
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isSessionUri(uri: String?): Boolean = uri?.startsWith("${LiveSegmentSession.SCHEME}://") == true

    /** Whether [videoId] was played from segments and its broadcast has since ended. */
    @Synchronized
    fun hasEnded(videoId: String?): Boolean = videoId != null && sessions[videoId]?.hasEnded == true

    @Synchronized
    fun forUri(uri: Uri): LiveSegmentSession? {
        if (uri.scheme != LiveSegmentSession.SCHEME) return null
        val videoId = uri.pathSegments.firstOrNull() ?: return null
        return sessions[videoId]
    }

    @Synchronized
    fun forVideo(videoId: String?): LiveSegmentSession? = videoId?.let { sessions[it] }

    @Synchronized
    internal fun register(session: LiveSegmentSession) {
        sessions.remove(session.videoId)
        sessions[session.videoId] = session
        while (sessions.size > MAX_SESSIONS) sessions.remove(sessions.keys.first())
    }

    /**
     * Opens a session for a live [response] and returns its manifest URI, or null when the response
     * has no segment formats or the head cannot be read; the caller then plays the HLS manifest.
     */
    suspend fun open(
        videoId: String,
        response: PlayerResponse,
    ): String? {
        if (response.videoDetails?.isLive != true) return null
        val window = LiveWindow.of(response) ?: return null
        val candidates = segmentFormats(response)
        if (candidates.none { it.first.isAudio } || candidates.none { !it.first.isAudio }) return null
        val targetDurationMs = window.segmentDurationMs
        val readaheadMs = window.readaheadMs
        val probeUrl = candidates.filter { it.first.isAudio }.minBy { it.first.bitrate }.second
        val deviceBefore = System.currentTimeMillis()
        val head = probeHead(probeUrl) ?: return null
        val deviceAfter = System.currentTimeMillis()
        val serverOffsetMs = head.serverTimeMs - (deviceBefore + deviceAfter) / 2
        val windowMs =
            if (window.isDvrEnabled && window.maxDvrDurationMs > 0) window.maxDvrDurationMs else readaheadMs + 4 * targetDurationMs
        val session =
            LiveSegmentSession(
                videoId = videoId,
                formats = candidates.map { it.first },
                urls = candidates.associate { it.first.itag to it.second },
                clock = LiveSegmentClock(head, targetDurationMs, windowMs),
                readaheadMs = readaheadMs,
                serverClockOffsetMs = serverOffsetMs,
                scope = refreshScope,
                refreshUrls = { refreshedUrls(videoId) },
                isStillLive = {
                    InnerTubeVideoStreamExtractor.extract(videoId)?.let {
                        it.isLive ||
                            it.playerResponse.videoDetails?.isLive == true
                    }
                },
            )
        register(session)
        PlayerDiagnostics.logWarning(
            TAG,
            "live segments $videoId head=${head.sequence} seg=${session.clock.segmentDurationUs}us " +
                "window=${session.clock.timeShiftBufferDepthMs / 1_000}s readahead=${readaheadMs}ms " +
                "formats=${candidates.joinToString(",") { it.first.itag.toString() }} probe=${deviceAfter - deviceBefore}ms",
        )
        return session.manifestUri
    }

    internal fun segmentFormats(response: PlayerResponse): List<Pair<LiveSegmentFormat, String>> =
        response.streamingData
            ?.adaptiveFormats
            .orEmpty()
            .mapNotNull { format ->
                val url = format.url?.takeIf { it.isNotBlank() && !it.contains("&n=") } ?: return@mapNotNull null
                if (format.targetDurationSec == null) return@mapNotNull null
                val container = format.mimeType.substringBefore(';').trim()
                if (container != "video/mp4" && container != "audio/mp4") return@mapNotNull null
                val codecs = VideoCodecUtils.codecStringFromMimeType(format.mimeType).ifBlank { return@mapNotNull null }
                LiveSegmentFormat(
                    itag = format.itag,
                    containerMimeType = container,
                    codecs = codecs,
                    bitrate = format.bitrate,
                    width = format.width,
                    height = format.height,
                    frameRate = format.fps,
                    channels = format.audioChannels,
                    sampleRate = format.audioSampleRate,
                ) to url
            }

    private suspend fun refreshedUrls(videoId: String): Map<Int, String>? {
        val result = InnerTubeVideoStreamExtractor.extract(videoId) ?: return null
        val urls = segmentFormats(result.playerResponse).associate { it.first.itag to it.second }
        PlayerDiagnostics.logWarning(TAG, "live URLs refreshed for $videoId via ${result.usedClient.clientName}: ${urls.size} formats")
        return urls
    }

    private suspend fun probeHead(url: String): LiveHead? =
        withContext(Dispatchers.IO) {
            val source: HttpDataSource = YouTubeHttpDataSource.Factory().createDataSource()
            try {
                source.open(
                    DataSpec
                        .Builder()
                        .setUri(url)
                        .setLength(PROBE_RANGE_BYTES)
                        .build(),
                )
                LiveHeadHeaders.parse(source.responseHeaders)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Live head probe failed", e)
                PlayerDiagnostics.logWarning(TAG, "live head probe failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            } finally {
                runCatching { source.close() }
            }
        }
}
