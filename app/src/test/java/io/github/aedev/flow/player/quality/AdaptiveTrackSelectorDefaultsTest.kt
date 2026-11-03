package io.github.aedev.flow.player.quality

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.config.VideoSizeCap
import io.github.aedev.flow.player.config.adaptiveTrackSelectorDefaults
import org.junit.Test

@UnstableApi
class AdaptiveTrackSelectorDefaultsTest {
    private fun manualLivePick(): DefaultTrackSelector.Parameters.Builder =
        DefaultTrackSelector.Parameters
            .Builder()
            .setMinVideoSize(0, 143)
            .setMaxVideoSize(Int.MAX_VALUE, 144)
            .setForceHighestSupportedBitrate(true)

    @Test
    fun `defaults keep the low-RAM cap`() {
        val params = adaptiveTrackSelectorDefaults(manualLivePick(), VideoSizeCap(1920, 1080)).build()

        assertThat(params.maxVideoWidth).isEqualTo(1920)
        assertThat(params.maxVideoHeight).isEqualTo(1080)
    }

    @Test
    fun `defaults keep the 4K cap on other devices`() {
        val params = adaptiveTrackSelectorDefaults(manualLivePick(), VideoSizeCap.UHD).build()

        assertThat(params.maxVideoWidth).isEqualTo(3840)
        assertThat(params.maxVideoHeight).isEqualTo(2160)
    }

    @Test
    fun `defaults lift the manual floor and forced bitrate`() {
        val params = adaptiveTrackSelectorDefaults(manualLivePick(), VideoSizeCap.UHD).build()

        assertThat(params.minVideoHeight).isEqualTo(0)
        assertThat(params.forceHighestSupportedBitrate).isFalse()
    }

    @Test
    fun `defaults put back the screen viewport a local file cleared`() {
        val afterLocalFile = manualLivePick().clearViewportSizeConstraints()

        val params = adaptiveTrackSelectorDefaults(afterLocalFile, VideoSizeCap.UHD).build()

        assertThat(params.isViewportSizeLimitedByPhysicalDisplaySize).isTrue()
    }

    @Test
    fun `only a low-RAM device is held at 1080p`() {
        assertThat(VideoSizeCap.forRam(isLowRamDevice = true)).isEqualTo(VideoSizeCap(1920, 1080))
        assertThat(VideoSizeCap.forRam(isLowRamDevice = false)).isEqualTo(VideoSizeCap.UHD)
    }
}
