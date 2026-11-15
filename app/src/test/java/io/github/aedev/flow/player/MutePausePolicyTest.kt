package io.github.aedev.flow.player

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.MutePausePolicy.Action
import org.junit.Test

class MutePausePolicyTest {
    @Test
    fun `muting pauses playing music when the setting is on`() {
        assertThat(MutePausePolicy.onVolume(silent = true, enabled = true, isPlaying = true, pausedForMute = false)).isEqualTo(Action.PAUSE)
        assertThat(MutePausePolicy.onVolume(silent = true, enabled = false, isPlaying = true, pausedForMute = false)).isEqualTo(Action.NONE)
    }

    @Test
    fun `sound coming back resumes only music the mute paused`() {
        assertThat(
            MutePausePolicy.onVolume(silent = false, enabled = true, isPlaying = false, pausedForMute = true),
        ).isEqualTo(Action.RESUME)
        assertThat(
            MutePausePolicy.onVolume(silent = false, enabled = true, isPlaying = false, pausedForMute = false),
        ).isEqualTo(Action.NONE)
    }

    @Test
    fun `turning the setting off still resumes what it paused`() {
        assertThat(
            MutePausePolicy.onVolume(silent = false, enabled = false, isPlaying = false, pausedForMute = true),
        ).isEqualTo(Action.RESUME)
    }
}
