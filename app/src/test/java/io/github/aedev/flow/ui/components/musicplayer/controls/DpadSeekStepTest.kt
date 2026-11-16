package io.github.aedev.flow.ui.components.musicplayer.controls

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DpadSeekStepTest {
    @Test
    fun `a press steps by the seek setting`() {
        assertThat(DpadSeekStep.stepMs(seekSeconds = 5, repeatCount = 0)).isEqualTo(5_000L)
        assertThat(DpadSeekStep.stepMs(seekSeconds = 15, repeatCount = 7)).isEqualTo(15_000L)
    }

    @Test
    fun `a held key steps three times as far`() {
        assertThat(DpadSeekStep.stepMs(seekSeconds = 10, repeatCount = 8)).isEqualTo(30_000L)
    }

    @Test
    fun `double tap seek turned off still steps by the fallback`() {
        assertThat(DpadSeekStep.stepMs(seekSeconds = 0, repeatCount = 0)).isEqualTo(DpadSeekStep.FALLBACK_SECONDS * 1_000L)
    }

    @Test
    fun `the target stays inside the track`() {
        assertThat(DpadSeekStep.target(fromMs = 4_000L, direction = -1, seekSeconds = 10, repeatCount = 0, durationMs = 200_000L))
            .isEqualTo(0L)
        assertThat(DpadSeekStep.target(fromMs = 195_000L, direction = 1, seekSeconds = 10, repeatCount = 0, durationMs = 200_000L))
            .isEqualTo(200_000L)
        assertThat(DpadSeekStep.target(fromMs = 60_000L, direction = 1, seekSeconds = 10, repeatCount = 0, durationMs = 200_000L))
            .isEqualTo(70_000L)
    }
}
