package io.github.aedev.flow.data.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricsBreakMarkersTest {
    @Test
    fun `a timed blank line stays as a blank entry`() {
        val entries = LyricsUtils.parseLyrics("[01:24.73] Sung line\n[01:38.95] \n[02:33.40] Next line")

        assertThat(entries.map { it.time to it.text })
            .containsExactly(84_730L to "Sung line", 98_950L to "", 153_400L to "Next line")
            .inOrder()
    }

    @Test
    fun `lyrics with only blank lines count as no lyrics`() {
        assertThat(LyricsUtils.parseLyrics("[00:01.00] \n[00:30.00] ")).isEmpty()
    }

    @Test
    fun `a blank line with no timestamp is not a break`() {
        val entries = LyricsUtils.parseLyrics("[00:01.00] One\n\n[00:30.00] Two")

        assertThat(entries.map { it.text }).containsExactly("One", "Two").inOrder()
    }

    @Test
    fun `break markers inside a non-music part don't count as sung lines`() {
        val lines = listOf(LyricsEntry(10_000L, ""), LyricsEntry(20_000L, ""), LyricsEntry(200_000L, "Sung"))

        assertThat(onVideoClock(lines, listOf(NonMusicPart(0L, 75_000L)), 400_000L)).isSameInstanceAs(lines)
    }
}
