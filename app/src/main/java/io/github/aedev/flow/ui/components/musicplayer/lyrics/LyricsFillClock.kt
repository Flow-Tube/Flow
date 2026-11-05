package io.github.aedev.flow.ui.components.musicplayer.lyrics

import kotlin.math.abs

private const val SEEK_MS = 500.0
private const val MAX_CORRECTION = 0.5

/**
 * The position the word fill draws at. The player's own position gets corrected every few seconds,
 * by up to about 230 ms backwards, and a fill following it would visibly jump back on a long word.
 * This clock only moves forward and works a correction off by running slower or faster for a moment.
 * A jump too large to be a correction is a seek, and goes straight to the player's position.
 */
internal class LyricsFillClock {
    private var shownMs = Double.NaN
    private var lastFrameMs = 0L

    fun next(
        playerPositionMs: Long,
        frameTimeMs: Long,
        speed: Float,
    ): Long {
        val elapsedMs = (frameTimeMs - lastFrameMs).coerceAtLeast(0L) * speed.toDouble()
        val predictedMs = shownMs + elapsedMs
        val errorMs = playerPositionMs - predictedMs
        shownMs =
            if (shownMs.isNaN() || abs(errorMs) >= SEEK_MS) {
                playerPositionMs.toDouble()
            } else {
                predictedMs + errorMs.coerceIn(-elapsedMs * MAX_CORRECTION, elapsedMs * MAX_CORRECTION)
            }
        lastFrameMs = frameTimeMs
        return shownMs.toLong()
    }
}
