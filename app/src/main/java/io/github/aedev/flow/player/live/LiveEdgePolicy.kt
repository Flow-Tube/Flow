package io.github.aedev.flow.player.live

import androidx.media3.common.C

/**
 * Whether live playback is at the live edge, by YouTube's rule: within 15 seconds of the end of the
 * window. Measured from the window rather than from Media3's live offset, because Media3 raises its
 * target offset after every rebuffer, so a player sitting at its own default position would
 * otherwise drift out of "live" after a couple of stalls.
 */
object LiveEdgePolicy {
    const val EDGE_WINDOW_MS = 15_000L

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
