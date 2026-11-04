package io.github.aedev.flow.notification

import java.util.concurrent.TimeUnit

/**
 * How long Flow has been on screen since the last break. Times are elapsed realtime, so a clock change can't move it.
 * A short look away keeps the count; [breakLengthMs] or more away is the break, and the count starts over.
 */
internal class BreakClock(
    private val breakLengthMs: Long = DEFAULT_BREAK_LENGTH_MS,
) {
    private var countedMs = 0L
    private var shownAtMs: Long? = null
    private var hiddenAtMs: Long? = null

    fun onShown(nowMs: Long) {
        if (shownAtMs != null) return
        hiddenAtMs?.let { if (nowMs - it >= breakLengthMs) countedMs = 0L }
        hiddenAtMs = null
        shownAtMs = nowMs
    }

    fun onHidden(nowMs: Long) {
        val shownAt = shownAtMs ?: return
        countedMs += nowMs - shownAt
        shownAtMs = null
        hiddenAtMs = nowMs
    }

    /** Starts the count over from [nowMs]; Flow is on screen when this is called. */
    fun restart(nowMs: Long) {
        countedMs = 0L
        hiddenAtMs = null
        shownAtMs = nowMs
    }

    fun remainingMs(
        frequencyMs: Long,
        nowMs: Long,
    ): Long {
        val counted = countedMs + (shownAtMs?.let { nowMs - it } ?: 0L)
        return (frequencyMs - counted).coerceAtLeast(0L)
    }

    private companion object {
        val DEFAULT_BREAK_LENGTH_MS = TimeUnit.MINUTES.toMillis(5)
    }
}
