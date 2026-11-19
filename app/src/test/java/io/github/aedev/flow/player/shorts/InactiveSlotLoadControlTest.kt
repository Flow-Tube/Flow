package io.github.aedev.flow.player.shorts

import android.content.ComponentCallbacks2
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.upstream.Allocator
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test

@UnstableApi
class InactiveSlotLoadControlTest {
    private class Recording : LoadControl {
        var prepared = 0
        var stopped = 0
        var released = 0
        var asked = 0

        override fun getAllocator(playerId: PlayerId): Allocator = mockk()

        override fun onPrepared(playerId: PlayerId) {
            prepared++
        }

        override fun onStopped(playerId: PlayerId) {
            stopped++
        }

        override fun onReleased(playerId: PlayerId) {
            released++
        }

        override fun getBackBufferDurationUs(playerId: PlayerId): Long = 2_000_000L

        override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = true

        override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = true

        override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
            asked++
            return true
        }
    }

    private fun params(bufferedMs: Long) =
        LoadControl.Parameters(
            PlayerId.UNSET,
            Timeline.EMPTY,
            LoadControl.EMPTY_MEDIA_PERIOD_ID,
            0L,
            bufferedMs * 1_000L,
            1f,
            false,
            false,
            C.TIME_UNSET,
            C.TIME_UNSET,
        )

    @Test
    fun `a slot that is not on screen stops at 2 s, the one on screen loads as usual`() {
        var active = false
        val delegate = Recording()
        val control = InactiveSlotLoadControl(delegate, { active })

        assertThat(control.shouldContinueLoading(params(1_500))).isTrue()
        assertThat(control.shouldContinueLoading(params(2_000))).isFalse()

        active = true
        assertThat(control.shouldContinueLoading(params(6_000))).isTrue()
        assertThat(delegate.asked).isEqualTo(3)
    }

    @Test
    fun `everything else reaches the wrapped load control`() {
        val delegate = Recording()
        val control = InactiveSlotLoadControl(delegate, { false })

        control.onPrepared(PlayerId.UNSET)
        control.onStopped(PlayerId.UNSET)
        control.onReleased(PlayerId.UNSET)

        assertThat(delegate.prepared).isEqualTo(1)
        assertThat(delegate.stopped).isEqualTo(1)
        assertThat(delegate.released).isEqualTo(1)
        assertThat(control.getBackBufferDurationUs(PlayerId.UNSET)).isEqualTo(2_000_000L)
        assertThat(control.retainBackBufferFromKeyframe(PlayerId.UNSET)).isTrue()
        assertThat(control.shouldStartPlayback(params(500))).isTrue()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `only real memory pressure trims the neighbours`() {
        assertThat(shouldTrimShortsNeighbours(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)).isFalse()
        assertThat(shouldTrimShortsNeighbours(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)).isTrue()
        assertThat(shouldTrimShortsNeighbours(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)).isTrue()
        assertThat(shouldTrimShortsNeighbours(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)).isTrue()
        assertThat(shouldTrimShortsNeighbours(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)).isFalse()
    }
}
