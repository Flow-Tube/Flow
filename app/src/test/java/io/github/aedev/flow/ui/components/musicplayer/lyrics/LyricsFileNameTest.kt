package io.github.aedev.flow.ui.components.musicplayer.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricsFileNameTest {
    @Test
    fun `artist leads the title`() {
        assertThat(lyricsFileName(title = "Song", artist = "Band", extension = "png")).isEqualTo("Band - Song.png")
    }

    @Test
    fun `characters no folder accepts are taken out`() {
        assertThat(lyricsFileName(title = "A\\B/C:D", artist = "AC/DC", extension = "lrc")).isEqualTo("AC DC - A B C D.lrc")
    }

    @Test
    fun `a song with no title or artist is still named`() {
        assertThat(lyricsFileName(title = " ", artist = "", extension = "png")).isEqualTo("lyrics.png")
    }
}
