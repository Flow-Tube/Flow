package io.github.aedev.flow.ui.components.musicplayer.lyrics

import io.github.aedev.flow.data.lyrics.LyricsEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsShareSelectionTest {
    private val lines =
        listOf(
            LyricsEntry(time = 0L, text = ""),
            LyricsEntry(time = 1_000L, text = " first "),
            LyricsEntry(time = 2_000L, text = "second"),
            LyricsEntry(time = 3_000L, text = ""),
            LyricsEntry(time = 9_000L, text = "third"),
        )

    @Test
    fun `blank lines are left out and the rest are trimmed`() {
        assertEquals(listOf("first", "second", "third"), shareableLyricLines(lines))
    }

    @Test
    fun `a selection stays in song order`() {
        assertEquals(listOf(1, 4, 7), toggleShareLine(listOf(1, 7), 4))
    }

    @Test
    fun `a selected line is deselected`() {
        assertEquals(listOf(1, 7), toggleShareLine(listOf(1, 4, 7), 4))
    }

    @Test
    fun `a line past the limit is refused`() {
        val full = List(MAX_SHARE_LINES) { it }
        assertEquals(full, toggleShareLine(full, 9))
        assertEquals(full - 2, toggleShareLine(full, 2))
    }

    @Test
    fun `the picker opens at the line being sung`() {
        assertEquals(0, shareLineIndexAt(lines, 0L))
        assertEquals(1, shareLineIndexAt(lines, 2_500L))
        assertEquals(1, shareLineIndexAt(lines, 5_000L))
        assertEquals(2, shareLineIndexAt(lines, 60_000L))
    }
}
