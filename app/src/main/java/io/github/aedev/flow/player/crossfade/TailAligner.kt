package io.github.aedev.flow.player.crossfade

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.Player
import kotlin.math.abs

/**
 * Lines a muted, buffered tail up with the session player before a song changes hands: starts it at
 * the moment it will be heard level, then nudges its speed until the two positions match. Positions
 * are compared through [PositionAnchor], since a player reports them in coarse steps. A tail lined up early
 * keeps being nudged until the earliest hand-off, so a short fade still ends with the song.
 */
internal class TailAligner(
    private val session: Player,
    private val handler: Handler,
    private val tail: () -> Player?,
    private val onTailStarted: () -> Unit,
    private val onAligned: (deltaMs: Long) -> Unit,
    private val onGaveUp: (reason: String) -> Unit,
) {
    private var earliestHandOffMs = 0L
    private var latestHandOffMs = 0L
    private var tailRetries = 0
    private var sessionAnchor = PositionAnchor()
    private var tailAnchor = PositionAnchor()
    private var alignedReadings = 0
    private var learnedThisAlign = false

    // A started player is heard a moment after play(), which differs by device (about 300 ms on HyperOS),
    // so the tail starts ahead by what the last one turned out to need.
    var headStartMs = CrossfadePolicy.DEFAULT_HEAD_START_MS
        private set

    private val alignStep = Runnable { align() }
    private val startTailStep = Runnable { startTail() }

    fun reset(
        earliestHandOffMs: Long,
        latestHandOffMs: Long,
    ) {
        this.earliestHandOffMs = earliestHandOffMs
        this.latestHandOffMs = latestHandOffMs
        tailRetries = 0
    }

    fun cancel() {
        handler.removeCallbacks(alignStep)
        handler.removeCallbacks(startTailStep)
    }

    /** Starts a buffered tail at the moment it will be heard level with the session player. */
    fun startWhenLinedUp() {
        val player = tail() ?: return
        val sessionMs = session.currentPosition
        if (sessionMs >= latestHandOffMs) {
            onGaveUp("the tail was not buffered in time")
            return
        }
        val aheadByMs = player.currentPosition - sessionMs
        when {
            aheadByMs >= headStartMs -> {
                handler.removeCallbacks(startTailStep)
                val waitMs = ((aheadByMs - headStartMs) / session.playbackParameters.speed.coerceAtLeast(0.1f)).toLong()
                handler.postDelayed(startTailStep, waitMs)
            }

            tailRetries < MAX_TAIL_RETRIES -> {
                // Buffering took longer than the lead it was given, so the session player went past it.
                tailRetries++
                player.seekTo(sessionMs + CrossfadePolicy.LATE_READY_LEAD_MS + headStartMs)
            }

            else -> {
                onGaveUp("the tail kept falling behind")
            }
        }
    }

    private fun startTail() {
        val player = tail() ?: return
        player.play()
        sessionAnchor = PositionAnchor()
        tailAnchor = PositionAnchor()
        alignedReadings = 0
        learnedThisAlign = false
        onTailStarted()
        handler.post(alignStep)
    }

    private fun align() {
        val player = tail() ?: return
        val now = SystemClock.elapsedRealtime()
        val baseSpeed = session.playbackParameters.speed
        val sessionMs = sessionAnchor.estimate(session.currentPosition, now, baseSpeed)
        val remaining = latestHandOffMs - sessionMs
        if (!player.isPlaying) {
            if (remaining <= 0) onGaveUp("the tail did not start") else handler.postDelayed(alignStep, ALIGN_STEP_MS)
            return
        }
        val tailMs = tailAnchor.estimate(player.currentPosition, now, player.playbackParameters.speed)
        if (!sessionAnchor.anchored || !tailAnchor.anchored) {
            if (remaining <= 0) onGaveUp("the positions never moved") else handler.postDelayed(alignStep, ALIGN_STEP_MS)
            return
        }
        val delta = tailMs - sessionMs
        if (!learnedThisAlign) {
            learnedThisAlign = true
            headStartMs = CrossfadePolicy.learnedHeadStartMs(headStartMs, delta)
        }
        alignedReadings = if (CrossfadePolicy.isAligned(delta)) alignedReadings + 1 else 0
        when {
            alignedReadings >= ALIGNED_READINGS && sessionMs >= earliestHandOffMs -> {
                onAligned(delta)
            }

            remaining <= 0 && abs(delta) <= CrossfadePolicy.LATE_ALIGNED_WITHIN_MS -> {
                onAligned(delta)
            }

            remaining <= 0 -> {
                onGaveUp("the tail stayed ${delta}ms apart")
            }

            else -> {
                val speed = CrossfadePolicy.nudgedSpeed(baseSpeed, delta)
                if (abs(speed - player.playbackParameters.speed) >= SPEED_STEP) player.setPlaybackSpeed(speed)
                handler.postDelayed(alignStep, ALIGN_STEP_MS)
            }
        }
    }

    private companion object {
        const val ALIGN_STEP_MS = 10L
        const val ALIGNED_READINGS = 2
        const val SPEED_STEP = 0.005f
        const val MAX_TAIL_RETRIES = 2
    }
}
