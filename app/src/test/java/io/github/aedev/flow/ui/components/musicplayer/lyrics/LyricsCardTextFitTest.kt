package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricsCardTextFitTest {
    @Test
    fun `short lines grow to the largest scale`() {
        assertThat(largestFittingScale(0.6f, 1.6f) { true }).isEqualTo(1.6f)
    }

    @Test
    fun `lines that never fit stay at the smallest scale`() {
        assertThat(largestFittingScale(0.6f, 1.6f) { false }).isEqualTo(0.6f)
    }

    @Test
    fun `the search lands just under the largest scale that fits`() {
        val scale = largestFittingScale(0.6f, 1.6f) { it <= 1.234f }
        assertThat(scale).isAtMost(1.234f)
        assertThat(scale).isWithin(0.001f).of(1.234f)
    }

    @Test
    fun `stacked height counts the gaps between lines only`() {
        assertThat(stackedHeight(listOf(100, 80, 60), spacing = 10)).isEqualTo(260)
        assertThat(stackedHeight(listOf(100), spacing = 10)).isEqualTo(100)
        assertThat(stackedHeight(emptyList(), spacing = 10)).isEqualTo(0)
    }

    @Test
    fun `scaling keeps type and line height in proportion`() {
        val scaled = TextStyle(fontSize = 24.sp, lineHeight = 32.sp).scaledBy(1.5f)
        assertThat(scaled.fontSize).isEqualTo(36.sp)
        assertThat(scaled.lineHeight).isEqualTo(48.sp)
    }

    @Test
    fun `an unspecified line height stays unspecified`() {
        assertThat(TextStyle(fontSize = 20.sp).scaledBy(2f).lineHeight).isEqualTo(TextUnit.Unspecified)
    }
}
