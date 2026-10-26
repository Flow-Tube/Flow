package io.github.aedev.flow.ui.components.videoplayer.gesture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GestureLevelStepsTest {
    @Test
    fun `brightness has twenty steps above auto`() {
        assertThat(gestureLevelStep(-1f)).isEqualTo(AUTO_GESTURE_STEP)
        assertThat(gestureLevelStep(0f)).isEqualTo(0)
        assertThat(gestureLevelStep(0.049f)).isEqualTo(0)
        assertThat(gestureLevelStep(0.05f)).isEqualTo(1)
        assertThat(gestureLevelStep(0.5f)).isEqualTo(10)
        assertThat(gestureLevelStep(1f)).isEqualTo(20)
    }

    @Test
    fun `brightness swept from auto to full ticks at every step`() {
        var ticks = 0
        val ticker = GestureTicker { ticks++ }
        ticker.onStep(gestureLevelStep(-1f))
        for (i in 0..1000) ticker.onStep(gestureLevelStep(i / 1000f))
        assertThat(ticks).isEqualTo(21)
    }

    @Test
    fun `the step a swipe starts in does not tick`() {
        var ticks = 0
        val ticker = GestureTicker { ticks++ }
        ticker.onStep(4)
        ticker.onStep(4)
        assertThat(ticks).isEqualTo(0)
        ticker.onStep(5)
        assertThat(ticks).isEqualTo(1)
        ticker.reset()
        ticker.onStep(9)
        assertThat(ticks).isEqualTo(1)
    }
}
