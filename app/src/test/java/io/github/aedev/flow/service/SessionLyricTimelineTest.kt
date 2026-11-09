package io.github.aedev.flow.service

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.lyrics.LyricsEntry
import io.github.aedev.flow.data.lyrics.WordTimestamp
import org.junit.Test

class SessionLyricTimelineTest {
    private fun timeline(
        entries: List<LyricsEntry>,
        offsetMs: Long,
    ) = SessionLyricTimeline(entries, offsetMs) { line, count -> "$line (x$count)" }

    private val entries =
        listOf(
            LyricsEntry(5_000L, "First line"),
            LyricsEntry(7_000L, "Backing", isBackground = true),
            LyricsEntry(10_000L, "Second line"),
            LyricsEntry(20_000L, ""),
            LyricsEntry(30_000L, "♪"),
            LyricsEntry(40_000L, "Last line"),
        )

    @Test
    fun `the artist line keeps the song name in view`() {
        assertThat(sessionLyricArtist("The Feels", "TWICE")).isEqualTo("The Feels - TWICE")
        assertThat(sessionLyricArtist("The Feels", null)).isEqualTo("The Feels")
        assertThat(sessionLyricArtist("The Feels", "")).isEqualTo("The Feels")
    }

    @Test
    fun `the timing setting snaps to its steps and stays in range`() {
        assertThat(SessionLyricDelay.snap(-790f)).isEqualTo(-800)
        assertThat(SessionLyricDelay.snap(90f)).isEqualTo(0)
        assertThat(SessionLyricDelay.snap(110f)).isEqualTo(200)
        assertThat(SessionLyricDelay.snap(-9_000f)).isEqualTo(-SessionLyricDelay.MAX_MS)
        assertThat(SessionLyricDelay.snap(9_000f)).isEqualTo(SessionLyricDelay.MAX_MS)
    }

    @Test
    fun `a negative delay shows a line early and a positive one shows it late`() {
        val early = timeline(entries, 800L)
        val late = timeline(entries, -800L)

        assertThat(early.lineAt(4_200L)).isEqualTo("First line")
        assertThat(early.nextChangeAfter(4_200L)).isEqualTo(9_200L)
        assertThat(late.lineAt(5_500L)).isEqualTo("♪")
        assertThat(late.lineAt(5_800L)).isEqualTo("First line")
        assertThat(late.nextChangeAfter(5_800L)).isEqualTo(10_800L)
    }

    @Test
    fun `no line before the first one starts after a short intro`() {
        val timeline = timeline(listOf(LyricsEntry(4_000L, "First line")), 0L)

        assertThat(timeline.lineAt(3_999L)).isNull()
        assertThat(timeline.nextChangeAfter(0L)).isEqualTo(4_000L)
    }

    @Test
    fun `one music note stays through a long intro`() {
        val timeline = timeline(entries, 0L)

        assertThat(timeline.lineAt(0L)).isEqualTo("♪")
        assertThat(timeline.nextChangeAfter(0L)).isEqualTo(5_000L)
        assertThat(timeline.lineAt(3_000L)).isEqualTo("♪")
        assertThat(timeline.nextChangeAfter(3_000L)).isEqualTo(5_000L)
    }

    @Test
    fun `a line lasts until the next and skips background vocals`() {
        val timeline = timeline(entries, 0L)

        assertThat(timeline.lineAt(5_000L)).isEqualTo("First line")
        assertThat(timeline.lineAt(9_999L)).isEqualTo("First line")
        assertThat(timeline.nextChangeAfter(5_000L)).isEqualTo(10_000L)
    }

    @Test
    fun `blank and symbol lines become one break with one music note`() {
        val marked = listOf(LyricsEntry(0L, "Sung"), LyricsEntry(1_000L, ""), LyricsEntry(5_000L, "♪"), LyricsEntry(12_000L, "Next"))
        val timeline = timeline(marked, 0L)

        assertThat(timeline.lineAt(1_000L)).isEqualTo("♪")
        assertThat(timeline.nextChangeAfter(1_000L)).isEqualTo(12_000L)
        assertThat(timeline.lineAt(7_000L)).isEqualTo("♪")
        assertThat(timeline.nextChangeAfter(10_000L)).isEqualTo(12_000L)
        assertThat(timeline.lineAt(12_000L)).isEqualTo("Next")
    }

    @Test
    fun `a long silence the lyrics do not mark is a break after the line is sung`() {
        val timeline = timeline(listOf(LyricsEntry(0L, "Twenty characters ok"), LyricsEntry(10_000L, "Next")), 0L)

        assertThat(timeline.lineAt(1_999L)).isEqualTo("Twenty characters ok")
        assertThat(timeline.nextChangeAfter(0L)).isEqualTo(2_000L)
        assertThat(timeline.lineAt(2_000L)).isEqualTo("♪")
    }

