package io.github.aedev.flow.data.wellbeing

import androidx.compose.runtime.Immutable

/** The daily limit as the viewer set it in Wellbeing. */
@Immutable
data class DailyLimitSettings(
    val enabled: Boolean = false,
    val minutes: Int = DailyLimitPolicy.DEFAULT_MINUTES,
    val countMusic: Boolean = false,
)

/**
 * Playing time counted on one local day ([day] is an epoch day). [extraMs] is the time Keep watching added that day,
 * [warned] whether the five-minute warning went out, and [reached] whether the limit ran out.
 */
@Immutable
data class DailyTally(
    val day: Long,
    val playedMs: Long = 0L,
    val extraMs: Long = 0L,
    val warned: Boolean = false,
    val reached: Boolean = false,
)

/** What the daily limit does with a [DailyTally]: the day it belongs to, the time left, the warning and the extension. */
object DailyLimitPolicy {
    const val MIN_MINUTES = 15
    const val MAX_MINUTES = 8 * 60
    const val STEP_MINUTES = 15
    const val DEFAULT_MINUTES = 2 * 60
    const val EXTENSION_MS = 15 * 60_000L
    const val WARNING_MS = 5 * 60_000L
    const val WAIT_MS = 30_000L
    private const val MILLIS_PER_MINUTE = 60_000L

    val minuteChoices: List<Int> = (MIN_MINUTES..MAX_MINUTES step STEP_MINUTES).toList()

    /** [minutes] if it is one of the choices, otherwise the default. */
    fun validMinutes(minutes: Int?): Int = minutes?.takeIf { it in minuteChoices } ?: DEFAULT_MINUTES

    /**
     * The tally to count on for [today]. A later day starts from nothing. An earlier one means the clock was moved
     * back, so the stored day carries on rather than handing out a fresh day.
     */
    fun rollover(
        tally: DailyTally?,
        today: Long,
    ): DailyTally = if (tally == null || today > tally.day) DailyTally(today) else tally

    fun add(
        tally: DailyTally,
        playedMs: Long,
    ): DailyTally = tally.copy(playedMs = tally.playedMs + playedMs.coerceAtLeast(0L))

    fun allowanceMs(
        minutes: Int,
        tally: DailyTally,
    ): Long = minutes * MILLIS_PER_MINUTE + tally.extraMs

    fun remainingMs(
        minutes: Int,
        tally: DailyTally,
    ): Long = (allowanceMs(minutes, tally) - tally.playedMs).coerceAtLeast(0L)

    /** True once per day, when the time left first drops to the warning and the limit hasn't run out yet. */
    fun shouldWarn(
        minutes: Int,
        tally: DailyTally,
    ): Boolean = !tally.warned && remainingMs(minutes, tally) in 1..WARNING_MS

    /** How long playing can go on before the warning or the limit is due. */
    fun msUntilNextCheck(
        minutes: Int,
        tally: DailyTally,
    ): Long {
        val remaining = remainingMs(minutes, tally)
        return if (!tally.warned && remaining > WARNING_MS) remaining - WARNING_MS else remaining
    }

    /** Exactly [EXTENSION_MS] more from now, also when playing ran past the limit (a car kept it going). */
    fun extend(
        minutes: Int,
        tally: DailyTally,
    ): DailyTally {
        val overrunMs = (tally.playedMs - allowanceMs(minutes, tally)).coerceAtLeast(0L)
        return tally.copy(extraMs = tally.extraMs + overrunMs + EXTENSION_MS)
    }

    /** Turning the limit off or lowering it after it ran out today waits [WAIT_MS] first. Raising it doesn't. */
    fun changeNeedsWait(
        current: DailyLimitSettings,
        next: DailyLimitSettings,
        tally: DailyTally?,
        today: Long,
    ): Boolean {
        val ranOutToday = tally != null && tally.reached && today <= tally.day
        if (!ranOutToday || !current.enabled) return false
        return !next.enabled || next.minutes < current.minutes || (current.countMusic && !next.countMusic)
    }
}
