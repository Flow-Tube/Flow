package io.github.aedev.flow.data.lyrics

/** What a provider's answer is worth: how it is timed, and whether those times can be trusted. */
internal object LyricsChecks {
    const val BUSY_REST_MS = 2 * 60 * 1000L
    private const val AUTH_REST_MS = 10 * 60 * 1000L

    /** How long a source that failed with [error] rests before it is asked again, or null when the next song asks it. */
    fun restAfter(error: Throwable): Long? =
        when {
            error is LyricsSourceBusyException -> maxOf(BUSY_REST_MS, error.retryAfterMs ?: 0L)
            isAuthFailure(error.message) -> AUTH_REST_MS
            else -> null
        }

    fun hasWordSync(entries: List<LyricsEntry>): Boolean {
        val wordLines = entries.filter { !it.words.isNullOrEmpty() }
        if (wordLines.isEmpty()) return false

        val lineCount = entries.size.coerceAtLeast(1)
        val ratio = wordLines.size.toFloat() / lineCount
        if (lineCount > 5 && wordLines.size < 3) return false
        if (lineCount > 10 && ratio < 0.25f) return false

        val validTimingLines =
            wordLines.count { entry ->
                val words = entry.words.orEmpty().sortedBy { it.startTime }
                words.isNotEmpty() &&
                    words.last().endTime > words.first().startTime &&
                    words.zipWithNext().all { (current, next) ->
                        current.endTime >= current.startTime && next.startTime >= current.startTime
                    }
            }
        if (validTimingLines < minOf(2, wordLines.size)) return false

        val firstWordMs = wordLines.minOf { it.words!!.first().startTime }
        val lastWordMs = wordLines.maxOf { it.words!!.last().endTime }
        if (lineCount > 5 && lastWordMs - firstWordMs < 10_000L) return false

        return true
    }

    fun hasReasonableTimestamps(
        entries: List<LyricsEntry>,
        durationSec: Int,
    ): Boolean {
        val timed = entries.filter { it.time > 0 }
        if (timed.size < 2) return true
        val firstMs = timed.first().time
        val lastMs = timed.last().time
        if (lastMs - firstMs < 10_000L) return false
        if (durationSec > 0 && firstMs > durationSec * 1000L + 10_000L) return false
        return true
    }

    fun isAuthFailure(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        return message.contains(" 401") || message.contains(" 403") ||
            message.contains("Unauthorized", ignoreCase = true) ||
            message.contains("Forbidden", ignoreCase = true)
    }

    fun entriesAreSynced(entries: List<LyricsEntry>): Boolean {
        if (entries.size < 2) return false
        val main = entries.filter { !it.isBackground }
        val list = if (main.size >= 2) main else entries
        val times = list.map { it.time }.distinct()
        if (times.size < 2) return false
        val firstPositive = times.firstOrNull { it > 0L } ?: return false
        val maxTime = list.maxOf { it.time }
        if (maxTime - firstPositive < 5_000L) return false
        val hasWordTimings = entries.any { !it.words.isNullOrEmpty() }
        if (hasWordTimings) return true
        val distinctTimedLines = list.count { it.time > 0L }
        return distinctTimedLines >= (list.size * 0.5).toInt().coerceAtLeast(2)
    }

    fun stripWordTimings(entries: List<LyricsEntry>): List<LyricsEntry> = entries.map { entry -> entry.copy(words = null) }

    fun normalizeEntries(entries: List<LyricsEntry>): List<LyricsEntry> =
        entries.map { entry ->
            entry.copy(
                text = LyricsUtils.decodeHtmlEntities(entry.text),
                words =
                    entry.words?.map { word ->
                        word.copy(text = LyricsUtils.decodeHtmlEntities(word.text))
                    },
            )
        }
}