    @Test
    fun `a short silence keeps the line`() {
        val timeline = timeline(listOf(LyricsEntry(0L, "Twenty characters ok"), LyricsEntry(6_999L, "Next")), 0L)

        assertThat(timeline.lineAt(6_998L)).isEqualTo("Twenty characters ok")
    }

    @Test
    fun `timed words decide when a line ends`() {
        val words = listOf(WordTimestamp("Long", 0L, 6_000L), WordTimestamp("note", 6_000L, 8_000L))
        val timeline = timeline(listOf(LyricsEntry(0L, "Long note", words = words), LyricsEntry(14_000L, "Next")), 0L)

        assertThat(timeline.lineAt(7_999L)).isEqualTo("Long note")
        assertThat(timeline.lineAt(8_000L)).isEqualTo("♪")
    }

    @Test
    fun `a repeated line says how many times it has been sung`() {
        val repeated =
            listOf(
                LyricsEntry(0L, "Hold on"),
                LyricsEntry(2_000L, "hold on "),
                LyricsEntry(4_000L, "Hold on"),
                LyricsEntry(6_000L, "Let go"),
                LyricsEntry(8_000L, "Hold on"),
            )
        val timeline = timeline(repeated, 0L)

        assertThat(timeline.lineAt(0L)).isEqualTo("Hold on")
        assertThat(timeline.lineAt(2_000L)).isEqualTo("hold on (x2)")
        assertThat(timeline.lineAt(4_000L)).isEqualTo("Hold on (x3)")
        assertThat(timeline.lineAt(6_000L)).isEqualTo("Let go")
        assertThat(timeline.lineAt(8_000L)).isEqualTo("Hold on")
    }

    @Test
    fun `a line sung again with more words after it counts as a repeat`() {
        val kamli =
            listOf(
                LyricsEntry(18_880L, "तद मैं कमली कहलावांगी"),
                LyricsEntry(25_010L, "तद मैं कमली कहलावांगी (माहिया माहिया)"),
                LyricsEntry(51_080L, "मेरे माहिया सनम जानम"),
            )
        val timeline = timeline(kamli, 0L)

        assertThat(timeline.lineAt(19_000L)).isEqualTo("तद मैं कमली कहलावांगी")
        assertThat(timeline.lineAt(25_100L)).isEqualTo("तद मैं कमली कहलावांगी (x2)")
        assertThat(timeline.lineAt(27_500L)).isEqualTo("(माहिया माहिया)")
    }

    @Test
    fun `a line that differs only by a vowel sign is not a repeat`() {
        val timeline = timeline(listOf(LyricsEntry(1_000L, "कमल"), LyricsEntry(2_000L, "कमली"), LyricsEntry(3_000L, "end")), 0L)

        assertThat(timeline.lineAt(2_100L)).isEqualTo("कमली")
    }

    @Test
    fun `punctuation does not hide a repeat`() {
        val timeline = timeline(listOf(LyricsEntry(1_000L, "Hold on,"), LyricsEntry(2_000L, "hold on!"), LyricsEntry(3_000L, "end")), 0L)

        assertThat(timeline.lineAt(2_100L)).isEqualTo("hold on! (x2)")
    }

    @Test
    fun `a blank line ends a run of repeats`() {
        val lines =
            listOf(LyricsEntry(1_000L, "Hold on"), LyricsEntry(2_000L, ""), LyricsEntry(9_000L, "Hold on"), LyricsEntry(9_900L, "end"))

        assertThat(timeline(lines, 0L).lineAt(9_100L)).isEqualTo("Hold on")
    }

    @Test
    fun `a repeated long line marks only its last part`() {
        val timeline = timeline(listOf(LyricsEntry(0L, longLine), LyricsEntry(6_000L, longLine), LyricsEntry(12_000L, "Next")), 0L)
        val second = timeline.nextChangeAfter(6_000L)!!

        assertThat(timeline.lineAt(6_000L)).isEqualTo("You can turn me on with")
        assertThat(timeline.lineAt(second)).isEqualTo("just a touch, baby (x2)")
    }

    @Test
    fun `the offset moves lines earlier and the wait is in player time`() {
        val timeline = timeline(entries, 1_000L)

        assertThat(timeline.lineAt(4_000L)).isEqualTo("First line")
        assertThat(timeline.nextChangeAfter(4_000L)).isEqualTo(9_000L)
    }

