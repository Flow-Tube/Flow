package io.github.aedev.flow.player.live

import androidx.media3.common.C
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveEdgePolicyTest {
    private val twelveHours = 43_200_000L

    @Test
    fun `the default position after a couple of rebuffers is still live`() {
        // 12 s readahead plus two 500 ms target raises.
        assertThat(LiveEdgePolicy.isAtEdge(twelveHours, twelveHours - 13_000)).isTrue()
    }

    @Test
    fun `more than 15 seconds behind the window end is behind live`() {
        assertThat(LiveEdgePolicy.isAtEdge(twelveHours, twelveHours - 15_001)).isFalse()
        assertThat(LiveEdgePolicy.behindLiveMs(twelveHours, twelveHours - 5_025_000)).isEqualTo(5_025_000)
    }

    @Test
    fun `an unknown window counts as live`() {
        assertThat(LiveEdgePolicy.isAtEdge(C.TIME_UNSET, 0)).isTrue()
        assertThat(LiveEdgePolicy.behindLiveMs(0, 1_000)).isEqualTo(0)
    }
}
