package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricsCardFrameTest {
    private val slate = Color(red = 72, green = 80, blue = 112)
    private val nearSlate = Color(red = 80, green = 88, blue = 116)
    private val ink = Color(red = 230, green = 225, blue = 229)
    private val salmon = Color(red = 255, green = 141, blue = 131)

    @Test
    fun `the cover accent frames the card when it differs from the ground`() {
        assertThat(lyricsBackdropColor(slate, listOf(salmon, ink))).isEqualTo(salmon)
    }

    @Test
    fun `an accent that reads as the ground is skipped`() {
        assertThat(lyricsBackdropColor(slate, listOf(nearSlate, ink, salmon))).isEqualTo(ink)
    }

    @Test
    fun `with nothing different the first candidate is kept`() {
        assertThat(lyricsBackdropColor(slate, listOf(nearSlate, slate))).isEqualTo(nearSlate)
    }
}
