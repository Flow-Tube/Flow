package io.github.aedev.flow.ui.components.musicplayer.controls

/** The remote's Left and Right on a focused seek bar: the seek-seconds setting per press, three times that while held. */
object DpadSeekStep {
    const val FALLBACK_SECONDS = 10
    private const val HELD_REPEATS = 8
    private const val HELD_MULTIPLIER = 3

    fun stepMs(
        seekSeconds: Int,
        repeatCount: Int,
    ): Long {
        val seconds = if (seekSeconds > 0) seekSeconds else FALLBACK_SECONDS
        val multiplier = if (repeatCount >= HELD_REPEATS) HELD_MULTIPLIER else 1
        return seconds * multiplier * 1_000L
    }

    fun target(
        fromMs: Long,
        direction: Int,
        seekSeconds: Int,
        repeatCount: Int,
        durationMs: Long,
    ): Long = (fromMs + direction * stepMs(seekSeconds, repeatCount)).coerceIn(0L, durationMs.coerceAtLeast(0L))
}
