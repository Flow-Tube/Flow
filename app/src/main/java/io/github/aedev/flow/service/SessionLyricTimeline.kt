package io.github.aedev.flow.service

import io.github.aedev.flow.data.lyrics.LyricsEntry
import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MAX_LINE_WIDTH = 24.0
private const val WIDE_CHARACTER_WEIGHT = 1.75
private const val MAX_PIECES = 4

/** Android's media card fades both text lines for about 770 ms on every change, so a part has to outlast that. */
private const val MIN_PIECE_MS = 1_500L

/** A slow singer's pace, so a line followed by a long gap doesn't stretch its parts across the gap. A wide character counts as more. */
private const val MAX_MS_PER_CHARACTER = 100L

/** Lyrics often leave a solo unmarked, so a silence this long after a line's words counts as a break too. */
private const val MIN_BREAK_MS = 5_000L

/** One note for the whole break: every change to the title replays the media card's fade on both text lines. */
private const val BREAK_NOTE = "♪"

/** The steps of the setting that lets people move lock screen lines if they feel late or early. */
internal object SessionLyricDelay {
    const val MAX_MS = 2_000
    const val STEP_MS = 200

    fun snap(delayMs: Float): Int = ((delayMs / STEP_MS).roundToInt() * STEP_MS).coerceIn(-MAX_MS, MAX_MS)
}

/**
 * Which lyric text belongs at a playback position, for the music session. Positions are the player's
 * own. Media cards cut the title off after a line or so, so a long line is shown in parts, one after
 * the other.
 */
internal class SessionLyricTimeline(
    entries: List<LyricsEntry>,
    private val offsetMs: Long,
    repeatedLine: (line: String, count: Int) -> String,
) {
    private val lines = entries.filterNot { it.isBackground }.sortedBy { it.time }
    private val pieces = pieces(lines, repeatedLine)
    private val breaks = breaks(lines)

    /** The text sung at [positionMs], a music note during a break, or null just before the first line. */
    fun lineAt(positionMs: Long): String? {
        val lyricTime = positionMs + offsetMs
        if (breakAt(lyricTime) != null) return BREAK_NOTE
        return pieces.lastOrNull { it.timeMs <= lyricTime }?.text?.takeIf { it.isSung }
    }

    /** The player position of the next change after [positionMs], or null after the last one. */
    fun nextChangeAfter(positionMs: Long): Long? {
        val lyricTime = positionMs + offsetMs
        val current = breakAt(lyricTime)
        val next =
            if (current != null) {
                current.endMs.takeIf { it != Long.MAX_VALUE }
            } else {
                listOfNotNull(
                    pieces.firstOrNull { it.timeMs > lyricTime }?.timeMs,
                    breaks.firstOrNull { it.startMs > lyricTime }?.startMs,
                ).minOrNull()
            }
        return next?.minus(offsetMs)
    }

    private fun breakAt(lyricTime: Long): LyricBreak? = breaks.firstOrNull { lyricTime >= it.startMs && lyricTime < it.endMs }
}

/** The artist line shown under a lyric, which keeps the song's name in view. */
internal fun sessionLyricArtist(
    title: CharSequence?,
    artist: CharSequence?,
): String = listOfNotNull(title, artist).filter { it.isNotBlank() }.joinToString(" - ")

private class LyricPiece(
    val text: String,
    val timeMs: Long,
)

private class LyricBreak(
    val startMs: Long,
    val endMs: Long,
)

private val String.isSung: Boolean
    get() = any(Char::isLetterOrDigit)

/** The parts of the song with no words: a long intro, a line the lyrics leave blank or a long silence. */
private fun breaks(lines: List<LyricsEntry>): List<LyricBreak> {
    val first = lines.firstOrNull() ?: return emptyList()
    val found = mutableListOf<LyricBreak>()
    if (first.time >= MIN_BREAK_MS) found += LyricBreak(0L, first.time)
    lines.forEachIndexed { index, line ->
        val nextMs = lines.getOrNull(index + 1)?.time ?: Long.MAX_VALUE
        val text = line.text.trim()
        if (!text.isSung) {
            found += LyricBreak(line.time, nextMs)
        } else {
            val sungUntil = line.words?.maxOfOrNull { it.endTime } ?: (line.time + text.length * MAX_MS_PER_CHARACTER)
            if (nextMs - sungUntil >= MIN_BREAK_MS) found += LyricBreak(sungUntil, nextMs)
        }
    }
    return found.fold(emptyList()) { merged, next ->
        val last = merged.lastOrNull()
        if (last != null && next.startMs <= last.endMs) {
            merged.dropLast(1) + LyricBreak(last.startMs, maxOf(last.endMs, next.endMs))
        } else {
            merged + next
        }
    }
}

