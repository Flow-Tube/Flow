package io.github.aedev.flow.ui.screens.settings

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.ui.screens.settings.wellbeing.DayWatchTime
import io.github.aedev.flow.ui.screens.settings.wellbeing.WatchTimeSummary
import io.github.aedev.flow.ui.screens.settings.wellbeing.millisUntilNextDay
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class WatchTimeSelectionTest {
    private val today = LocalDate.of(2026, 11, 5)
    private val tuesday = today.minusDays(2)

    private fun weekEnding(
        end: LocalDate,
        millisPerDay: (LocalDate) -> Long = { 60_000L },
    ) = WatchTimeSummary((6L downTo 0L).map { end.minusDays(it) }.map { DayWatchTime(it, millisPerDay(it)) })

    @Test
    fun `the chart opens on today`() {
        assertThat(weekEnding(today).chosenDay(null)?.date).isEqualTo(today)
    }

    @Test
    fun `a tapped day becomes the chosen day`() {
        val week = weekEnding(today)

        assertThat(week.chosenDay(week.pickAfterTap(tuesday))?.date).isEqualTo(tuesday)
    }

    @Test
    fun `tapping the chosen day again leaves it chosen`() {
        val week = weekEnding(today)

        val first = week.pickAfterTap(tuesday)
        val second = week.pickAfterTap(week.chosenDay(first)!!.date)

        assertThat(second).isEqualTo(first)
        assertThat(week.chosenDay(second)?.date).isEqualTo(tuesday)
    }

    @Test
    fun `tapping today remembers no pick and stays on today`() {
        val week = weekEnding(today)

        val first = week.pickAfterTap(today)

        assertThat(first).isNull()
        assertThat(week.chosenDay(first)?.date).isEqualTo(today)
        assertThat(week.pickAfterTap(week.chosenDay(first)!!.date)).isNull()
    }

    @Test
    fun `a week with no data still chooses today at zero`() {
        val chosen = WatchTimeSummary.empty(today).chosenDay(null)

        assertThat(chosen).isEqualTo(DayWatchTime(today, 0L))
    }

    @Test
    fun `today with no time stays chosen over days that have some`() {
        val week = weekEnding(today) { if (it == today) 0L else 60_000L }

        assertThat(week.isEmpty).isFalse()
        assertThat(week.chosenDay(null)).isEqualTo(DayWatchTime(today, 0L))
    }

    @Test
    fun `a summary with no days chooses nothing and does not crash`() {
        assertThat(WatchTimeSummary(emptyList()).chosenDay(today)).isNull()
    }

    @Test
    fun `at midnight the default choice moves to the new today`() {
        val pick = weekEnding(today).pickAfterTap(today)

        assertThat(weekEnding(today.plusDays(1)).chosenDay(pick)?.date).isEqualTo(today.plusDays(1))
    }

    @Test
    fun `at midnight a picked day stays chosen while it is still in the week`() {
        val pick = weekEnding(today).pickAfterTap(tuesday)

        assertThat(weekEnding(today.plusDays(1)).chosenDay(pick)?.date).isEqualTo(tuesday)
    }

    @Test
    fun `a picked day that left the week falls back to today`() {
        val oldest = today.minusDays(6)
        val pick = weekEnding(today).pickAfterTap(oldest)

        assertThat(weekEnding(today.plusDays(1)).chosenDay(pick)?.date).isEqualTo(today.plusDays(1))
    }

    @Test
    fun `a restored pick newer than the week falls back to today`() {
        assertThat(weekEnding(today).chosenDay(today.plusDays(3))?.date).isEqualTo(today)
    }

    @Test
    fun `the wait runs to the start of the next local day`() {
        val zone = ZoneId.of("Europe/Paris")
        val now = ZonedDateTime.of(today, LocalTime.of(23, 59, 59), zone)

        assertThat(millisUntilNextDay(now)).isEqualTo(1_000L)
    }

    @Test
    fun `the wait follows a short day when the clocks go forward`() {
        val zone = ZoneId.of("America/New_York")
        val midnight = LocalDate.of(2026, 3, 8).atStartOfDay(zone)

        assertThat(millisUntilNextDay(midnight)).isEqualTo(Duration.ofHours(23).toMillis())
    }
}
