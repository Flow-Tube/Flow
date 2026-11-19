package io.github.aedev.flow.ui.components.videoplayer.motion

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerGestureThresholdsTest {
    @Test
    fun `on the density they were tuned on the thresholds keep their tuned pixels`() {
        val t = PlayerGestureThresholds(density = 3f)
        assertThat(t.expandDragCommit).isEqualTo(80f)
        assertThat(t.collapseFlingVelocity).isEqualTo(1000f)
        assertThat(t.tuckFlingVelocity).isEqualTo(2500f)
        assertThat(t.miniTapMovement).isEqualTo(24f)
        assertThat(t.sidePanelCloseVelocity).isEqualTo(900f)
    }

    @Test
    fun `a lower density gets the same physical distance in fewer pixels`() {
        val t = PlayerGestureThresholds(density = 2f)
        assertThat(t.expandDragCommit).isWithin(0.001f).of(80f * 2f / 3f)
        assertThat(t.exitFullscreenDrag).isWithin(0.001f).of(80f * 2f / 3f)
        assertThat(t.portraitFsCancelVelocity).isWithin(0.01f).of(1400f * 2f / 3f)
    }

    @Test
    fun `a collapse commit flips at the same physical speed on every density`() {
        val low = PlayerGestureThresholds(density = 2f)
        val high = PlayerGestureThresholds(density = 3.5f)
        val physical = 340f
        assertThat(shouldCollapseOnRelease(0.05f, physical * 2f, low)).isTrue()
        assertThat(shouldCollapseOnRelease(0.05f, physical * 3.5f, high)).isTrue()
        assertThat(shouldCollapseOnRelease(0.05f, 320f * 2f, low)).isFalse()
        assertThat(shouldCollapseOnRelease(0.05f, 320f * 3.5f, high)).isFalse()
    }
}
