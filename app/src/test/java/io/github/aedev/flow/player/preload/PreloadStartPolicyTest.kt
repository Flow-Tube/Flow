package io.github.aedev.flow.player.preload

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PreloadStartPolicyTest {
    @Test
    fun `a long video preloads its successor a minute before the end`() {
        assertThat(PreloadStartPolicy.startPositionMs(10 * 60_000L)).isEqualTo(9 * 60_000L)
    }

    @Test
    fun `a video under two minutes preloads from halfway`() {
        assertThat(PreloadStartPolicy.startPositionMs(90_000L)).isEqualTo(45_000L)
        assertThat(PreloadStartPolicy.startPositionMs(40_000L)).isEqualTo(20_000L)
    }

    @Test
    fun `the lead never drops below 15 seconds, or the whole of a shorter video`() {
        assertThat(PreloadStartPolicy.startPositionMs(20_000L)).isEqualTo(5_000L)
        assertThat(PreloadStartPolicy.startPositionMs(10_000L)).isEqualTo(0L)
    }

    @Test
    fun `an unknown duration preloads at once, as before`() {
        assertThat(PreloadStartPolicy.startPositionMs(0L)).isEqualTo(0L)
        assertThat(PreloadStartPolicy.startPositionMs(-9_223_372_036_854_775_807L)).isEqualTo(0L)
    }
}
