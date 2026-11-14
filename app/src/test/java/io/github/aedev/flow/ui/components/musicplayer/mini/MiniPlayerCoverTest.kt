package io.github.aedev.flow.ui.components.musicplayer.mini

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MiniPlayerCoverTest {
    @Test
    fun `a track always gets the same shape`() {
        val first = miniCoverShapeIndex("dQw4w9WgXcQ", 6)

        assertThat(miniCoverShapeIndex("dQw4w9WgXcQ", 6)).isEqualTo(first)
        assertThat(first).isIn(0 until 6)
    }

    @Test
    fun `a negative hash still picks a shape`() {
        val id = "polygenelubricants"

        assertThat(id.hashCode()).isEqualTo(Int.MIN_VALUE)
        assertThat(miniCoverShapeIndex(id, 6)).isIn(0 until 6)
    }

    @Test
    fun `a moving cover holds each shape before it morphs`() {
        assertThat(alwaysMovingShape(startIndex = 2, seconds = 1f, count = 6)).isEqualTo(2)
        assertThat(alwaysMovingFraction(1f)).isEqualTo(0f)
        assertThat(alwaysMovingShape(startIndex = 2, seconds = 2.5f, count = 6)).isEqualTo(3)
        assertThat(alwaysMovingFraction(2.5f)).isEqualTo(0f)
    }

    @Test
    fun `a moving cover eases through the morph and wraps around the list`() {
        assertThat(alwaysMovingShape(startIndex = 5, seconds = 2.05f, count = 6)).isEqualTo(5)
        assertThat(alwaysMovingFraction(2.05f)).isWithin(0.001f).of(0.5f)
        assertThat(alwaysMovingShape(startIndex = 5, seconds = 2.6f, count = 6)).isEqualTo(0)
    }
}
