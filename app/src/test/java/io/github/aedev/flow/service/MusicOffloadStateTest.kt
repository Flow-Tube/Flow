package io.github.aedev.flow.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MusicOffloadStateTest {
    private val optedIn = MusicOffloadState(enabledByUser = true)

    @Test
    fun `offload stays off until the listener turns it on`() {
        assertThat(MusicOffloadState().allowed).isFalse()
    }

    @Test
    fun `untouched sound while playing may offload once turned on`() {
        assertThat(optedIn.allowed).isTrue()
    }

    @Test
    fun `an equalizer that changes the sound keeps offload off`() {
        assertThat(optedIn.copy(processing = true).allowed).isFalse()
    }

    @Test
    fun `a changed speed or pitch keeps offload off`() {
        assertThat(optedIn.copy(playbackChanged = true).allowed).isFalse()
    }

    @Test
    fun `a long pause gives offload up until playback resumes`() {
        val paused = optedIn.copy(pausedLong = true)

        assertThat(paused.allowed).isFalse()
        assertThat(paused.copy(pausedLong = false).allowed).isTrue()
    }

    @Test
    fun `a speed a hair off one still counts as untouched`() {
        assertThat(MusicOffloadState.changesPlayback(speed = 0.99999994f, pitch = 1f)).isFalse()
        assertThat(MusicOffloadState.changesPlayback(speed = 1.25f, pitch = 1f)).isTrue()
        assertThat(MusicOffloadState.changesPlayback(speed = 1f, pitch = 1.0594631f)).isTrue()
    }
}
