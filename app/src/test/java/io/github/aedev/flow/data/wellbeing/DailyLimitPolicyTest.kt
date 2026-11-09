package io.github.aedev.flow.data.wellbeing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DailyLimitPolicyTest {
    private val minute = 60_000L
    private val day = 20_000L

    @Test
    fun `a new day starts from nothing`() {
        val yesterday = DailyTally(day, playedMs = 90 * minute, extraMs = 15 * minute, warned = true, reached = true)

        assertThat(DailyLimitPolicy.rollover(yesterday, day + 1)).isEqualTo(DailyTally(day + 1))
        assertThat(DailyLimitPolicy.rollover(null, day)).isEqualTo(DailyTally(day))
    }

    @Test
    fun `moving the clock back keeps counting on the stored day`() {
        val today = DailyTally(day, playedMs = 100 * minute, reached = true)

        assertThat(DailyLimitPolicy.rollover(today, day - 1)).isEqualTo(today)
        assertThat(DailyLimitPolicy.rollover(today, day)).isEqualTo(today)
    }

    @Test
    fun `time left counts down to zero and never below`() {
        val tally = DailyTally(day, playedMs = 100 * minute)

        assertThat(DailyLimitPolicy.remainingMs(120, tally)).isEqualTo(20 * minute)
        assertThat(DailyLimitPolicy.remainingMs(90, tally)).isEqualTo(0L)
        assertThat(DailyLimitPolicy.remainingMs(120, DailyLimitPolicy.add(tally, -5 * minute))).isEqualTo(20 * minute)
    }

    @Test
    fun `the warning goes out once, inside the last five minutes, and not after the limit`() {
        val early = DailyTally(day, playedMs = 110 * minute)
        val close = DailyTally(day, playedMs = 116 * minute)

        assertThat(DailyLimitPolicy.shouldWarn(120, early)).isFalse()
        assertThat(DailyLimitPolicy.shouldWarn(120, close)).isTrue()
        assertThat(DailyLimitPolicy.shouldWarn(120, close.copy(warned = true))).isFalse()
        assertThat(DailyLimitPolicy.shouldWarn(120, DailyTally(day, playedMs = 120 * minute))).isFalse()
    }

    @Test
    fun `the next check is the warning first, then the limit`() {
        val tally = DailyTally(day, playedMs = 100 * minute)

        assertThat(DailyLimitPolicy.msUntilNextCheck(120, tally)).isEqualTo(15 * minute)
        assertThat(DailyLimitPolicy.msUntilNextCheck(120, tally.copy(warned = true))).isEqualTo(20 * minute)
        assertThat(DailyLimitPolicy.msUntilNextCheck(120, DailyTally(day, playedMs = 117 * minute))).isEqualTo(3 * minute)
    }

    @Test
    fun `keep watching gives exactly fifteen minutes from now`() {
        val atLimit = DailyTally(day, playedMs = 120 * minute, warned = true, reached = true)
        val ranOver = atLimit.copy(playedMs = 130 * minute)

        assertThat(DailyLimitPolicy.remainingMs(120, DailyLimitPolicy.extend(120, atLimit))).isEqualTo(15 * minute)
        assertThat(DailyLimitPolicy.remainingMs(120, DailyLimitPolicy.extend(120, ranOver))).isEqualTo(15 * minute)
        val twice = DailyLimitPolicy.extend(120, DailyLimitPolicy.add(DailyLimitPolicy.extend(120, atLimit), 15 * minute))
        assertThat(DailyLimitPolicy.remainingMs(120, twice)).isEqualTo(15 * minute)
    }

    @Test
    fun `after the limit ran out today, loosening it waits and raising or tightening does not`() {
        val on = DailyLimitSettings(enabled = true, minutes = 60, countMusic = true)
        val ranOut = DailyTally(day, playedMs = 60 * minute, reached = true)

        assertThat(DailyLimitPolicy.changeNeedsWait(on, on.copy(enabled = false), ranOut, day)).isTrue()
        assertThat(DailyLimitPolicy.changeNeedsWait(on, on.copy(minutes = 45), ranOut, day)).isTrue()
        assertThat(DailyLimitPolicy.changeNeedsWait(on, on.copy(countMusic = false), ranOut, day)).isTrue()
        assertThat(DailyLimitPolicy.changeNeedsWait(on, on.copy(minutes = 90), ranOut, day)).isFalse()
        assertThat(DailyLimitPolicy.changeNeedsWait(on.copy(countMusic = false), on, ranOut, day)).isFalse()
    }

    @Test
    fun `no wait before the limit ran out, on a later day, or while it is off`() {
        val on = DailyLimitSettings(enabled = true, minutes = 60)
        val ranOut = DailyTally(day, playedMs = 60 * minute, reached = true)

        assertThat(DailyLimitPolicy.changeNeedsWait(on, on.copy(enabled = false), ranOut.copy(reached = false), day)).isFalse()
        assertThat(DailyLimitPolicy.changeNeedsWait(on, on.copy(enabled = false), ranOut, day + 1)).isFalse()
        assertThat(DailyLimitPolicy.changeNeedsWait(on, on.copy(enabled = false), null, day)).isFalse()
        assertThat(DailyLimitPolicy.changeNeedsWait(on.copy(enabled = false), on, ranOut, day)).isFalse()
    }

    @Test
    fun `the limit is picked in quarter hours from fifteen minutes to eight hours`() {
        assertThat(DailyLimitPolicy.minuteChoices.first()).isEqualTo(15)
        assertThat(DailyLimitPolicy.minuteChoices.last()).isEqualTo(480)
        assertThat(DailyLimitPolicy.minuteChoices).hasSize(32)
        assertThat(DailyLimitPolicy.validMinutes(null)).isEqualTo(120)
        assertThat(DailyLimitPolicy.validMinutes(50)).isEqualTo(120)
        assertThat(DailyLimitPolicy.validMinutes(45)).isEqualTo(45)
    }
}
