package io.github.aedev.flow.ui.components.musicplayer.lyrics

import io.github.aedev.flow.data.lyrics.LyricsEntry
import io.github.aedev.flow.data.lyrics.WordTimestamp

/** A wordless stretch shorter than this gets no instrumental break row. */
private const val MIN_BREAK_MS = 4000L

/** Between line-synced lines, a gap this long is what it takes to suspect a solo. */
private const val MIN_UNMARKED_GAP_MS = 15_000L
private const val SLOW_MS_PER_CHARACTER = 250L

internal sealed class LyricsListItem {
    data class Line(
        val index: Int,
        val entry: LyricsEntry,
    ) : LyricsListItem()

    data class Indicator(
        val afterLineIndex: Int,
        val gapStartMs: Long,
        val gapEndMs: Long,
    ) : LyricsListItem()
}

internal fun adaptiveLyricsTextSize(
    baseSize: Float,
    textLength: Int,
    isBackground: Boolean,
): Float {
    val foregroundSize =
        when {
            textLength > 92 -> baseSize * 0.66f
            textLength > 72 -> baseSize * 0.72f
            textLength > 54 -> baseSize * 0.8f
            textLength > 42 -> baseSize * 0.9f
            else -> baseSize
        }
    return if (isBackground) foregroundSize * 0.7f else foregroundSize
}

internal fun buildLines(
    lyrics: String?,
    syncedLyrics: List<LyricsEntry>,
): List<LyricsEntry> {
    if (syncedLyrics.isNotEmpty() && entriesLookSynced(syncedLyrics)) {
        return listOf(LyricsEntry(time = 0L, text = "")) + withoutShortBreaks(syncedLyrics.sorted())
    }

    val plainSource =
        lyrics?.takeIf { it.isNotBlank() }
            ?: syncedLyrics.joinToString("\n") { it.text }.takeIf { it.isNotBlank() }
    val plainLines =
        plainSource
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    if (plainLines.isEmpty()) return emptyList()
    return plainLines.mapIndexed { index, line ->
        LyricsEntry(time = 1_000_000L + index, text = line)
    }
}

/**
 * A blank line only stays when it opens a break long enough to get a row. The list follows the
 * active line's row, so a blank line with none would send it back to the top while it is active.
 */
private fun withoutShortBreaks(lines: List<LyricsEntry>): List<LyricsEntry> =
    lines.filterIndexed { index, line ->
        val next = lines.getOrNull(index + 1)
        line.text.isNotBlank() || (next != null && next.time - line.time > MIN_BREAK_MS)
    }

internal fun entriesLookSynced(entries: List<LyricsEntry>): Boolean {
    if (entries.size < 2) return false
    val main = entries.filter { !it.isBackground }
    val list = if (main.size >= 2) main else entries
    val distinctTimes = list.map { it.time }.distinct()
    if (distinctTimes.size < 2) return false
    val firstPositive = distinctTimes.firstOrNull { it > 0L } ?: return false
    val maxTime = list.maxOf { it.time }
    if (maxTime - firstPositive < 5_000L) return false
    if (entries.any { !it.words.isNullOrEmpty() }) return true
    val distinctTimedLines = list.count { it.time > 0L }
    return distinctTimedLines >= (list.size * 0.5).toInt().coerceAtLeast(2)
}

/**
 * When [line]'s words stop, or null when nothing says they stop before [nextStart]. Line-synced
 * lyrics give no line an end, so a line followed by a long silence is taken to last as long as a
 * very slow singer would need: Hua Main holds a 40-character line for 14 s before a 55 s solo.
 */
private fun wordsEndBefore(
    line: LyricsEntry,
    nextStart: Long,
): Long? =
    when {
        !line.words.isNullOrEmpty() -> line.words.last().endTime
        line.text.isBlank() -> line.time
        line.isBackground || nextStart == Long.MAX_VALUE || nextStart - line.time < MIN_UNMARKED_GAP_MS -> null
        else -> minOf(line.time + line.text.length * SLOW_MS_PER_CHARACTER, nextStart)
    }

