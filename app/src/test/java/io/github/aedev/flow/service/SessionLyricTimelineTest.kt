package io.github.aedev.flow.service

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.lyrics.LyricsEntry
import io.github.aedev.flow.data.lyrics.WordTimestamp
import org.junit.Test

class SessionLyricTimelineTest {
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
        val early = SessionLyricTimeline(entries, 800L)
        val late = SessionLyricTimeline(entries, -800L)

        assertThat(early.lineAt(4_200L)).isEqualTo("First line")
        assertThat(early.nextChangeAfter(4_200L)).isEqualTo(9_200L)
        assertThat(late.lineAt(5_500L)).isNull()
        assertThat(late.lineAt(5_800L)).isEqualTo("First line")
        assertThat(late.nextChangeAfter(5_800L)).isEqualTo(10_800L)
    }

    @Test
    fun `no line before the first one starts`() {
        assertThat(SessionLyricTimeline(entries, 0L).lineAt(4_999L)).isNull()
    }

    @Test
    fun `a line lasts until the next and skips background vocals`() {
        val timeline = SessionLyricTimeline(entries, 0L)

        assertThat(timeline.lineAt(5_000L)).isEqualTo("First line")
        assertThat(timeline.lineAt(9_999L)).isEqualTo("First line")
        assertThat(timeline.nextChangeAfter(5_000L)).isEqualTo(10_000L)
    }

    @Test
    fun `a blank or symbol line shows the song title again`() {
        val timeline = SessionLyricTimeline(entries, 0L)

        assertThat(timeline.lineAt(21_000L)).isNull()
        assertThat(timeline.lineAt(31_000L)).isNull()
    }

    @Test
    fun `the offset moves lines earlier and the wait is in player time`() {
        val timeline = SessionLyricTimeline(entries, 1_000L)

        assertThat(timeline.lineAt(4_000L)).isEqualTo("First line")
        assertThat(timeline.nextChangeAfter(4_000L)).isEqualTo(9_000L)
    }

    @Test
    fun `nothing follows the last line`() {
        assertThat(SessionLyricTimeline(entries, 0L).nextChangeAfter(41_000L)).isNull()
    }

    private val longLine = "You can turn me on with just a touch, baby"

    @Test
    fun `a line that fits stays whole`() {
        val timeline = SessionLyricTimeline(listOf(LyricsEntry(0L, "I'm running out of time"), LyricsEntry(5_000L, "Next")), 0L)

        assertThat(timeline.lineAt(4_999L)).isEqualTo("I'm running out of time")
        assertThat(timeline.nextChangeAfter(0L)).isEqualTo(5_000L)
    }

    @Test
    fun `a long line is shown in balanced parts that cut no word`() {
        val timeline = SessionLyricTimeline(listOf(LyricsEntry(0L, longLine), LyricsEntry(6_000L, "Next")), 0L)
        val second = timeline.nextChangeAfter(0L)!!

        assertThat(timeline.lineAt(0L)).isEqualTo("You can turn me on with")
        assertThat(timeline.lineAt(second)).isEqualTo("just a touch, baby")
        assertThat(second).isIn(Range.open(0L, 6_000L))
    }

    @Test
    fun `the second part starts when its word is sung if the words are timed`() {
        val words = longLine.split(" ").mapIndexed { index, word -> WordTimestamp(word, 200L + index * 300L, 500L + index * 300L) }
        val timeline = SessionLyricTimeline(listOf(LyricsEntry(100L, longLine, words = words), LyricsEntry(6_000L, "Next")), 0L)

        assertThat(timeline.nextChangeAfter(100L)).isEqualTo(words[6].startTime)
    }

    @Test
    fun `a long gap after a line does not stretch its parts`() {
        val timeline = SessionLyricTimeline(listOf(LyricsEntry(0L, longLine), LyricsEntry(60_000L, "Next")), 0L)

        assertThat(timeline.nextChangeAfter(0L)!!).isLessThan(longLine.length * 100L)
    }

    @Test
    fun `a line sung too fast to read in parts stays whole`() {
        val timeline = SessionLyricTimeline(listOf(LyricsEntry(0L, longLine), LyricsEntry(1_000L, "Next")), 0L)

        assertThat(timeline.lineAt(0L)).isEqualTo(longLine)
        assertThat(timeline.nextChangeAfter(0L)).isEqualTo(1_000L)
    }

    @Test
    fun `a very long line goes into more parts and loses no text`() {
        val text = "Live vicariously through me, write my life like it's a movie, like it's a movie"
        val timeline = SessionLyricTimeline(listOf(LyricsEntry(0L, text), LyricsEntry(10_000L, "Next")), 0L)
        val parts = mutableListOf(timeline.lineAt(0L)!!)
        var at = timeline.nextChangeAfter(0L)!!
        while (at < 10_000L) {
            parts += timeline.lineAt(at)!!
            at = timeline.nextChangeAfter(at)!!
        }

        assertThat(parts.size).isGreaterThan(2)
        assertThat(parts.joinToString(" ")).isEqualTo(text)
        parts.forEach { assertThat(it.length).isAtMost(24) }
    }

    @Test
    fun `wide characters count for more and a line without spaces still splits`() {
        val text = "君の名前を何度も呼んでいるこの夜の中で"
        val timeline = SessionLyricTimeline(listOf(LyricsEntry(0L, text), LyricsEntry(6_000L, "Next")), 0L)
        val second = timeline.nextChangeAfter(0L)!!

        assertThat(second).isLessThan(6_000L)
        assertThat(timeline.lineAt(0L) + timeline.lineAt(second)).isEqualTo(text)
    }

    @Test
    fun `the offset moves the parts too`() {
        val entries = listOf(LyricsEntry(5_000L, longLine), LyricsEntry(11_000L, "Next"))
        val plain = SessionLyricTimeline(entries, 0L).nextChangeAfter(5_000L)!!

        assertThat(SessionLyricTimeline(entries, 1_000L).nextChangeAfter(4_000L)).isEqualTo(plain - 1_000L)
    }
}
