package io.github.aedev.flow.player.analytics

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData

/**
 * Per-item playback numbers for before/after comparisons: stalls, bytes loaded and the deepest
 * forward buffer reached. Logged when the item changes or the player is released. Debug builds only.
 */
@UnstableApi
internal class PlaybackSessionStats(
    private val tag: String,
    private val bufferedAheadMs: () -> Long,
    private val fallbackId: () -> String? = { null },
) : AnalyticsListener {
    private var mediaId: String? = null
    private var startedAtMs = 0L
    private var bytes = 0L
    private var rebuffers = 0
    private var rebufferMs = 0L
    private var rebufferStartedAtMs = -1L
    private var hasBeenReady = false
    private var maxBufferedAheadMs = 0L

    override fun onMediaItemTransition(
        eventTime: AnalyticsListener.EventTime,
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
        flush()
        mediaId = mediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: fallbackId()
        startedAtMs = SystemClock.elapsedRealtime()
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        bytes += loadEventInfo.bytesLoaded
        maxBufferedAheadMs = maxOf(maxBufferedAheadMs, bufferedAheadMs())
    }

    override fun onPlaybackStateChanged(
        eventTime: AnalyticsListener.EventTime,
        state: Int,
    ) {
        val now = SystemClock.elapsedRealtime()
        when (state) {
            Player.STATE_READY -> {
                if (rebufferStartedAtMs >= 0) rebufferMs += now - rebufferStartedAtMs
                rebufferStartedAtMs = -1
                hasBeenReady = true
            }

            Player.STATE_BUFFERING -> {
                if (hasBeenReady && rebufferStartedAtMs < 0) {
                    rebuffers++
                    rebufferStartedAtMs = now
                }
            }
        }
    }

    override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) {
        flush()
    }

    private fun flush() {
        val id = mediaId
        if (id != null && bytes > 0) {
            val seconds = (SystemClock.elapsedRealtime() - startedAtMs) / 1000
            Log.i(
                tag,
                "$id stats: ${seconds}s rebuffers=$rebuffers rebufferMs=$rebufferMs " +
                    "kb=${bytes / 1024} maxAheadMs=$maxBufferedAheadMs",
            )
        }
        mediaId = null
        bytes = 0
        rebuffers = 0
        rebufferMs = 0
        rebufferStartedAtMs = -1
        hasBeenReady = false
        maxBufferedAheadMs = 0
    }
}
