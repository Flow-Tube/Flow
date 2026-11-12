package io.github.aedev.flow.player.state

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerStateSphericalTest {
    @Test
    fun `a detected 360 video shows the 360 view until turned off`() {
        val detected = EnhancedPlayerState(isSphericalVideo = true)

        assertThat(detected.showsSpherical).isTrue()
        assertThat(detected.copy(sphericalOverride = false).showsSpherical).isFalse()
    }

    @Test
    fun `a flat video shows the 360 view only when turned on`() {
        val flat = EnhancedPlayerState()

        assertThat(flat.showsSpherical).isFalse()
        assertThat(flat.copy(sphericalOverride = true).showsSpherical).isTrue()
    }
}
