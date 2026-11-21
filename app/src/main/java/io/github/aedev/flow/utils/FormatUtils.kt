package io.github.aedev.flow.utils

import android.icu.text.CompactDecimalFormat
import android.icu.text.RelativeDateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Format a duration as `H:MM:SS` or `M:SS`.
 *
 * @param padMinutes when true the no-hours form is zero-padded (`MM:SS`), which is what the
 *   on-video player controls render; every other caller keeps the unpadded default.
 */
fun formatDuration(
    seconds: Int,
    padMinutes: Boolean = false,
): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return when {
        hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, secs)
        padMinutes -> String.format("%02d:%02d", minutes, secs)
        else -> String.format("%d:%02d", minutes, secs)
    }
}

fun formatDurationMillis(
    millis: Long,
    padMinutes: Boolean = false,
): String = formatDuration((millis / 1000L).toInt(), padMinutes)

/**
 * Format a multiplier as a compact label, e.g. `2x`, `1.5x`, `0.75x`.
 * Trailing zeros are trimmed and the value is clamped to `0.1..maxValue`.
 */
fun formatMultiplierLabel(
    value: Float,
    maxValue: Float = 10.0f,
): String {
    val clamped = value.coerceIn(0.1f, maxValue)
    return if (kotlin.math.abs(clamped - clamped.toInt()) < 0.01f) {
        "${clamped.toInt()}x"
    } else {
        val rounded = kotlin.math.round(clamped * 100f) / 100f
        "${rounded.toString().trimEnd('0').trimEnd('.')}x"
    }
}

fun formatViewCount(count: Long): String = formatCompactCount(count)

fun formatSubscriberCount(count: Long): String {
    if (count <= 0L) return ""
    return formatCompactCount(count)
}

private val CompactCounts =
    LocaleSlot<CompactDecimalFormat> { locale ->
        CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT)
    }

internal fun formatCompactCount(
    count: Long,
    locale: Locale = Locale.getDefault(),
): String = CompactCounts.use(locale) { it.format(count) }

fun formatYouTubeRelativeTime(
    timestampMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
    locale: Locale = Locale.getDefault(),
): String =
    formatRelativeSpan(
        spanMillis = (nowMillis - timestampMillis).coerceAtLeast(0L),
        direction = RelativeDateTimeFormatter.Direction.LAST,
        locale = locale,
    )

/** "in 4 hours" for a moment still ahead: the mirror of [formatYouTubeRelativeTime]. */
fun formatTimeUntil(
    timestampMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
    locale: Locale = Locale.getDefault(),
): String =
    formatRelativeSpan(
        spanMillis = (timestampMillis - nowMillis).coerceAtLeast(0L),
        direction = RelativeDateTimeFormatter.Direction.NEXT,
        locale = locale,
    )

private fun formatRelativeSpan(
    spanMillis: Long,
    direction: RelativeDateTimeFormatter.Direction,
    locale: Locale,
): String {
    val seconds = spanMillis / 1000L
    val minutes = seconds / 60L
    val hours = minutes / 60L
    val days = hours / 24L
    val weeks = days / 7L
    val months = days / 30L
    val years = days / 365L

    return when {
        years > 0L -> {
            formatRelativeTime(years, RelativeDateTimeFormatter.RelativeUnit.YEARS, direction, locale)
        }

        months > 0L -> {
            formatRelativeTime(months, RelativeDateTimeFormatter.RelativeUnit.MONTHS, direction, locale)
        }

        weeks > 0L -> {
            formatRelativeTime(weeks, RelativeDateTimeFormatter.RelativeUnit.WEEKS, direction, locale)
        }

        days > 0L -> {
            formatRelativeTime(days, RelativeDateTimeFormatter.RelativeUnit.DAYS, direction, locale)
        }

        hours > 0L -> {
            formatRelativeTime(hours, RelativeDateTimeFormatter.RelativeUnit.HOURS, direction, locale)
        }

        minutes > 0L -> {
            formatRelativeTime(minutes, RelativeDateTimeFormatter.RelativeUnit.MINUTES, direction, locale)
        }

        else -> {
            RelativeDateTimeFormatter.getInstance(locale).format(
                RelativeDateTimeFormatter.Direction.PLAIN,
                RelativeDateTimeFormatter.AbsoluteUnit.NOW,
            )
        }
    }
}

