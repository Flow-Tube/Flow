package io.github.aedev.flow.player.state

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerStateSeekLockTest {
    @Test
    fun `only a live stream without DVR locks seeking`() {
        assertThat(EnhancedPlayerState(isLive = true, isLiveDvrEnabled = false).isSeekLocked).isTrue()
        assertThat(EnhancedPlayerState(isLive = true, isLiveDvrEnabled = true).isSeekLocked).isFalse()
        assertThat(EnhancedPlayerState(isLive = false, isLiveDvrEnabled = false).isSeekLocked).isFalse()
    }
}
