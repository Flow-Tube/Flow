package io.github.aedev.flow.ui.components.musicplayer.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricsCardExportTest {
    @Test
    fun `the card is 1080 px wide`() {
        with(LyricsCardExportDensity) {
            assertThat(LyricsCardWidth.roundToPx()).isEqualTo(LYRICS_CARD_EXPORT_WIDTH_PX)
        }
    }

    @Test
    fun `the sender's font scale does not reach the image`() {
        assertThat(LyricsCardExportDensity.fontScale).isEqualTo(1f)
    }
}
