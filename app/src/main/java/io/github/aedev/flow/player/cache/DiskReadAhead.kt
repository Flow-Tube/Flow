package io.github.aedev.flow.player.cache

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Writes the playing video ahead into the video cache, per [DiskReadAheadPolicy] (#1696).
 *
 * The streams and bitrates come from the player's own media loads, so whatever source Flow built
 * (generated DASH, the Auto ladder) is followed without knowing how it was built.
 *
 * Playback always comes first: a pass starts only once the player has filled its own buffer and
 * stopped loading, and is cancelled the moment the player loads again, so on a slow connection the
 * writer never takes bandwidth the next seconds of video need. Whatever a cancelled pass wrote stays
 * cached and is skipped next time. No timer: the player's loading changes drive it.
 */
@UnstableApi
internal class DiskReadAhead(
    private val context: Context,
    private val player: ExoPlayer,
    private val cacheManager: PlayerCacheManager,
    private val preferences: PlayerPreferences,
    private val scope: CoroutineScope,
) : AnalyticsListener {
    /** One stream the player reads: the load it was last seen in, as a template for writing more. */
    private class Stream(
        val spec: DataSpec,
        val bitrate: Int,
    ) {
        /** googlevideo URLs name their own length, which keeps a write from asking past the end. */
        val contentLength: Long? = spec.uri.getQueryParameter("clen")?.toLongOrNull()

        fun sameAs(other: DataSpec): Boolean = spec.uri == other.uri && spec.key == other.key
    }

    private val streams = mutableMapOf<Int, Stream>()
    private var job: Job? = null

    @Volatile private var coveredUntilMs = 0L

    @Volatile private var writtenBytes = 0L

    @Volatile private var retryAfterPositionMs = 0L

    override fun onLoadStarted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        retryCount: Int,
    ) {
        if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return
        val trackType = mediaLoadData.trackType
        if (trackType != C.TRACK_TYPE_VIDEO && trackType != C.TRACK_TYPE_AUDIO) return
        val spec = loadEventInfo.dataSpec
        if (!DiskReadAheadPolicy.isWritableStream(spec.uri.scheme, spec.uri.host)) return
        val bitrate = mediaLoadData.trackFormat?.bitrate ?: return
        if (bitrate <= 0) return

        if (streams[trackType]?.sameAs(spec) != true) {
            // A new rung or a reloaded stream starts its coverage over; the budget stays per video.
            streams[trackType] = Stream(spec, bitrate)
            cancel()
            coveredUntilMs = 0L
            retryAfterPositionMs = 0L
        }
    }

    override fun onIsLoadingChanged(
        eventTime: AnalyticsListener.EventTime,
        isLoading: Boolean,
    ) {
        if (isLoading) cancel() else maybeStart()
    }

    override fun onMediaItemTransition(
        eventTime: AnalyticsListener.EventTime,
        mediaItem: MediaItem?,
        reason: Int,
    ) = reset()

    override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) = reset()

    fun release() = reset()

    private fun reset() {
        cancel()
        streams.clear()
        coveredUntilMs = 0L
        writtenBytes = 0L
        retryAfterPositionMs = 0L
    }

    private fun cancel() {
        job?.cancel()
        job = null
    }

    private fun maybeStart() {
        if (job?.isActive == true || player.isLoading || player.isCurrentMediaItemLive || streams.isEmpty()) return
        val positionMs = player.currentPosition
        if (positionMs < retryAfterPositionMs) return
        val bufferedMs = player.bufferedPosition
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val targets = streams.values.toList()
        job =
            scope.launch(PerformanceDispatcher.networkIO) {
                val minutes = preferences.diskReadAheadMinutes.first()
                if (!DiskReadAheadPolicy.needsTopUp(minutes, positionMs, coveredUntilMs, durationMs)) return@launch
                if (preferences.diskReadAheadUnmeteredOnly.first() && isMetered()) {
                    retryAfterPositionMs = positionMs + DiskReadAheadPolicy.STEP_MS
                    return@launch
                }
                val window = DiskReadAheadPolicy.plan(minutes, positionMs, bufferedMs, durationMs) ?: return@launch
                Log.d(TAG, "Read-ahead from ${window.first / 1000}s to ${(window.last + 1) / 1000}s")
                write(targets, window, positionMs)
            }
    }

    private suspend fun write(
        targets: List<Stream>,
        window: LongRange,
        positionMs: Long,
    ) {
        val budget = DiskReadAheadPolicy.budgetBytes(PlayerCacheManager.configuredLimits(context).videoBytes)
        val source = cacheManager.createWritingDataSource() ?: return
        var stepStart = window.first
        try {
            while (stepStart <= window.last) {
                val stepEnd = minOf(stepStart + DiskReadAheadPolicy.STEP_MS, window.last + 1)
                for (stream in targets) {
                    val range = DiskReadAheadPolicy.byteRange(stepStart, stepEnd, stream.bitrate, stream.contentLength) ?: continue
                    val length = range.last - range.first + 1
                    if (writtenBytes + length > budget) {
                        Log.i(TAG, "Read-ahead stopped at half the cache size")
                        return
                    }
                    currentCoroutineContext().ensureActive()
                    val writer =
                        CacheWriter(
                            source,
                            stream.spec
                                .buildUpon()
                                .setPosition(range.first)
                                .setLength(length)
                                .build(),
                            null,
                            null,
                        )
                    val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { writer.cancel() }
                    try {
                        writer.cache()
                    } finally {
                        handle?.dispose()
                    }
                    writtenBytes += length
                }
                coveredUntilMs = stepEnd
                stepStart = stepEnd
            }
            Log.d(TAG, "Read-ahead covers ${coveredUntilMs / 1000}s, ${writtenBytes / BYTES_PER_MB} MB this video")
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode == HTTP_RANGE_NOT_SATISFIABLE) {
                coveredUntilMs = Long.MAX_VALUE
            } else {
                backOff(positionMs, e)
            }
        } catch (e: IOException) {
            // A cancel interrupts the write with an IOException of its own; that is no failure.
            if (currentCoroutineContext().isActive) backOff(positionMs, e)
        }
    }

    private fun backOff(
        positionMs: Long,
        error: IOException,
    ) {
        retryAfterPositionMs = positionMs + DiskReadAheadPolicy.STEP_MS
        Log.w(TAG, "Read-ahead paused: ${error::class.simpleName}: ${error.message}")
    }

    private fun isMetered(): Boolean = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered != false

    private companion object {
        const val TAG = "DiskReadAhead"
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val BYTES_PER_MB = 1024 * 1024
    }
}
