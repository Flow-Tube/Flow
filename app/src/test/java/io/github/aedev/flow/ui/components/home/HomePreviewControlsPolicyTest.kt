package io.github.aedev.flow.ui.components.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomePreviewControlsPolicyTest {
    @Test
    fun `right after a touch every control shows`() {
        assertThat(HomePreviewControlsPolicy.shown(hasCaptions = true, idleMs = 0L, scrubbing = false))
            .isEqualTo(PreviewControlsShown(mute = true, captions = true, timeBar = true, badge = true))
    }

    @Test
    fun `after three idle seconds only mute, the time bar and the badge stay`() {
        assertThat(HomePreviewControlsPolicy.shown(hasCaptions = true, idleMs = 2_999L, scrubbing = false).captions).isTrue()
        assertThat(HomePreviewControlsPolicy.shown(hasCaptions = true, idleMs = 3_000L, scrubbing = false))
            .isEqualTo(PreviewControlsShown(mute = true, captions = false, timeBar = true, badge = true))
    }

    @Test
    fun `nothing hides mid-scrub, and the badge gives way to the scrub preview`() {
        assertThat(HomePreviewControlsPolicy.shown(hasCaptions = true, idleMs = 60_000L, scrubbing = true))
            .isEqualTo(PreviewControlsShown(mute = true, captions = true, timeBar = true, badge = false))
    }

    @Test
    fun `a video without captions never shows the captions button`() {
        assertThat(HomePreviewControlsPolicy.shown(hasCaptions = false, idleMs = 0L, scrubbing = false).captions).isFalse()
    }

    @Test
    fun `a double tap on the left half goes back ten seconds, on the right half forward`() {
        assertThat(HomePreviewControlsPolicy.doubleTapSeekMs(x = 10f, width = 400f)).isEqualTo(-10_000L)
        assertThat(HomePreviewControlsPolicy.doubleTapSeekMs(x = 390f, width = 400f)).isEqualTo(10_000L)
    }

    @Test
    fun `a seek stays inside the video`() {
        assertThat(HomePreviewControlsPolicy.seekTarget(positionMs = 4_000L, deltaMs = -10_000L, durationMs = 60_000L)).isEqualTo(0L)
        assertThat(HomePreviewControlsPolicy.seekTarget(positionMs = 55_000L, deltaMs = 10_000L, durationMs = 60_000L)).isEqualTo(60_000L)
        assertThat(HomePreviewControlsPolicy.seekTarget(positionMs = 20_000L, deltaMs = 10_000L, durationMs = 60_000L)).isEqualTo(30_000L)
    }

    @Test
    fun `the badge counts down in whole seconds, rounding up`() {
        assertThat(HomePreviewControlsPolicy.remainingSeconds(positionMs = 0L, durationMs = 61_000L)).isEqualTo(61)
        assertThat(HomePreviewControlsPolicy.remainingSeconds(positionMs = 59_001L, durationMs = 61_000L)).isEqualTo(2)
        assertThat(HomePreviewControlsPolicy.remainingSeconds(positionMs = 70_000L, durationMs = 61_000L)).isEqualTo(0)
    }
}
