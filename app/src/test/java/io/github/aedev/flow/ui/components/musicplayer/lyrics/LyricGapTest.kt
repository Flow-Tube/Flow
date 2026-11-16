package io.github.aedev.flow.ui.components.musicplayer.lyrics

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.lyrics.LyricsEntry
import io.github.aedev.flow.data.lyrics.WordTimestamp
import org.junit.Test

class LyricGapTest {
    private fun sung(
        time: Long,
        lastWordEnd: Long,
    ) = LyricsEntry(
        time = time,
        text = "Baby, I'm yours",
        words = listOf(WordTimestamp("Baby,", time, time + 400L), WordTimestamp("yours", lastWordEnd - 500L, lastWordEnd)),
    )

    @Test
    fun `a line followed closely by the next one stays up`() {
        val lines = listOf(sung(10_000L, 12_000L), sung(14_000L, 16_000L))

        assertThat(lyricGapStartMs(lines, 0)).isNull()
    }

    @Test
    fun `a long instrumental after the last word clears the line a second later`() {
        val lines = listOf(sung(10_000L, 12_000L), sung(30_000L, 32_000L))

        assertThat(lyricGapStartMs(lines, 0)).isEqualTo(13_000L)
    }

    @Test
    fun `the song's last line clears after its last word`() {
        val lines = listOf(sung(10_000L, 12_000L))

        assertThat(lyricGapStartMs(lines, 0)).isEqualTo(13_000L)
    }

    @Test
    fun `a blank break marker clears at once`() {
        val lines = listOf(LyricsEntry(10_000L, "Line"), LyricsEntry(20_000L, ""), LyricsEntry(40_000L, "Next"))

        assertThat(lyricGapStartMs(lines, 1)).isEqualTo(20_000L)
    }

    @Test
    fun `a line-synced line without word timings stays up`() {
        val lines = listOf(LyricsEntry(10_000L, "Line"), LyricsEntry(40_000L, "Next"))

        assertThat(lyricGapStartMs(lines, 0)).isNull()
    }

    @Test
    fun `before the first line there is no line to clear`() {
        assertThat(lyricGapStartMs(listOf(LyricsEntry(10_000L, "Line")), -1)).isNull()
    }
}
