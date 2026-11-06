package io.github.aedev.flow.ui.components.musicplayer.lyrics

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.lyrics.LyricsEntry
import io.github.aedev.flow.data.lyrics.WordTimestamp
import org.junit.Test

class LyricsBreaksAndRomanizedWordsTest {
    private val sung = LyricsEntry(10_000L, "Sung")
    private val synced = listOf(sung, LyricsEntry(20_000L, "Sung too"), LyricsEntry(30_000L, "Sung again"))

    @Test
    fun `a marked break in line-synced lyrics gets a break row`() {
        val lines = buildLines(null, listOf(sung, LyricsEntry(14_000L, ""), LyricsEntry(69_000L, "After the break")))

        val breaks = buildMergedLyricsList(lines).filterIsInstance<LyricsListItem.Indicator>()

        assertThat(breaks.map { it.gapStartMs to it.gapEndMs }).contains(14_000L to 69_000L)
    }

    @Test
    fun `an unmarked gap between line-synced lines gets no break row`() {
        val lines = buildLines(null, listOf(LyricsEntry(10_000L, "Sung"), LyricsEntry(69_000L, "After")))

        val breaks = buildMergedLyricsList(lines).filterIsInstance<LyricsListItem.Indicator>()

        assertThat(breaks.map { it.gapStartMs }).containsExactly(0L)
    }

    @Test
    fun `a blank line too short for a break row is dropped`() {
        val lines = buildLines(null, synced + LyricsEntry(31_000L, "") + LyricsEntry(33_000L, "Quick"))

        assertThat(lines.drop(1).map { it.text }).doesNotContain("")
    }

    @Test
    fun `a blank line at the end of the song is dropped`() {
        val lines = buildLines(null, synced + LyricsEntry(40_000L, ""))

        assertThat(lines.drop(1).map { it.text }).doesNotContain("")
    }

    @Test
    fun `every blank line left has a row for the list to follow`() {
        val lines = buildLines(null, listOf(sung, LyricsEntry(14_000L, ""), LyricsEntry(69_000L, "After")))
        val items = buildMergedLyricsList(lines)

        lines.forEachIndexed { index, line ->
            if (line.text.isBlank()) {
                assertThat(items.filterIsInstance<LyricsListItem.Indicator>().map { it.afterLineIndex }).contains(index)
            }
        }
    }

    @Test
    fun `romanized words take the source's timings when the counts match`() {
        val words = listOf(WordTimestamp("जो", 100L, 200L), WordTimestamp("अब", 200L, 300L))

        assertThat(romanizedWordTimings("jo aba", words))
            .containsExactly(WordTimestamp("jo", 100L, 200L), WordTimestamp("aba", 200L, 300L))
            .inOrder()
    }

    @Test
    fun `romanized words have no timings when the counts differ`() {
        val perCharacter = listOf(WordTimestamp("你", 100L, 200L), WordTimestamp("好", 200L, 300L))

        assertThat(romanizedWordTimings("nihao", perCharacter)).isNull()
    }
}
