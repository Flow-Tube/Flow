package io.github.aedev.flow.player

import androidx.media3.common.C

object PlaybackResumePolicy {
    fun resolveStartPosition(
        savedPosition: Long,
        durationMs: Long,
        resumeAllowed: Boolean,
    ): Long {
        if (!resumeAllowed || savedPosition <= 500L) return 0L
        return savedPosition.takeUnless {
            shouldRestartCompletedPlayback(savedPosition, durationMs)
        } ?: 0L
    }

    fun shouldRestartCompletedPlayback(
        savedPosition: Long,
        durationMs: Long,
    ): Boolean {
        if (savedPosition <= 0L) return false
        if (durationMs > 0L) {
            val remainingMs = durationMs - savedPosition
            return remainingMs <= 1_500L || savedPosition >= (durationMs * 0.98f).toLong()
        }
        return savedPosition > 4 * 60 * 60 * 1000L
    }

    /**
     * The duration to record as finished when the player moves on by itself to the next item, or
     * null when the item it left was not at its end. [windowDurationMs] may be [C.TIME_UNSET].
     */
    fun finishedDurationOnAdvance(
        windowDurationMs: Long,
        endPositionMs: Long,
    ): Long? {
        val durationMs = windowDurationMs.takeIf { it > 0L && it != C.TIME_UNSET } ?: return null
        return durationMs.takeIf { shouldRestartCompletedPlayback(endPositionMs, it) }
    }
}
