package io.github.aedev.flow.ui.components.shared

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SplitSideShapeTest {
    private val density = Density(1f)
    private val size = Size(120f, 68f)

    @Test
    fun `previous is fully round on its start side and nearly flat towards play pause`() {
        val shape = splitSideShape(leading = true, pressedFraction = 0f)

        assertThat(shape.topStart.toPx(size, density)).isAtLeast(34f)
        assertThat(shape.bottomStart.toPx(size, density)).isAtLeast(34f)
        assertThat(shape.topEnd.toPx(size, density)).isEqualTo(8f)
        assertThat(shape.bottomEnd.toPx(size, density)).isEqualTo(8f)
    }

    @Test
    fun `next mirrors previous`() {
        val shape = splitSideShape(leading = false, pressedFraction = 0f)

        assertThat(shape.topStart.toPx(size, density)).isEqualTo(8f)
        assertThat(shape.bottomStart.toPx(size, density)).isEqualTo(8f)
        assertThat(shape.topEnd.toPx(size, density)).isAtLeast(34f)
        assertThat(shape.bottomEnd.toPx(size, density)).isAtLeast(34f)
    }

    @Test
    fun `a press rounds the inner corner out`() {
        val pressed = splitSideShape(leading = true, pressedFraction = 1f)
        val halfway = splitSideShape(leading = true, pressedFraction = 0.5f)

        assertThat(pressed.topEnd.toPx(size, density)).isEqualTo(20f)
        assertThat(halfway.topEnd.toPx(size, density)).isEqualTo(14f)
    }

    @Test
    fun `the spring's undershoot never makes a corner negative`() {
        val shape = splitSideShape(leading = false, pressedFraction = -2f)

        assertThat(shape.topStart.toPx(size, density)).isEqualTo(0f)
    }
}
