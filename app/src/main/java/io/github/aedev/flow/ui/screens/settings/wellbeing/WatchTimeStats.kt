package io.github.aedev.flow.ui.screens.settings.wellbeing

import androidx.compose.runtime.Immutable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

internal const val WEEK_DAYS = 7

/** Time watched on one calendar day. */
@Immutable
data class DayWatchTime(
    val date: LocalDate,
    val millis: Long,
)

/** The last seven days of watch time, oldest first and ending today. */
@Immutable
data class WatchTimeSummary(
    val days: List<DayWatchTime>,
) {
    val todayMillis: Long get() = days.lastOrNull()?.millis ?: 0L
    val weekMillis: Long get() = days.sumOf { it.millis }
    val dailyAverageMillis: Long get() = weekMillis / WEEK_DAYS
    val isEmpty: Boolean get() = days.all { it.millis == 0L }

    /** The row for [date], or null when it is not one of these seven days. */
    fun dayOrNull(date: LocalDate?): DayWatchTime? = days.firstOrNull { it.date == date }

    /** The chart's one chosen day: [picked] while it is still in this week, otherwise today. */
    fun chosenDay(picked: LocalDate?): DayWatchTime? = dayOrNull(picked) ?: days.lastOrNull()

    /** What to remember after a tap on [tapped]. Today is remembered as no pick, so the choice stays on today past midnight. */
    fun pickAfterTap(tapped: LocalDate): LocalDate? = tapped.takeUnless { it == days.lastOrNull()?.date }

    companion object {
        fun empty(today: LocalDate) = WatchTimeSummary((WEEK_DAYS - 1 downTo 0).map { DayWatchTime(today.minusDays(it.toLong()), 0L) })
    }
}

/**
 * The seven days ending [today] from the recap ledgers' real per-day time, or null while the ledger
 * does not yet cover the whole week and watch history is still the better estimate.
 */
internal fun summarizeLedgerTime(
    dayMs: Map<LocalDate, Long>,
    ledgerStart: LocalDate?,
    today: LocalDate,
): WatchTimeSummary? {
    val first = today.minusDays((WEEK_DAYS - 1).toLong())
    if (ledgerStart == null || ledgerStart > first) return null
    return WatchTimeSummary((0 until WEEK_DAYS).map { offset -> first.plusDays(offset.toLong()).let { DayWatchTime(it, dayMs[it] ?: 0L) } })
}

/** Time left until the next calendar day starts in [now]'s zone. A day can run 23 or 25 hours. */
internal fun millisUntilNextDay(now: ZonedDateTime): Long =
    Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone)).toMillis()

/** A watched item: when it was last watched and how far into it playback got. */
data class WatchRecord(
    val timestamp: Long,
    val watchedMillis: Long,
)

/**
 * Buckets [records] into the seven calendar days ending [today] in [zone]. A record counts on the
 * day it was last watched, for as far as playback got, which is the only duration history keeps.
 */
internal fun summarizeWatchTime(
    records: List<WatchRecord>,
    today: LocalDate,
    zone: ZoneId,
): WatchTimeSummary {
    val first = today.minusDays((WEEK_DAYS - 1).toLong())
    val totals = LongArray(WEEK_DAYS)
    records.forEach { record ->
        val day = Instant.ofEpochMilli(record.timestamp).atZone(zone).toLocalDate()
        if (day < first || day > today) return@forEach
        totals[(day.toEpochDay() - first.toEpochDay()).toInt()] += record.watchedMillis.coerceAtLeast(0L)
    }
    return WatchTimeSummary(totals.mapIndexed { index, millis -> DayWatchTime(first.plusDays(index.toLong()), millis) })
}
