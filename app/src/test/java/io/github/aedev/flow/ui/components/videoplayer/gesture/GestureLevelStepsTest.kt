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
    fun `volume below full follows the stream steps`() {
        assertThat(volumeGestureStep(0f, 15)).isEqualTo(0)
        assertThat(volumeGestureStep(0.5f, 15)).isEqualTo(7)
        assertThat(volumeGestureStep(0.999f, 15)).isEqualTo(14)
        assertThat(volumeGestureStep(1f, 15)).isEqualTo(15)
    }

    @Test
    fun `volume boost ticks every five percent above the stream maximum`() {
        assertThat(volumeGestureStep(1.01f, 15)).isEqualTo(15)
        assertThat(volumeGestureStep(1.05f, 15)).isEqualTo(16)
        assertThat(volumeGestureStep(2f, 15)).isEqualTo(35)

        var ticks = 0
        val ticker = GestureTicker { ticks++ }
        for (i in 1000..2000) ticker.onStep(volumeGestureStep(i / 1000f, 15))
        assertThat(ticks).isEqualTo(20)
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
