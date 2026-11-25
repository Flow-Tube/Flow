package io.github.aedev.flow.player.preload

/**
 * When the next video starts preloading while video plays on screen.
 *
 * Preloading at the first frame cost every video the viewer skipped one extraction and a few
 * seconds of media for a video never played; YouTube's watch page preloads nothing by default.
 * Background and audio-only playback still preload at once, because advancing with the screen off
 * depends on the next window already being in the player.
 *
 * The lead is 60 s, or half of a video shorter than two minutes, so a skip in its first half costs
 * nothing. It never drops below 15 s (the whole video when shorter), which leaves an extraction
 * that walks several clients time to finish before the end.
 */
internal object PreloadStartPolicy {
    const val FOREGROUND_LEAD_MS = 60_000L
    const val MIN_LEAD_MS = 15_000L

    fun leadMs(durationMs: Long): Long = maxOf(minOf(FOREGROUND_LEAD_MS, durationMs / 2), minOf(MIN_LEAD_MS, durationMs))

    /** The position from which a foreground preload may start; 0 when the duration is not known. */
    fun startPositionMs(durationMs: Long): Long = if (durationMs <= 0L) 0L else (durationMs - leadMs(durationMs)).coerceAtLeast(0L)
}
