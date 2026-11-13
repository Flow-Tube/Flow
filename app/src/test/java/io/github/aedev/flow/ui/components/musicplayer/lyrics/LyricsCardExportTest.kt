package io.github.aedev.flow.ui.components.musicplayer.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricsCardExportTest {
    @Test
    fun `the card is 1080 px wide at any font scale`() {
        listOf(0.85f, 1f, 1.3f, 2f).forEach { fontScale ->
            with(lyricsCardExportDensity(fontScale)) {
                assertThat(LyricsCardWidth.roundToPx()).isEqualTo(LYRICS_CARD_EXPORT_WIDTH_PX)
            }
        }
    }

    @Test
    fun `the user's font scale is kept`() {
        assertThat(lyricsCardExportDensity(1.3f).fontScale).isEqualTo(1.3f)
    }
}
