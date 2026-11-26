package io.github.aedev.flow.ui.components.musicplayer.lyrics

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.lyrics.LyricsEntry
import org.junit.Test

class LyricsScrollMathTest {
    private val metrics = LyricsRowMetrics(lineHeightPx = 68f, indicatorHeightPx = 72f, constraintLineHeightPx = 120f, gapPx = 16f)
    private val items = (0 until 6).map { LyricsListItem.Line(it, LyricsEntry(it * 1_000L, "line $it")) }

    @Test
    fun `a scrolled list still moves rows to targets that change`() {
        assertThat(lyricsRowMove(isInitialLayout = false, motionEnabled = true, autoScroll = false)).isEqualTo(LyricsRowMove.FOLLOW)
    }

    @Test
    fun `a line change cascades while the panel follows the song`() {
        assertThat(lyricsRowMove(isInitialLayout = false, motionEnabled = true, autoScroll = true)).isEqualTo(LyricsRowMove.CASCADE)
    }

    @Test
    fun `the first layout and reduced motion snap`() {
        assertThat(lyricsRowMove(isInitialLayout = true, motionEnabled = true, autoScroll = false)).isEqualTo(LyricsRowMove.SNAP)
        assertThat(lyricsRowMove(isInitialLayout = false, motionEnabled = false, autoScroll = true)).isEqualTo(LyricsRowMove.SNAP)
    }

    @Test
    fun `a row measured taller than the guess pushes only the rows after it`() {
        val guessed = lyricsRowOffsets(items, heights = mapOf(0 to 68, 1 to 68), activeIndex = 0, metrics = metrics)
        val measured = lyricsRowOffsets(items, heights = mapOf(0 to 68, 1 to 68, 2 to 110), activeIndex = 0, metrics = metrics)

        assertThat(measured[2]).isEqualTo(guessed[2])
        assertThat(measured[3]!! - guessed[3]!!).isEqualTo(42f)
        assertThat(measured[5]!! - guessed[5]!!).isEqualTo(42f)
    }

    @Test
    fun `a row above the active one measured taller moves only itself and the rows above it`() {
        val guessed = lyricsRowOffsets(items, heights = emptyMap(), activeIndex = 4, metrics = metrics)
        val measured = lyricsRowOffsets(items, heights = mapOf(2 to 110), activeIndex = 4, metrics = metrics)

        assertThat(measured[3]).isEqualTo(guessed[3])
        assertThat(guessed[2]!! - measured[2]!!).isEqualTo(42f)
        assertThat(guessed[0]!! - measured[0]!!).isEqualTo(42f)
    }
}
