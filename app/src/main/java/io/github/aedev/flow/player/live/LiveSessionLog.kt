package io.github.aedev.flow.player.live

import android.os.SystemClock
import android.util.Log
import io.github.aedev.flow.player.error.PlayerDiagnostics

/**
 * One diagnostics line when a live stream starts and one a minute while it plays: where it plays
 * from, how long the window is, how far behind live, how often it stalled, and the manifest and
 * media traffic of that minute. This is what live reports are measured against.
 */
class LiveSessionLog(
    private val traffic: () -> LiveTraffic,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val emit: (String) -> Unit = { line ->
        Log.i(TAG, line)
        PlayerDiagnostics.logInfo(TAG, line)
    },
) {
    private var videoId: String? = null
    private var source = ""
    private var startedAtMs = 0L
    private var lastLineAtMs = 0L
    private var lastTraffic = LiveTraffic()
    private var stalls = 0
    private var stallsAtLastLine = 0
    private var hasPlayed = false
    private var lastSeekAtMs = Long.MIN_VALUE

    fun start(
        videoId: String,
        source: String,
    ) {
        this.videoId = videoId
        this.source = source
        startedAtMs = now()
        lastLineAtMs = startedAtMs
        lastTraffic = traffic()
        stalls = 0
        stallsAtLastLine = 0
        hasPlayed = false
        lastSeekAtMs = Long.MIN_VALUE
        emit("live start $videoId source=$source")
    }

    fun stop() {
        videoId = null
    }

    fun onReady() {
        hasPlayed = true
    }

    fun onSeek() {
        lastSeekAtMs = now()
    }

    /** Buffering after playback started counts as a stall, unless a seek just caused it. */
    fun onBuffering() {
        if (videoId == null || !hasPlayed) return
        if (lastSeekAtMs != Long.MIN_VALUE && now() - lastSeekAtMs < SEEK_GRACE_MS) return
        stalls++
    }

    fun tick(
        windowMs: Long,
        behindLiveMs: Long,
    ) {
        val id = videoId ?: return
        val at = now()
        if (at - lastLineAtMs < LINE_INTERVAL_MS) return
        val current = traffic()
        val minute = current - lastTraffic
        emit(
            "live $id source=$source t=${(at - startedAtMs) / 1_000}s window=${windowMs / 1_000}s " +
                "behind=${behindLiveMs / 1_000}s stalls=${stalls - stallsAtLastLine}/$stalls " +
                "manifest=${minute.manifestRequests}req/${minute.manifestBytes / 1_024}KB " +
                "media=${minute.mediaRequests}req/${minute.mediaBytes / 1_024}KB",
        )
        lastLineAtMs = at
        lastTraffic = current
        stallsAtLastLine = stalls
    }

    private companion object {
        const val TAG = "LiveSession"
        const val LINE_INTERVAL_MS = 60_000L
        const val SEEK_GRACE_MS = 5_000L
    }
}
