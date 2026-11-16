package io.github.aedev.flow.ui.components.musicplayer.lyrics

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.lyrics.LyricsEntry
import org.junit.Test

class LyricsRepeatsTest {
    private fun lines(vararg texts: String) = texts.mapIndexed { i, text -> LyricsEntry(time = i * 1_000L, text = text) }

    @Test
    fun `four repeats count up`() {
        val song = lines("So, oh, oh-oh-oh", "So, oh, oh-oh-oh", "So, oh, oh-oh-oh", "So, oh, oh-oh-oh")
        assertThat((0..3).map { lyricRepeatCount(song, it) }).containsExactly(1, 2, 3, 4).inOrder()
    }

    @Test
    fun `case punctuation and spacing don't break a run`() {
        val song = lines("So, oh, oh-oh-oh", "so oh oh oh oh", "SO OH OH-OH-OH!")
        assertThat(lyricRepeatCount(song, 2)).isEqualTo(3)
    }

    @Test
    fun `a blank break restarts the count`() {
        val song = lines("Again", "Again", "", "Again")
        assertThat(lyricRepeatCount(song, 1)).isEqualTo(2)
        assertThat(lyricRepeatCount(song, 3)).isEqualTo(1)
    }

    @Test
    fun `a different line between restarts the count`() {
        val song = lines("Again", "Something else", "Again")
        assertThat(lyricRepeatCount(song, 2)).isEqualTo(1)
    }

    @Test
    fun `a vowel sign makes a different line`() {
        val song = lines("कल", "काल")
        assertThat(lyricRepeatCount(song, 1)).isEqualTo(1)
    }

    @Test
    fun `a line of only punctuation never counts`() {
        val song = lines("...", "...")
        assertThat(lyricRepeatCount(song, 1)).isEqualTo(1)
    }

    @Test
    fun `no line or a line out of range is one`() {
        val song = lines("Again", "Again")
        assertThat(lyricRepeatCount(song, -1)).isEqualTo(1)
        assertThat(lyricRepeatCount(song, 2)).isEqualTo(1)
        assertThat(lyricRepeatCount(emptyList(), 0)).isEqualTo(1)
    }
}