fun formatTimeAgo(
    dateString: String?,
    locale: Locale = Locale.getDefault(),
): String {
    if (dateString.isNullOrBlank()) return ""

    normalizeRelativeTimeText(dateString, locale)?.let { return it }
    if (dateString.contains("前")) return dateString

    val formats =
        listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
        )

    var date: java.util.Date? = null
    for (format in formats) {
        try {
            val sdf = java.text.SimpleDateFormat(format, java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            date = sdf.parse(dateString)
            if (date != null) break
        } catch (e: Exception) {
        }
    }

    if (date == null) return dateString

    return try {
        formatYouTubeRelativeTime(date.time, locale = locale)
    } catch (e: Exception) {
        dateString
    }
}

private fun normalizeRelativeTimeText(
    value: String,
    locale: Locale,
): String? {
    val text = value.trim()
    val lower = text.lowercase(java.util.Locale.US)
    if (!lower.contains("ago") && !lower.contains("just now")) return null
    val prefix =
        when {
            lower.startsWith("streamed ") -> "Streamed"
            lower.startsWith("premiered ") -> "Premiered"
            else -> null
        }
    if (lower.contains("just now")) {
        val relative =
            RelativeDateTimeFormatter.getInstance(locale).format(
                RelativeDateTimeFormatter.Direction.PLAIN,
                RelativeDateTimeFormatter.AbsoluteUnit.NOW,
            )
        return if (prefix != null) "$prefix $relative" else relative
    }

    val match =
        Regex(
            """(\d+)\s*(mo|sec|secs|second|seconds|min|mins|minute|minutes|hr|hrs|hour|hours|day|days|week|weeks|month|months|year|years|[smhdwy])\b""",
        ).find(lower) ?: return text
    val count = match.groupValues[1].toLongOrNull() ?: return text
    val unit =
        when (match.groupValues[2]) {
            "s", "sec", "secs", "second", "seconds" -> RelativeDateTimeFormatter.RelativeUnit.SECONDS
            "m", "min", "mins", "minute", "minutes" -> RelativeDateTimeFormatter.RelativeUnit.MINUTES
            "h", "hr", "hrs", "hour", "hours" -> RelativeDateTimeFormatter.RelativeUnit.HOURS
            "d", "day", "days" -> RelativeDateTimeFormatter.RelativeUnit.DAYS
            "w", "week", "weeks" -> RelativeDateTimeFormatter.RelativeUnit.WEEKS
            "mo", "month", "months" -> RelativeDateTimeFormatter.RelativeUnit.MONTHS
            "y", "year", "years" -> RelativeDateTimeFormatter.RelativeUnit.YEARS
            else -> return text
        }
    val relative = formatRelativeTime(count, unit, RelativeDateTimeFormatter.Direction.LAST, locale)
    return if (prefix != null) "$prefix $relative" else relative
}

private fun formatRelativeTime(
    value: Long,
    unit: RelativeDateTimeFormatter.RelativeUnit,
    direction: RelativeDateTimeFormatter.Direction,
    locale: Locale,
): String =
    RelativeDateTimeFormatter.getInstance(locale).format(
        value.toDouble(),
        direction,
        unit,
    )

fun formatLikeCount(count: Int): String =
    when {
        count >= 1_000_000 -> "${(count / 1_000_000.0 * 10).roundToInt() / 10.0}M"
        count >= 1_000 -> "${(count / 1_000.0 * 10).roundToInt() / 10.0}K"
        else -> "$count"
    }

/**
 * Formats a scheduled premiere date string (from NewPipe extractor) into YouTube-style:
 * "Premieres M/d/yy, h:mm a"  e.g. "Premieres 4/1/26, 9:00 AM"
 *
 * Returns "Premieres soon" if the date cannot be parsed.
 */
fun formatPremiereDate(dateString: String): String? {
    if (dateString.isBlank()) return null
    return parsePremiereDate(dateString)?.time?.let(::formatPremiereDate)
}

fun formatPremiereDate(timestampMs: Long): String {
    val out = java.text.SimpleDateFormat("M/d/yy, h:mm a", java.util.Locale.US)
    out.timeZone = java.util.TimeZone.getDefault()
    return out.format(java.util.Date(timestampMs))
}

/** The form [formatPremiereDate] reads back, in the device's zone. */
fun premiereDateText(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(PREMIERE_DATE_FORMAT)

private val PREMIERE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

fun parsePremiereTimestamp(dateString: String): Long? = parsePremiereDate(dateString)?.time

private fun parsePremiereDate(dateString: String): java.util.Date? {
    if (dateString.isBlank()) return null
    val formats =
        listOf(
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
        )
    var date: java.util.Date? = null
    for (fmt in formats) {
        try {
            val sdf = java.text.SimpleDateFormat(fmt, java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getDefault()
            date = sdf.parse(dateString)
            if (date != null) break
        } catch (_: Exception) {
        }
    }
    return date
}
