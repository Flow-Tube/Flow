package io.github.aedev.flow.ui.components.musicplayer.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricsCopyTextTest {
    @Test
    fun `lines come first, one per row, then the credit`() {
        val text = lyricsCopyText(listOf("First line", "Second line"), title = "Song", artist = "Artist")
        assertThat(text).isEqualTo("First line\nSecond line\n\nSong, Artist")
    }

    @Test
    fun `a missing artist leaves only the song in the credit`() {
        assertThat(lyricsCopyText(listOf("Line"), title = "Song", artist = " ")).isEqualTo("Line\n\nSong")
    }

    @Test
    fun `no song or artist leaves the lines alone`() {
        assertThat(lyricsCopyText(listOf("Line"), title = "", artist = "")).isEqualTo("Line")
    }
}