internal fun buildMergedLyricsList(lines: List<LyricsEntry>): List<LyricsListItem> {
    val result = mutableListOf<LyricsListItem>()
    lines.forEachIndexed { index, entry ->
        if (entry.text.isNotBlank()) {
            result.add(LyricsListItem.Line(index, entry))
        }
        if (index < lines.lastIndex) {
            val nextStart = lines[index + 1].time
            val currentEnd = wordsEndBefore(entry, nextStart)
            if (currentEnd != null && currentEnd < nextStart && nextStart - currentEnd > MIN_BREAK_MS) {
                result.add(LyricsListItem.Indicator(index, currentEnd, nextStart))
            }
        }
    }
    return result
}

internal fun findActiveLineIndices(
    lines: List<LyricsEntry>,
    position: Long,
): Set<Int> {
    val active = mutableSetOf<Int>()
    val hasWordTimings = lines.any { !it.words.isNullOrEmpty() }

    val distinctMainTimes =
        lines
            .asSequence()
            .filter { !it.isBackground }
            .map { it.time }
            .distinct()
            .take(3)
            .toList()
    if (distinctMainTimes.size < 2) return active

    for (index in lines.indices) {
        val line = lines[index]
        if (line.time > position) break
        val nextStart =
            (index + 1 until lines.size)
                .asSequence()
                .map { lines[it].time }
                .firstOrNull { it > line.time }
                ?: Long.MAX_VALUE
        val lineEndMs = if (line.text.isBlank()) nextStart else wordsEndBefore(line, nextStart) ?: nextStart
        if (position <= lineEndMs) active.add(index)
    }

    if (!hasWordTimings && active.size > 1) {
        val mainActive = active.filter { !lines[it].isBackground }
        if (mainActive.size > 1) {
            val maxTime = mainActive.maxOf { lines[it].time }
            active.removeAll { it in mainActive && lines[it].time < maxTime }
        }
    }

    return active
}

internal fun sanitizeWordTimestamps(words: List<WordTimestamp>): List<WordTimestamp> {
    if (words.isEmpty()) return emptyList()
    return words.mapIndexed { index, word ->
        val nextWord = words.getOrNull(index + 1)
        val start = word.startTime.coerceAtLeast(0L)
        val endFromNext = nextWord?.startTime?.takeIf { it > start }
        val end =
            when {
                endFromNext != null && word.endTime > endFromNext -> endFromNext
                word.endTime <= start -> start + 80L
                else -> word.endTime
            }
        word.copy(startTime = start, endTime = end)
    }
}

/**
 * The last word of a line, when it holds hyphens ("la-la-la"), is sung as separate syllables:
 * split it into timed segments and keep, for each, the index of the word it came from.
 */
internal fun splitTrailingHyphenWord(sanitizedInputWords: List<WordTimestamp>): Pair<List<WordTimestamp>, List<Int>> =
    sanitizedInputWords
        .flatMapIndexed { originalIdx, word ->
            val shouldSplit = word.text.contains('-') && word.text.length > 1 && originalIdx == sanitizedInputWords.lastIndex
            if (shouldSplit) {
                val segments = mutableListOf<String>()
                var start = 0
                for (i in word.text.indices) {
                    if (word.text[i] == '-') {
                        segments.add(word.text.substring(start, i + 1))
                        start = i + 1
                    }
                }
                if (start < word.text.length) segments.add(word.text.substring(start))
                if (segments.size > 1) {
                    val totalDuration = word.endTime - word.startTime
                    val segmentDuration = totalDuration / segments.size
                    segments.mapIndexed { index, segmentText ->
                        WordTimestamp(
                            text = segmentText,
                            startTime = word.startTime + index * segmentDuration,
                            endTime = word.startTime + (index + 1) * segmentDuration,
                        ) to originalIdx
                    }
                } else {
                    listOf(word to originalIdx)
                }
            } else {
                listOf(word to originalIdx)
            }
        }.let { data -> data.map { it.first } to data.map { it.second } }
