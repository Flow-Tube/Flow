package io.github.aedev.flow.ui.components.videoplayer.motion

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.ui.components.videoplayer.MiniPlayerCorner
import org.junit.Test

class ClearDockedBarTest {
    private fun clear(
        x: Float,
        corner: MiniPlayerCorner,
        clearance: Float = 84f,
        barOnLeft: Boolean = true,
    ) = clearDockedBar(x, corner, width = 160f, screenWidth = 400f, clearance = clearance, barOnLeft = barOnLeft)

    @Test
    fun `the bottom corner on the bar's side moves in past the bar`() {
        assertThat(clear(8f, MiniPlayerCorner.BottomLeft)).isEqualTo(84f)
        assertThat(clear(232f, MiniPlayerCorner.BottomRight, barOnLeft = false)).isEqualTo(400f - 160f - 84f)
    }

    @Test
    fun `other corners and an undocked bar keep their place`() {
        assertThat(clear(232f, MiniPlayerCorner.BottomRight)).isEqualTo(232f)
        assertThat(clear(8f, MiniPlayerCorner.TopLeft)).isEqualTo(8f)
        assertThat(clear(8f, MiniPlayerCorner.BottomLeft, clearance = 0f)).isEqualTo(8f)
    }
}
