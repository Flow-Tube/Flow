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
    fun `a long silence after a line-synced line gets a break row once a slow singer would be done`() {
        val line = LyricsEntry(84_730L, "x".repeat(40))
        val lines = buildLines(null, listOf(line, LyricsEntry(153_400L, "After the solo")))

        val solo = buildMergedLyricsList(lines).filterIsInstance<LyricsListItem.Indicator>().last()

        assertThat(solo.gapStartMs to solo.gapEndMs).isEqualTo(94_730L to 153_400L)
    }

    @Test
    fun `a line-synced gap under the limit gets no break row`() {
        val lines = buildLines(null, listOf(LyricsEntry(10_000L, "A short line"), LyricsEntry(24_000L, "Next")))

        val breaks = buildMergedLyricsList(lines).filterIsInstance<LyricsListItem.Indicator>()

        assertThat(breaks.map { it.gapStartMs }).containsExactly(0L)
    }

    @Test
    fun `a line-synced line stops being active when its break starts`() {
        val lines = listOf(LyricsEntry(10_000L, "x".repeat(40)), LyricsEntry(80_000L, "Next"), LyricsEntry(90_000L, "Last"))

        assertThat(findActiveLineIndices(lines, 15_000L)).containsExactly(0)
        assertThat(findActiveLineIndices(lines, 30_000L)).isEmpty()
        assertThat(findActiveLineIndices(lines, 80_500L)).containsExactly(1)
    }

    @Test
    fun `the last line stays active to the end of the song`() {
        val lines = listOf(LyricsEntry(10_000L, "One"), LyricsEntry(20_000L, "Two"), LyricsEntry(30_000L, "Last"))

        assertThat(findActiveLineIndices(lines, 200_000L)).containsExactly(2)
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
