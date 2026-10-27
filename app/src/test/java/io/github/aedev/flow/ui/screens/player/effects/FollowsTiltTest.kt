package io.github.aedev.flow.ui.screens.player.effects

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FollowsTiltTest {
    @Test
    fun `auto-rotate on follows the tilt`() {
        assertThat(followsTilt(ignoreRotationLock = false, autoRotateOn = { true })).isTrue()
    }

    @Test
    fun `rotation lock stops the tilt when the switch is off`() {
        assertThat(followsTilt(ignoreRotationLock = false, autoRotateOn = { false })).isFalse()
    }

    @Test
    fun `switch on follows the tilt through the rotation lock`() {
        assertThat(followsTilt(ignoreRotationLock = true, autoRotateOn = { false })).isTrue()
    }

    @Test
    fun `switch on never reads the system setting`() {
        var reads = 0
        followsTilt(ignoreRotationLock = true, autoRotateOn = { reads++ == 0 })
        assertThat(reads).isEqualTo(0)
    }
}
