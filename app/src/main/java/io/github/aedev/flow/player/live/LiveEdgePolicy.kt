package io.github.aedev.flow.player.live

import androidx.media3.common.C

/**
 * Whether live playback is at the live edge: within 20 seconds of the end of the window. YouTube
 * allows 15 seconds, but Media3 snaps its default live position back to the start of a segment, so
 * on 5 second segments with YouTube's 12 second readahead "live" sits 12 to 17 seconds from the
 * window end (16 s measured on a news stream). Measured from the window rather than from Media3's
 * live offset, because Media3 raises its target offset after every rebuffer.
 */
object LiveEdgePolicy {
    const val EDGE_WINDOW_MS = 20_000L

    /** How far the playhead is behind the newest moment the window holds. */
    fun behindLiveMs(
        windowDurationMs: Long,
        positionMs: Long,
    ): Long = if (windowDurationMs <= 0L || windowDurationMs == C.TIME_UNSET) 0L else (windowDurationMs - positionMs).coerceAtLeast(0L)

    fun isAtEdge(
        windowDurationMs: Long,
        positionMs: Long,
    ): Boolean = behindLiveMs(windowDurationMs, positionMs) <= EDGE_WINDOW_MS
}
