package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricsCardStylesTest {
    private val slate = Color(red = 72, green = 80, blue = 112)
    private val nearSlate = Color(red = 80, green = 88, blue = 116)
    private val salmon = Color(red = 255, green = 141, blue = 131)

    @Test
    fun `theme roles that land on the artwork colour are left out`() {
        val styles =
            distinctCardStyles(hasCover = true) {
                when (it) {
                    LyricsCardStyle.ARTWORK -> slate
                    LyricsCardStyle.SURFACE, LyricsCardStyle.SECONDARY_CONTAINER -> nearSlate
                    LyricsCardStyle.PRIMARY, LyricsCardStyle.TERTIARY -> salmon
                    else -> Color(red = 20 * it.ordinal, green = 0, blue = 0)
                }
            }
        assertThat(styles).containsNoneOf(LyricsCardStyle.SURFACE, LyricsCardStyle.SECONDARY_CONTAINER, LyricsCardStyle.TERTIARY)
        assertThat(styles).containsAtLeast(LyricsCardStyle.ARTWORK, LyricsCardStyle.PRIMARY).inOrder()
    }

    @Test
    fun `colorful is offered beside the artwork colour it shares a ground with`() {
        val styles = distinctCardStyles(hasCover = true) { slate }
        assertThat(styles).containsExactly(LyricsCardStyle.ARTWORK, LyricsCardStyle.COLORFUL).inOrder()
    }

    @Test
    fun `colorful is not offered without a cover`() {
        val styles = distinctCardStyles(hasCover = false) { slate }
        assertThat(styles).containsExactly(LyricsCardStyle.ARTWORK)
    }
}
