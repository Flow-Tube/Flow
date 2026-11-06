package io.github.aedev.flow.service

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.lyrics.LyricsEntry
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
}
