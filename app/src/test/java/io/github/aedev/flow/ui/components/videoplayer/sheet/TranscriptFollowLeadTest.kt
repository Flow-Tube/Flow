package io.github.aedev.flow.ui.components.videoplayer.sheet

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TranscriptFollowLeadTest {
    @Test
    fun `a short line keeps a quarter of the list above it`() {
        assertThat(transcriptFollowLead(viewportPx = 1_000, linePx = 120)).isEqualTo(250)
    }

    @Test
    fun `a long line gives up lead so its last row stays in view`() {
        assertThat(transcriptFollowLead(viewportPx = 1_000, linePx = 900)).isEqualTo(100)
    }

    @Test
    fun `a line taller than the list starts at the top`() {
        assertThat(transcriptFollowLead(viewportPx = 1_000, linePx = 1_400)).isEqualTo(0)
    }

    @Test
    fun `an unmeasured line gets the quarter`() {
        assertThat(transcriptFollowLead(viewportPx = 800, linePx = null)).isEqualTo(200)
    }

    @Test
    fun `an empty list has no lead`() {
        assertThat(transcriptFollowLead(viewportPx = 0, linePx = 50)).isEqualTo(0)
    }
}
