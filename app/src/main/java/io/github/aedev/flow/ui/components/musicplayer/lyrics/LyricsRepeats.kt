package io.github.aedev.flow.ui.components.musicplayer.lyrics

import io.github.aedev.flow.data.lyrics.LyricsEntry

/**
 * How many lines in a row, ending at [index], are the same words: 1 for a line that doesn't repeat
 * the one before it. Case, spacing and punctuation don't count; a blank break or a different line
 * ends the run.
 */
internal fun lyricRepeatCount(
    lines: List<LyricsEntry>,
    index: Int,
): Int {
    val key = lines.getOrNull(index)?.let { repeatKey(it.text) }?.takeIf { it.isNotEmpty() } ?: return 1
    var count = 1
    var previous = index - 1
    while (previous >= 0 && repeatKey(lines[previous].text) == key) {
        count++
        previous--
    }
    return count
}

private val Ignored =
    setOf(
        CharCategory.SPACE_SEPARATOR,
        CharCategory.CONNECTOR_PUNCTUATION,
        CharCategory.DASH_PUNCTUATION,
        CharCategory.START_PUNCTUATION,
        CharCategory.END_PUNCTUATION,
        CharCategory.INITIAL_QUOTE_PUNCTUATION,
        CharCategory.FINAL_QUOTE_PUNCTUATION,
        CharCategory.OTHER_PUNCTUATION,
    )

// Only punctuation and spaces go: vowel signs are marks, not letters, and they change the word.
private fun repeatKey(text: String): String = text.lowercase().filterNot { it.isWhitespace() || it.category in Ignored }
