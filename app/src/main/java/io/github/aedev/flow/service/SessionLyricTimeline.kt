package io.github.aedev.flow.service

import io.github.aedev.flow.data.lyrics.LyricsEntry
import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MAX_LINE_WIDTH = 24.0
private const val WIDE_CHARACTER_WEIGHT = 1.75
private const val MAX_PIECES = 4
private const val MIN_PIECE_MS = 700L

/** A slow singer's pace, so a line followed by a long gap doesn't stretch its parts across the gap. */
private const val MAX_MS_PER_CHARACTER = 100L

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
) {
    private val pieces = pieces(entries.filterNot { it.isBackground }.sortedBy { it.time })

    /** The text sung at [positionMs], or null before the first line and during a break the lyrics mark. */
    fun lineAt(positionMs: Long): String? {
        val lyricTime = positionMs + offsetMs
        val text = pieces.lastOrNull { it.timeMs <= lyricTime }?.text.orEmpty()
        return text.takeIf { it.any(Char::isLetterOrDigit) }
    }

    /** The player position of the next change after [positionMs], or null after the last one. */
    fun nextChangeAfter(positionMs: Long): Long? {
        val lyricTime = positionMs + offsetMs
        return pieces.firstOrNull { it.timeMs > lyricTime }?.let { it.timeMs - offsetMs }
    }
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

private fun pieces(lines: List<LyricsEntry>): List<LyricPiece> =
    lines.flatMapIndexed { index, line ->
        val text = line.text.trim()
        val spokenMs = lines.getOrNull(index + 1)?.time?.minus(line.time) ?: Long.MAX_VALUE
        val spanMs = minOf(spokenMs, text.length * MAX_MS_PER_CHARACTER)
        val parts = splitToFit(text, (spanMs / MIN_PIECE_MS).toInt().coerceIn(1, MAX_PIECES))
        var consumed = 0
        parts.map { part ->
            val start = text.indexOf(part, consumed).coerceAtLeast(consumed)
            consumed = start + part.length
            val timeMs =
                if (start == 0) {
                    line.time
                } else {
                    wordStartAt(line, start) ?: (line.time + spanMs * start / text.length.coerceAtLeast(1))
                }
            LyricPiece(part, timeMs)
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
