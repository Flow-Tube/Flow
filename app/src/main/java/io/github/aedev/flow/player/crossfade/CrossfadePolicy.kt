package io.github.aedev.flow.player.crossfade

import io.github.aedev.flow.data.local.CrossfadeCurve
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** What decides whether, when and how a song blends into the next one (#1675). */
internal object CrossfadePolicy {
    const val MAX_MS = 12_000L

    /** Every length settings offer: short blends under a second, then whole seconds. */
    val DURATIONS_MS: List<Long> = listOf(250L, 500L, 750L) + (1..12).map { it * 1_000L }

    /** The tail player is built and buffered this long before it has to play. */
    const val PREPARE_LEAD_MS = 8_000L

    /** The tail starts muted this long before the fade, to line its position up with the session player's. */
    const val ALIGN_WINDOW_MS = 3_000L

    /** After a seek close to the end, the tail gets this long to buffer before it has to start. */
    const val LATE_READY_LEAD_MS = 1_500L

    /** A fade shorter than this is not worth it; closer to the end, the song just ends. */
    private const val MIN_FADE_MS = 2_000L

    /** With no fade asked for, a skip overlaps the two songs this long: it hides the change without sounding like a fade. */
    const val SKIP_BLEND_MS = 250L

    /** A song skipped sooner than this was barely heard, so there is nothing to fade out. */
    private const val MIN_SKIP_PLAYED_MS = 1_000L

    /** The least time a tail needs to line up; a tail starting with less would only be dropped. */
    private const val MIN_LINE_UP_MS = 1_000L

    /** Positions within this of each other are the same moment of the song, so the hand-off is inaudible. */
    const val ALIGNED_WITHIN_MS = 15L

    /** At the fade point a tail this close still takes over: a jump this small is not heard in music. */
    const val LATE_ALIGNED_WITHIN_MS = 30L

    /** The furthest the muted tail's speed moves from the session's while it catches up. */
    private const val MAX_NUDGE = 0.15f

    /** The nudge closes the gap over about this long; a fixed horizon keeps it from overshooting. */
    private const val NUDGE_HORIZON_MS = 700f

    /** A song this close to its end has too little left to fade out, so only the next song fades in. */
    private const val MIN_FADE_THROUGH_MS = 300L

    /** With no old song to blend with, the next one still comes in over this long instead of at full volume. */
    const val QUICK_FADE_IN_MS = 400L

    /** A lined-up tail takes over no sooner than this before the fade, so a short fade still ends with the song. */
    private const val HAND_OFF_EARLY_MS = 500L

    /** How long a muted song eases out before the player leaves it, so it doesn't stop mid-word. */
    const val EASE_OUT_MS = 150L

    /** What the user chose in settings; [fadeMs] of 0 is off. */
    data class Settings(
        val fadeMs: Long = 0L,
        val keepAlbumsGapless: Boolean = true,
        val curve: CrossfadeCurve = CrossfadeCurve.EQUAL_POWER,
        val onAutoAdvance: Boolean = true,
        val onManualSkip: Boolean = true,
    ) {
        val fadesOnSkip: Boolean get() = fadeMs > 0 && onManualSkip
    }

    /** The fade length the settings resolve to: [chosenMs] while crossfade is on, otherwise 0. */
    fun chosenFadeMs(
        enabled: Boolean,
        chosenMs: Int,
    ): Long = if (enabled) chosenMs.toLong().coerceIn(DURATIONS_MS.first(), MAX_MS) else 0L

    /** Where an in-app skip finds the outgoing song. */
    enum class SkipPhase {
        /** Nothing carries it but the session. */
        NONE,

        /** The session still plays it while its tail starts, muted, and the skip waits for that. */
        HOLDING,

        /** A tail has it, or is about to, and the session has moved on. */
        UNDER_TAIL,
    }

    enum class SkipPress { START_TAIL, PLAIN, MOVE_TARGET, BACK_TO_PLAYING_SONG, SEEK_UNDER_TAIL }

    /**
     * What another press does. Only the first press of a run builds a tail: later ones just change
     * where the session goes, so no press is lost and no second tail starts.
     */
    fun onSkipPress(
        phase: SkipPhase,
        targetIsPlayingSong: Boolean,
        canStartTail: Boolean,
    ): SkipPress =
        when (phase) {
            SkipPhase.NONE -> if (canStartTail) SkipPress.START_TAIL else SkipPress.PLAIN
            SkipPhase.HOLDING -> if (targetIsPlayingSong) SkipPress.BACK_TO_PLAYING_SONG else SkipPress.MOVE_TARGET
            SkipPhase.UNDER_TAIL -> SkipPress.SEEK_UNDER_TAIL
        }

    /** When a fade began, for it to go on from [progress]; the next song may have kept it waiting. */
    fun fadeBeganAtMs(
        nowMs: Long,
        progress: Float,
        fadeClockMs: Long,
    ): Long = nowMs - (progress * fadeClockMs).toLong()

    /** The facts one decision needs; the service reads them from the player and the queue. */
    data class Moment(
        val fadeMs: Long,
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

    /** The fade's length in media time: a fade lasts the chosen length on the clock at any speed. */
    fun fadeMediaMs(
        fadeMs: Long,
        speed: Float,
    ): Long = (fadeMs.coerceIn(0L, MAX_MS) * speed.coerceAtLeast(0.1f)).toLong()

    /**
     * The position the fade starts at, or null when this song should end as it does today. With Repeat one
     * on, the song fades into its own start.
     */
    fun fadeStartMs(moment: Moment): Long? {
        if (moment.fadeMs <= 0 || !moment.playing || !(moment.hasNext || moment.repeatsOne)) return null
        if (moment.showsVideo || moment.pausesAtEndOfSong) return null
        if (!moment.repeatsOne && moment.keepAlbumsGapless && moment.nextIsSameAlbum) return null
        if (moment.durationMs <= 0) return null
        val fadeMs = fadeMediaMs(moment.fadeMs, moment.speed)
        if (moment.durationMs < fadeMs + (MIN_FADE_THROUGH_MS * moment.speed).toLong()) return null
        return moment.durationMs - fadeMs
    }

    /** How a song that no tail can carry still fades: wait [holdMs], fade the session's own song over [outMs]. */
    data class FadeThrough(
        val holdMs: Long,
        val outMs: Long,
    )

    /**
     * The fade a song gets when its tail isn't ready or can't line up in time: the session player fades its
     * own song out over what is left of the fade, and the next song comes in over [QUICK_FADE_IN_MS].
     */
    fun fadeThrough(
        positionMs: Long,
        durationMs: Long,
        speed: Float,
        fadeMs: Long,
    ): FadeThrough {
        val leftOnClock = ((durationMs - positionMs) / speed.coerceAtLeast(0.1f)).toLong().coerceAtLeast(0L)
        if (leftOnClock < MIN_FADE_THROUGH_MS) return FadeThrough(holdMs = leftOnClock, outMs = 0L)
        val outMs = minOf(fadeMs.coerceIn(0L, MAX_MS), leftOnClock)
        return FadeThrough(holdMs = leftOnClock - outMs, outMs = outMs)
    }

    /**
     * Where a song the user skipped away from comes back to fade under the next one, or null when it
     * should simply stop: skipped in its first moments, too near its end, or it stopped by failing.
     */
    fun skipResumeMs(
        positionMs: Long,
        leadMs: Long,
        durationMs: Long?,
        failed: Boolean,
    ): Long? {
        if (durationMs == null || failed || positionMs < MIN_SKIP_PLAYED_MS) return null
        val resumeAt = positionMs + leadMs
        return resumeAt.takeIf { it + MIN_FADE_MS <= durationMs }
    }

    /** The last position a song can change hands at and still have a fade worth hearing, or all of a shorter one. */
    fun latestHandOffMs(
        durationMs: Long,
        speed: Float,
        fadeMs: Long,
    ): Long = durationMs - (minOf(MIN_FADE_MS, fadeMs.coerceAtLeast(1L)) * speed).toLong()

    /** The first position a lined-up tail may take the song over at: just before the fade, never seconds early. */
    fun earliestHandOffMs(
        fadeStartMs: Long,
        speed: Float,
    ): Long = fadeStartMs - (HAND_OFF_EARLY_MS * speed).toLong()

    /**
     * The position the tail starts lining up at: the usual one before [fadeStartMs], or later when a
     * seek left too little time to buffer. Null when even that leaves no room for a fade.
     */
    fun alignStartMs(
        fadeStartMs: Long,
        positionMs: Long,
        durationMs: Long,
        speed: Float,
        fadeMs: Long,
    ): Long? {
        val start = maxOf(fadeStartMs - ALIGN_WINDOW_MS, positionMs + LATE_READY_LEAD_MS)
        return start.takeIf { it + MIN_LINE_UP_MS <= latestHandOffMs(durationMs, speed, fadeMs) }
    }

    /** How long the outgoing song fades on the clock once a tail has it: until the song ends, so it never stops early. */
    fun fadeOutClockMs(
        positionMs: Long,
        durationMs: Long,
        speed: Float,
    ): Long = ((durationMs - positionMs) / speed.coerceAtLeast(0.1f)).toLong().coerceAtLeast(1L)

    /** How far through its own fade a song is, [elapsedMs] after its clock started; 0 while it hasn't. */
    fun progress(
        elapsedMs: Long?,
        clockMs: Long,
    ): Float = if (elapsedMs == null) 0f else (elapsedMs.toFloat() / clockMs.coerceAtLeast(1L)).coerceIn(0f, 1f)

    /** How long a fade lasts on the clock: the chosen length, or what is left of the song after a late seek. */
    fun fadeClockMs(
        fadeMs: Long,
        positionMs: Long,
        durationMs: Long,
        speed: Float,
    ): Long {
        val leftOnClock = ((durationMs - positionMs) / speed.coerceAtLeast(0.1f)).toLong()
        return minOf(fadeMs.coerceIn(0L, MAX_MS), leftOnClock).coerceAtLeast(1L)
    }

    /** How long a skip overlaps the two songs on the clock: the chosen fade, or just [SKIP_BLEND_MS] without one. */
    fun skipClockMs(
        settings: Settings,
        positionMs: Long,
        durationMs: Long,
        speed: Float,
    ): Long {
        val chosen = fadeClockMs(settings.fadeMs, positionMs, durationMs, speed)
        if (settings.fadesOnSkip) return chosen
        val leftOnClock = ((durationMs - positionMs) / speed.coerceAtLeast(0.1f)).toLong()
        return minOf(SKIP_BLEND_MS, leftOnClock).coerceAtLeast(1L)
    }

    /**
     * A tail heard for moments, or after the song has already gone quiet, starts sooner from a sync point
     * at the cost of a small jump. One carrying a whole fade straight on from the session starts exactly.
     */
    fun tailStartsAtSyncPoint(
        settings: Settings,
        sessionStillPlaysSong: Boolean,
    ): Boolean = !settings.fadesOnSkip || !sessionStillPlaysSong

    /**
     * The outgoing song's gain [progress] of the way through the fade. Equal power and Smooth are pairs;
     * Hold and Gentle shape only the outgoing song, which leaves later or sooner over a steady incoming one.
     */
    fun fadeOutGain(
        progress: Float,
        curve: CrossfadeCurve = CrossfadeCurve.EQUAL_POWER,
    ): Float {
        val p = progress.coerceIn(0f, 1f)
        return when (curve) {
            CrossfadeCurve.EQUAL_POWER -> cos(p * PI / 2).toFloat()
            CrossfadeCurve.S_CURVE -> 1f - smoothstep(p)
            CrossfadeCurve.HOLD -> 1f - p * p * p
            CrossfadeCurve.GENTLE -> (1f - p) * (1f - p)
        }
    }

    /** The incoming song's gain [progress] of the way through the fade. */
    fun fadeInGain(
        progress: Float,
        curve: CrossfadeCurve = CrossfadeCurve.EQUAL_POWER,
    ): Float {
        val p = progress.coerceIn(0f, 1f)
        return if (curve == CrossfadeCurve.S_CURVE) smoothstep(p) else sin(p * PI / 2).toFloat()
    }

    private fun smoothstep(p: Float): Float = p * p * (3f - 2f * p)

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
