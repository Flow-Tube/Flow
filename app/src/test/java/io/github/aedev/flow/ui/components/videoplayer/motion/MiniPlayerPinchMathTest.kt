package io.github.aedev.flow.ui.components.videoplayer.motion

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MiniPlayerPinchMathTest {
    @Test
    fun `inside the limits the size follows the fingers`() {
        assertThat(rubberBandScale(raw = 1.4f, min = 0.8f, max = 2f)).isEqualTo(1.4f)
        assertThat(rubberBandScale(raw = 0.8f, min = 0.8f, max = 2f)).isEqualTo(0.8f)
    }

    @Test
    fun `past the widest size only the fourth root of the overshoot shows`() {
        val damped = rubberBandScale(raw = 2f * 16f, min = 0.8f, max = 2f)
        assertThat(damped).isWithin(0.0001f).of(4f)
        assertThat(rubberBandScale(raw = 2.2f, min = 0.8f, max = 2f)).isLessThan(2.2f)
        assertThat(rubberBandScale(raw = 2.2f, min = 0.8f, max = 2f)).isGreaterThan(2f)
    }

    @Test
    fun `below the smallest size it gives a little and holds`() {
        val damped = rubberBandScale(raw = 0.8f / 16f, min = 0.8f, max = 2f)
        assertThat(damped).isWithin(0.0001f).of(0.4f)
        assertThat(rubberBandScale(raw = 0.6f, min = 0.8f, max = 2f)).isGreaterThan(0.6f)
    }
}