/** Case and punctuation don't make a line another line, but vowel signs do: कमल isn't कमली. */
private fun repeatKey(text: String): String = text.lowercase().replace(NotPartOfAWord, " ").trim()

private val NotPartOfAWord = Regex("[^\\p{L}\\p{M}\\p{N}]+")

/** How many times in a row the line at [index] has been sung, counting itself. */
private fun repeatCount(
    lines: List<LyricsEntry>,
    index: Int,
): Int {
    val text = lines[index].text.trim()
    if (!text.isSung) return 1
    val key = repeatKey(text)
    var first = index
    while (first > 0 && repeatKey(lines[first - 1].text) == key) first--
    return index - first + 1
}

/**
 * The parts of the lines in order, with their repeats counted. A part can repeat what was just
 * shown without its whole line doing so: "Kamli" followed by "Kamli (Mahiya)" shows "Kamli" twice.
 */
private fun pieces(
    lines: List<LyricsEntry>,
    repeatedLine: (line: String, count: Int) -> String,
): List<LyricPiece> {
    var previousKey = ""
    var previousCount = 1
    return lines.flatMapIndexed { index, line ->
        val text = line.text.trim()
        val spokenMs = lines.getOrNull(index + 1)?.time?.minus(line.time) ?: Long.MAX_VALUE
        val spanMs = minOf(spokenMs, (lineWidth(text) * MAX_MS_PER_CHARACTER).toLong())
        val parts = splitToFit(text, (spanMs / MIN_PIECE_MS).toInt().coerceIn(1, MAX_PIECES))
        val lineCount = repeatCount(lines, index)
        var consumed = 0
        parts.mapIndexed { partIndex, part ->
            val start = text.indexOf(part, consumed).coerceAtLeast(consumed)
            consumed = start + part.length
            val timeMs =
                if (start == 0) {
                    line.time
                } else {
                    wordStartAt(line, start) ?: (line.time + spanMs * start / text.length.coerceAtLeast(1))
                }
            val key = repeatKey(part)
            val count =
                when {
                    partIndex == parts.lastIndex && lineCount > 1 -> lineCount
                    part.isSung && key == previousKey -> previousCount + 1
                    else -> 1
                }
            previousKey = key
            previousCount = count
            LyricPiece(if (count > 1) repeatedLine(part, count) else part, timeMs)
        }
    }
}

/** Halves [text] at the break nearest its middle until every part fits or [maxParts] is reached. */
private fun splitToFit(
    text: String,
    maxParts: Int,
): List<String> {
    val parts = mutableListOf(text)
    while (parts.size < maxParts) {
        val widest = parts.indices.maxBy { lineWidth(parts[it]) }
        if (lineWidth(parts[widest]) <= MAX_LINE_WIDTH) break
        val at = balancedBreak(parts[widest]) ?: break
        val part = parts[widest]
        parts[widest] = part.substring(0, at).trim()
        parts.add(widest + 1, part.substring(at).trim())
    }
    return parts
}

/** Where to cut for two parts of about equal width: at a space when there is one, else between characters. */
private fun balancedBreak(text: String): Int? {
    val spaces = text.indices.filter { text[it].isWhitespace() && it > 0 }
    val breaks =
        spaces.ifEmpty {
            val characters = BreakIterator.getCharacterInstance().apply { setText(text) }
            generateSequence { characters.next().takeIf { it != BreakIterator.DONE } }.filter { it < text.length }.toList()
        }
    return breaks.minByOrNull { abs(lineWidth(text.substring(0, it)) - lineWidth(text.substring(it))) }
}

private fun lineWidth(text: String): Double =
    text.codePoints().toArray().sumOf { codePoint ->
        when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL,
            -> WIDE_CHARACTER_WEIGHT

            else -> 1.0
        }
    }

/** When the word at [offset] in the line's text starts, if the provider timed its words. */
private fun wordStartAt(
    line: LyricsEntry,
    offset: Int,
): Long? {
    var cursor = 0
    line.words.orEmpty().forEach { word ->
        val own = word.text.trim()
        if (own.isEmpty()) return@forEach
        val start = line.text.trim().indexOf(own, cursor)
        if (start < 0) return null
        if (start >= offset) return word.startTime
        cursor = start + own.length
    }
    return null
}
