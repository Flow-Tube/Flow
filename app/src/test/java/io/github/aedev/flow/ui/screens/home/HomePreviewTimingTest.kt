package io.github.aedev.flow.ui.screens.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomePreviewTimingTest {
    @Test
    fun `a card plays after one second, five on a slow link`() {
        assertThat(HomePreviewTiming.dwellMs(slowNetwork = false)).isEqualTo(1_000L)
        assertThat(HomePreviewTiming.dwellMs(slowNetwork = true)).isEqualTo(5_000L)
    }

    @Test
    fun `the stream is asked for half a second before the dwell ends`() {
        assertThat(HomePreviewTiming.resolveDelayMs(slowNetwork = false)).isEqualTo(500L)
        assertThat(HomePreviewTiming.resolveDelayMs(slowNetwork = true)).isEqualTo(4_500L)
    }

    @Test
    fun `a card picked again resumes where it stopped`() {
        assertThat(HomePreviewTiming.resumeAtMs(stoppedAtMs = 12_000L, durationMs = 300_000L)).isEqualTo(12_000L)
    }

    @Test
    fun `a card never played, or stopped at its very end, starts from the beginning`() {
        assertThat(HomePreviewTiming.resumeAtMs(stoppedAtMs = null, durationMs = 300_000L)).isEqualTo(0L)
        assertThat(HomePreviewTiming.resumeAtMs(stoppedAtMs = 298_500L, durationMs = 300_000L)).isEqualTo(0L)
    }

    @Test
    fun `nothing shows over the thumbnail until a frame is rendered`() {
        assertThat(HomePreviewState(videoId = "a", phase = HomePreviewPhase.SELECTED).showsVideo).isFalse()
        assertThat(HomePreviewState(videoId = "a", phase = HomePreviewPhase.POSITIONED).showsVideo).isFalse()
        assertThat(HomePreviewState(videoId = "a", phase = HomePreviewPhase.PLAYING).showsVideo).isTrue()
        assertThat(HomePreviewState(videoId = "a", phase = HomePreviewPhase.FINISHED).showsVideo).isFalse()
    }
}
