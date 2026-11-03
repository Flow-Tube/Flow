package io.github.aedev.flow.player.crossfade

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** What decides whether, when and how a song blends into the next one (#1675). */
internal object CrossfadePolicy {
    const val MAX_SECONDS = 12

    /** The tail player is built and buffered this long before it has to play. */
    const val PREPARE_LEAD_MS = 8_000L

    /** The tail starts muted this long before the fade, to line its position up with the session player's. */
    const val ALIGN_WINDOW_MS = 3_000L

    /** Positions within this of each other are the same moment of the song, so the hand-off is inaudible. */
    const val ALIGNED_WITHIN_MS = 15L

    /** At the fade point a tail this close still takes over: a jump this small is not heard in music. */
    const val LATE_ALIGNED_WITHIN_MS = 30L

    /** The furthest the muted tail's speed moves from the session's while it catches up. */
    private const val MAX_NUDGE = 0.15f

    /** The nudge closes the gap over about this long; a fixed horizon keeps it from overshooting. */
    private const val NUDGE_HORIZON_MS = 700f

    /** Whatever a song needs left to play besides the fade, so a fade never starts before it can line up. */
    private const val MIN_REST_MS = PREPARE_LEAD_MS + ALIGN_WINDOW_MS

    /** The facts one decision needs; the service reads them from the player and the queue. */
    data class Moment(
        val fadeSeconds: Int,
        val playing: Boolean,
        val hasNext: Boolean,
        val repeatsOne: Boolean,
        val durationMs: Long,
        val speed: Float,
        val showsVideo: Boolean,
        val pausesAtEndOfSong: Boolean,
        val nextIsSameAlbum: Boolean,
        val keepAlbumsGapless: Boolean,
    )

    /** The fade's length in media time: a fade lasts the chosen seconds on the clock at any speed. */
    fun fadeMediaMs(
        fadeSeconds: Int,
        speed: Float,
    ): Long = (fadeSeconds.coerceIn(0, MAX_SECONDS) * 1_000L * speed.coerceAtLeast(0.1f)).toLong()

    /** The position the fade starts at, or null when this song should end as it does today. */
    fun fadeStartMs(moment: Moment): Long? {
        if (moment.fadeSeconds <= 0 || !moment.playing || !moment.hasNext || moment.repeatsOne) return null
        if (moment.showsVideo || moment.pausesAtEndOfSong) return null
        if (moment.keepAlbumsGapless && moment.nextIsSameAlbum) return null
        if (moment.durationMs <= 0) return null
        val fadeMs = fadeMediaMs(moment.fadeSeconds, moment.speed)
        if (moment.durationMs < fadeMs + MIN_REST_MS) return null
        return moment.durationMs - fadeMs
    }

    /** The outgoing song's gain [progress] of the way through the fade; equal power, so the blend keeps its loudness. */
    fun fadeOutGain(progress: Float): Float = cos(progress.coerceIn(0f, 1f) * PI / 2).toFloat()

    /** The incoming song's gain [progress] of the way through the fade. */
    fun fadeInGain(progress: Float): Float = sin(progress.coerceIn(0f, 1f) * PI / 2).toFloat()

    /**
     * The muted tail's speed while it lines up: a tail [deltaMs] ahead of the session player slows
     * down, one behind speeds up, by what closes the gap over [NUDGE_HORIZON_MS].
     */
    fun nudgedSpeed(
        baseSpeed: Float,
        deltaMs: Long,
    ): Float {
        if (abs(deltaMs) <= ALIGNED_WITHIN_MS) return baseSpeed
        val correction = (-deltaMs / NUDGE_HORIZON_MS).coerceIn(-MAX_NUDGE, MAX_NUDGE)
        return baseSpeed * (1f + correction)
    }

    fun isAligned(deltaMs: Long): Boolean = abs(deltaMs) <= ALIGNED_WITHIN_MS

    const val DEFAULT_HEAD_START_MS = 250L
    private const val MIN_HEAD_START_MS = 0L
    private const val MAX_HEAD_START_MS = 800L

    /**
     * How far ahead the next tail starts, learned from this one: a tail that started [deltaMs] behind
     * the session player with [headStartMs] needed that much more. The delay varies by 150 ms or so
     * between starts on one phone, so the head start moves half the way.
     */
    fun learnedHeadStartMs(
        headStartMs: Long,
        deltaMs: Long,
    ): Long = (headStartMs - deltaMs / 2).coerceIn(MIN_HEAD_START_MS, MAX_HEAD_START_MS)
}

/**
 * A player position read every few milliseconds. A player refreshes the position it reports in coarse
 * steps (about every 250 ms on some phones), so the reading is extended from the moment it last moved;
 * until it has moved once, that moment is unknown and the estimate is not [anchored].
 */
internal class PositionAnchor {
    private var lastMs = Long.MIN_VALUE
    private var movedAtMs = 0L

    var anchored = false
        private set

    fun estimate(
        positionMs: Long,
        nowMs: Long,
        speed: Float,
    ): Long {
        if (positionMs != lastMs) {
            if (lastMs != Long.MIN_VALUE) anchored = true
            lastMs = positionMs
            movedAtMs = nowMs
        }
        return lastMs + ((nowMs - movedAtMs) * speed).toLong()
    }
}