    @Test
    fun `one music note stays after the last line`() {
        val timeline = timeline(entries, 0L)

        assertThat(timeline.lineAt(40_899L)).isEqualTo("Last line")
        assertThat(timeline.lineAt(40_900L)).isEqualTo("♪")
        assertThat(timeline.lineAt(60_000L)).isEqualTo("♪")
        assertThat(timeline.nextChangeAfter(41_000L)).isNull()
    }

    @Test
    fun `no lyrics means no change`() {
        assertThat(timeline(emptyList(), 0L).lineAt(0L)).isNull()
        assertThat(timeline(emptyList(), 0L).nextChangeAfter(0L)).isNull()
    }

    private val longLine = "You can turn me on with just a touch, baby"

    @Test
    fun `a line that fits stays whole`() {
        val timeline = timeline(listOf(LyricsEntry(0L, "I'm running out of time"), LyricsEntry(5_000L, "Next")), 0L)

        assertThat(timeline.lineAt(4_999L)).isEqualTo("I'm running out of time")
        assertThat(timeline.nextChangeAfter(0L)).isEqualTo(5_000L)
    }

    @Test
    fun `a long line is shown in balanced parts that cut no word`() {
        val timeline = timeline(listOf(LyricsEntry(0L, longLine), LyricsEntry(6_000L, "Next")), 0L)
        val second = timeline.nextChangeAfter(0L)!!

        assertThat(timeline.lineAt(0L)).isEqualTo("You can turn me on with")
        assertThat(timeline.lineAt(second)).isEqualTo("just a touch, baby")
        assertThat(second).isIn(Range.open(0L, 6_000L))
    }

    @Test
    fun `the second part starts when its word is sung if the words are timed`() {
        val words = longLine.split(" ").mapIndexed { index, word -> WordTimestamp(word, 200L + index * 300L, 500L + index * 300L) }
        val timeline = timeline(listOf(LyricsEntry(100L, longLine, words = words), LyricsEntry(6_000L, "Next")), 0L)

        assertThat(timeline.nextChangeAfter(100L)).isEqualTo(words[6].startTime)
    }

    @Test
    fun `a long gap after a line does not stretch its parts`() {
        val timeline = timeline(listOf(LyricsEntry(0L, longLine), LyricsEntry(60_000L, "Next")), 0L)

        assertThat(timeline.nextChangeAfter(0L)!!).isLessThan(longLine.length * 100L)
    }

    @Test
    fun `a line sung too fast to read in parts stays whole`() {
        val timeline = timeline(listOf(LyricsEntry(0L, longLine), LyricsEntry(2_900L, "Next")), 0L)

        assertThat(timeline.lineAt(0L)).isEqualTo(longLine)
        assertThat(timeline.nextChangeAfter(0L)).isEqualTo(2_900L)
    }

    @Test
    fun `a very long line goes into more parts and loses no text`() {
        val text = "Live vicariously through me, write my life like it's a movie, like it's a movie"
        val timeline = timeline(listOf(LyricsEntry(0L, text), LyricsEntry(10_000L, "Next")), 0L)
        val parts = mutableListOf(timeline.lineAt(0L)!!)
        val starts = mutableListOf(0L)
        var at = timeline.nextChangeAfter(0L)!!
        while (at < 10_000L) {
            parts += timeline.lineAt(at)!!
            starts += at
            at = timeline.nextChangeAfter(at)!!
        }

        assertThat(parts.size).isGreaterThan(2)
        starts.zipWithNext { start, next -> assertThat(next - start).isAtLeast(1_500L) }
        assertThat(parts.joinToString(" ")).isEqualTo(text)
        parts.forEach { assertThat(it.length).isAtMost(24) }
    }

    @Test
    fun `wide characters count for more and a line without spaces still splits`() {
        val text = "君の名前を何度も呼んでいるこの夜の中で"
        val timeline = timeline(listOf(LyricsEntry(0L, text), LyricsEntry(6_000L, "Next")), 0L)
        val second = timeline.nextChangeAfter(0L)!!

        assertThat(second).isLessThan(6_000L)
        assertThat(timeline.lineAt(0L) + timeline.lineAt(second)).isEqualTo(text)
    }

    @Test
    fun `the offset moves the parts too`() {
        val entries = listOf(LyricsEntry(5_000L, longLine), LyricsEntry(11_000L, "Next"))
        val plain = timeline(entries, 0L).nextChangeAfter(5_000L)!!

        assertThat(timeline(entries, 1_000L).nextChangeAfter(4_000L)).isEqualTo(plain - 1_000L)
    }
}
