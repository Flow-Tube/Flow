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
    const val ALIGN_WINDOW_MS = 2_000L

    /** Positions within this of each other are the same moment of the song, so the hand-off is inaudible. */
    const val ALIGNED_WITHIN_MS = 10L

    /** The furthest the muted tail's speed moves from the session's while it catches up. */
    private const val MAX_NUDGE = 0.08f

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
     * down, one behind speeds up, by what closes the gap in about half of [remainingMs].
     */
    fun nudgedSpeed(
        baseSpeed: Float,
        deltaMs: Long,
        remainingMs: Long,
    ): Float {
        if (abs(deltaMs) <= ALIGNED_WITHIN_MS) return baseSpeed
        val window = (remainingMs / 2).coerceAtLeast(1L).toFloat()
        val correction = (-deltaMs / window).coerceIn(-MAX_NUDGE, MAX_NUDGE)
        return baseSpeed * (1f + correction)
    }

    fun isAligned(deltaMs: Long): Boolean = abs(deltaMs) <= ALIGNED_WITHIN_MS
}
