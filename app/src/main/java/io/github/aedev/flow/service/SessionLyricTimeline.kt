package io.github.aedev.flow.service

import io.github.aedev.flow.data.lyrics.LyricsEntry

/** Which lyric line belongs at a playback position, for the music session. Positions are the player's own. */
internal class SessionLyricTimeline(
    entries: List<LyricsEntry>,
    private val offsetMs: Long,
) {
    private val lines = entries.filterNot { it.isBackground }.sortedBy { it.time }

    /** The line sung at [positionMs], or null before the first line and during a break the lyrics mark. */
    fun lineAt(positionMs: Long): String? {
        val lyricTime = positionMs + offsetMs
        val text =
            lines
                .lastOrNull { it.time <= lyricTime }
                ?.text
                ?.trim()
                .orEmpty()
        return text.takeIf { it.any(Char::isLetterOrDigit) }
    }

    /** The player position of the next line after [positionMs], or null after the last one. */
    fun nextChangeAfter(positionMs: Long): Long? {
        val lyricTime = positionMs + offsetMs
        return lines.firstOrNull { it.time > lyricTime }?.let { it.time - offsetMs }
    }
}
