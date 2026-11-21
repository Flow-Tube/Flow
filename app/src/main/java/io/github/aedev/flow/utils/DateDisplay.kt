package io.github.aedev.flow.utils

import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.utils.relativedate.RelativeUploadDateParser
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

enum class DateDisplayMode {
    RELATIVE,
    EXACT,
    BOTH,
    ;

    companion object {
        fun fromString(value: String?): DateDisplayMode = entries.firstOrNull { it.name == value } ?: RELATIVE
    }
}

enum class DateContextMode {
    DEFAULT,
    RELATIVE,
    EXACT,
    BOTH,
    ;

    fun toDisplayMode(): DateDisplayMode? =
        when (this) {
            DEFAULT -> null
            RELATIVE -> DateDisplayMode.RELATIVE
            EXACT -> DateDisplayMode.EXACT
            BOTH -> DateDisplayMode.BOTH
        }

    companion object {
        fun fromString(value: String?): DateContextMode = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

enum class DateFormatStyle(
    val pattern: String?,
) {
    SYSTEM(null),
    MMM_D_YYYY("MMM d, yyyy"),
    D_MMM_YYYY("d MMM yyyy"),
    ISO("yyyy-MM-dd"),
    DMY_SLASH("dd/MM/yyyy"),
    MDY_SLASH("MM/dd/yyyy"),
    ;

    companion object {
        fun fromString(value: String?): DateFormatStyle = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

enum class DateContext { LISTS, WATCH, DESCRIPTION }

/**
 * The words a date line adds around the formatted date, from string resources in the reader's
 * language. [streamedTemplate] and [premieredTemplate] take the date as their one argument.
 */
data class DateLabels(
    val live: String,
    val streamedTemplate: String,
    val premieredTemplate: String,
)

data class DateDisplaySettings(
    val globalMode: DateDisplayMode = DateDisplayMode.RELATIVE,
    val formatStyle: DateFormatStyle = DateFormatStyle.SYSTEM,
    val listsMode: DateContextMode = DateContextMode.DEFAULT,
    val watchMode: DateContextMode = DateContextMode.DEFAULT,
    val descriptionMode: DateContextMode = DateContextMode.DEFAULT,
) {
    fun resolve(context: DateContext): DateDisplayMode {
        val override =
            when (context) {
                DateContext.LISTS -> listsMode
                DateContext.WATCH -> watchMode
                DateContext.DESCRIPTION -> descriptionMode
            }
        return override.toDisplayMode() ?: globalMode
    }

    fun format(
        date: String?,
        context: DateContext,
        labels: DateLabels,
        timestampFallbackMs: Long = 0L,
        timestampIsExact: Boolean = false,
        locale: Locale = Locale.getDefault(),
    ): String =
        formatUploadDateConfigured(
            date = date,
            mode = resolve(context),
            style = formatStyle,
            labels = labels,
            timestampFallbackMs = timestampFallbackMs,
            locale = locale,
            timestampIsExact = timestampIsExact,
        )
}

/**
 * When a stream still ahead starts: the moment the feed already knew, or the one its premiere label
 * carries, and only while it is still in the future.
 */
fun upcomingReleaseMs(
    timestampMs: Long,
    uploadDate: String,
    nowMs: Long = System.currentTimeMillis(),
): Long? = timestampMs.takeIf { it > nowMs } ?: parsePremiereTimestamp(uploadDate)?.takeIf { it > nowMs }

/** The start of a stream still ahead, in the mode the user chose for list dates. */
fun formatScheduledStart(
    releaseMs: Long,
    mode: DateDisplayMode,
    nowMs: Long = System.currentTimeMillis(),
    locale: Locale = Locale.getDefault(),
): String =
    when (mode) {
        DateDisplayMode.RELATIVE -> formatTimeUntil(releaseMs, nowMs, locale)
        DateDisplayMode.EXACT -> formatPremiereDate(releaseMs)
        DateDisplayMode.BOTH -> "${formatTimeUntil(releaseMs, nowMs, locale)} • ${formatPremiereDate(releaseMs)}"
    }

fun formatExactDate(
    timestampMs: Long,
    style: DateFormatStyle,
    locale: Locale = Locale.getDefault(),
): String {
    if (timestampMs <= 0L) return ""
    val moment = Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault())
    return try {
        val pattern =
            style.pattern
                ?: android.text.format.DateFormat
                    .getBestDateTimePattern(locale, "yMMMd")
        DateTimeFormatter.ofPattern(pattern, locale).format(moment)
    } catch (e: Exception) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(moment)
    }
}

fun formatUploadDateConfigured(
    date: String?,
    mode: DateDisplayMode,
    style: DateFormatStyle,
    labels: DateLabels,
    timestampFallbackMs: Long = 0L,
    locale: Locale = Locale.getDefault(),
    hl: String? = YouTube.locale.hl,
    timestampIsExact: Boolean = false,
    nowMillis: Long = System.currentTimeMillis(),
): String {
    if (date?.trim().equals("live", ignoreCase = true)) return labels.live

    val timestamp = resolveDisplayUploadTimestamp(date, timestampFallbackMs, nowMillis, hl)
    val prefixTemplate = relativePrefixTemplate(date, labels)
    val relative by lazy(LazyThreadSafetyMode.NONE) {
        applyPrefix(relativeString(date, timestamp, locale), prefixTemplate)
    }
    val exactTimestamp = exactUploadTimestamp(date, timestamp, timestampFallbackMs, timestampIsExact, nowMillis, hl)
    val exact = if (exactTimestamp != null && exactTimestamp > 0L) formatExactDate(exactTimestamp, style, locale) else ""
    val exactWithPrefix = applyPrefix(exact, prefixTemplate)
    return when (mode) {
        DateDisplayMode.RELATIVE -> {
            relative
        }

        DateDisplayMode.EXACT -> {
            exactWithPrefix.ifBlank { relative }
        }

        DateDisplayMode.BOTH -> {
            when {
                relative.isNotBlank() && exact.isNotBlank() -> "$relative • $exact"
                exactWithPrefix.isNotBlank() -> exactWithPrefix
                else -> relative
            }
        }
    }
}

/**
 * The moment behind a calendar date, only when something actually knows it: absolute text ("Jun 18,
 * 2025"), a timestamp from an exact source such as RSS, or an age under a day. "1 year ago" alone
 * covers twelve months, so turning it into a date would print one the upload never had (#1242).
 */
internal fun exactUploadTimestamp(
    date: String?,
    resolvedTimestamp: Long?,
    timestampFallbackMs: Long,
    timestampIsExact: Boolean,
    nowMillis: Long = System.currentTimeMillis(),
    hl: String? = YouTube.locale.hl,
): Long? {
    if (timestampIsExact && timestampFallbackMs > 0L) return timestampFallbackMs
    if (date.isNullOrBlank()) return null
    val age = RelativeUploadDateParser.read(date, hl, nowMillis)
    return when {
        age != null -> resolvedTimestamp.takeIf { age.placesCalendarDay }
        parseToTimestamp(date, hl) != null -> resolvedTimestamp
        else -> null
    }
}

private fun relativeString(
    date: String?,
    timestamp: Long?,
    locale: Locale,
): String {
    if (timestamp != null && timestamp > 0L) {
        return formatYouTubeRelativeTime(timestamp, locale = locale)
    }
    return formatTimeAgo(date, locale)
}

private fun relativePrefixTemplate(
    date: String?,
    labels: DateLabels,
): String? {
    val text = date?.trim().orEmpty().lowercase(Locale.US)
    return when {
        text.startsWith("streamed ") -> labels.streamedTemplate
        text.startsWith("premiered ") -> labels.premieredTemplate
        else -> null
    }
}

private fun applyPrefix(
    value: String,
    template: String?,
): String {
    if (template == null || value.isBlank()) return value
    return template.format(value)
}

private val StreamOrPremiereWords = Regex("(?i)^(streamed|premiered)\\s+(live\\s+)?(on\\s+)?")

internal fun withoutStreamOrPremiereWords(text: String): String = text.replace(StreamOrPremiereWords, "").trim()

fun parseToTimestamp(
    text: String?,
    hl: String? = YouTube.locale.hl,
): Long? {
    val raw = text?.trim().orEmpty()
    if (raw.isEmpty()) return null
    raw.toLongOrNull()?.takeIf { it > 100_000_000_000L }?.let { return it }

    // "Streamed live on Jun 19, 2020" and "Premiered on Jan 1, 2020" carry the date behind words no
    // format string matches, so without dropping them the parse fails and the caller falls back to
    // whatever timestamp it already had.
    val cleanRaw = withoutStreamOrPremiereWords(raw)

    parseAbsoluteDate(cleanRaw)?.let { return it }
    return RelativeUploadDateParser.parse(cleanRaw, hl)
}

/**
 * Relative text is read in the host language it was fetched in ([hl]), then in English. Text neither
 * reads leaves the stored timestamp, or null, in which case the relative display shows the server's
 * own wording rather than a guess.
 */
internal fun resolveDisplayUploadTimestamp(
    date: String?,
    timestampFallbackMs: Long,
    nowMillis: Long = System.currentTimeMillis(),
    hl: String? = YouTube.locale.hl,
): Long? {
    val storedTimestamp = timestampFallbackMs.takeIf { it > 0L }
    val relativeTimestamp = RelativeUploadDateParser.parse(date, hl, nowMillis)
    return when {
        storedTimestamp != null && relativeTimestamp != null -> minOf(storedTimestamp, relativeTimestamp)
        relativeTimestamp != null -> relativeTimestamp
        else -> preferStoredWithinDay(parseToTimestamp(date, hl), storedTimestamp)
    }
}

/**
 * A parsed date beats a stored timestamp, except on the day they share.
 *
 * "Sep 17, 2026" carries no time, so parsing it lands on midnight; a stored timestamp from the same
 * day knows the hour too. Taking the parse regardless is what made a video published four hours ago
 * read as however long the day had been running.
 */
private fun preferStoredWithinDay(
    parsed: Long?,
    stored: Long?,
): Long? {
    if (parsed == null) return stored
    if (stored == null) return parsed
    val zone = ZoneId.systemDefault()
    val sameDay =
        Instant.ofEpochMilli(parsed).atZone(zone).toLocalDate() ==
            Instant.ofEpochMilli(stored).atZone(zone).toLocalDate()
    return if (sameDay) stored else parsed
}
