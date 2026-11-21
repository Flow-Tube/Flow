package io.github.aedev.flow.utils

import java.text.ParsePosition
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.temporal.TemporalAccessor
import java.time.temporal.TemporalQueries
import java.util.Locale

private val NumericDateForms =
    listOf(
        "uuuu-M-d'T'H:m:sXXX",
        "uuuu-M-d'T'H:m:sX",
        "uuuu-M-d'T'H:m:s.SSSXXX",
        "uuuu-M-d'T'H:m:s.SSSX",
        "uuuu-M-d'T'H:m:s",
        "uuuu-M-d",
    ).map { strictForm(it, Locale.US) }

// Full and short month names are both accepted, as SimpleDateFormat's text parsing did.
private val WrittenDatePatterns = listOf("[MMMM][MMM] d, uuuu", "d [MMMM][MMM] uuuu")

private fun writtenForms(locale: Locale): List<DateTimeFormatter> = WrittenDatePatterns.map { strictForm(it, locale) }

private val UsWrittenForms = writtenForms(Locale.US)

private val DeviceWrittenForms = LocaleSlot(::writtenForms)

private fun strictForm(
    pattern: String,
    locale: Locale,
): DateTimeFormatter =
    DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern(pattern)
        .toFormatter(locale)
        .withResolverStyle(ResolverStyle.STRICT)

/**
 * An absolute date at the start of [text] ("2024-01-15T10:00:00Z", "Jun 18, 2025", "18 Jun 2025"),
 * as epoch millis. Trailing text is ignored and impossible dates are rejected. A form without an
 * offset is read in the device zone, a form without a time at its midnight. Written months are read
 * in US English first, then in the device language.
 */
internal fun parseAbsoluteDate(text: String): Long? {
    NumericDateForms.firstNotNullOfOrNull { parsePrefix(it, text) }?.let { return it }
    val device = Locale.getDefault()
    val deviceForms = if (device == Locale.US) null else DeviceWrittenForms.use(device) { it }
    for (index in WrittenDatePatterns.indices) {
        parsePrefix(UsWrittenForms[index], text)?.let { return it }
        deviceForms?.let { forms -> parsePrefix(forms[index], text)?.let { return it } }
    }
    return null
}

private fun parsePrefix(
    formatter: DateTimeFormatter,
    text: String,
): Long? =
    try {
        formatter.parse(text, ParsePosition(0)).toEpochMillis()
    } catch (_: DateTimeException) {
        null
    }

private fun TemporalAccessor.toEpochMillis(): Long? {
    val date = query(TemporalQueries.localDate()) ?: return null
    val dateTime = LocalDateTime.of(date, query(TemporalQueries.localTime()) ?: LocalTime.MIDNIGHT)
    val offset = query(TemporalQueries.offset())
    val instant = if (offset != null) dateTime.toInstant(offset) else dateTime.atZone(ZoneId.systemDefault()).toInstant()
    return instant.toEpochMilli()
}
