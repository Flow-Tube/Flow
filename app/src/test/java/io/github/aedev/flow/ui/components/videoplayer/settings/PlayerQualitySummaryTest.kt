package io.github.aedev.flow.ui.components.videoplayer.settings

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.QualityOption
import org.junit.Test

class PlayerQualitySummaryTest {
    private val auto = QualityOption(height = 0, label = "Auto", bitrate = 0L)
    private val hfr1080Vp9 = QualityOption(1080, "1080p60 VP9", 4_000_000L, "vp9", "137_vp9")
    private val hfr1080Avc = QualityOption(1080, "1080p60 H.264", 5_000_000L, "h264", "299_h264")
    private val hfr720 = QualityOption(720, "720p60 VP9", 2_000_000L, "vp9", "298_vp9")
    private val sd480 = QualityOption(480, "480p VP9", 900_000L, "vp9", "244_vp9")
    private val options = listOf(auto, hfr1080Vp9, hfr1080Avc, hfr720, sd480)

    @Test
    fun `a picked quality shows its resolution and frame rate without the codec`() {
        assertThat(qualitySummaryResolution(1080, "299_h264", 1080, options)).isEqualTo("1080p60")
        assertThat(qualitySummaryResolution(480, "244_vp9", 480, options)).isEqualTo("480p")
    }

    @Test
    fun `Auto shows the resolution it is playing`() {
        assertThat(qualitySummaryResolution(0, null, 720, options)).isEqualTo("720p60")
    }

    @Test
    fun `Auto before anything plays has no resolution`() {
        assertThat(qualitySummaryResolution(0, null, 0, options)).isNull()
    }

    @Test
    fun `a height the list does not have falls back to plain pixels`() {
        assertThat(qualitySummaryResolution(0, null, 800, options)).isEqualTo("800p")
        assertThat(qualitySummaryResolution(1440, "missing", 1440, options)).isEqualTo("1440p")
    }

    @Test
    fun `a live quality without a codec keeps its whole label`() {
        val live = listOf(auto, QualityOption(1080, "1080p60", 0L, streamKey = "live_1080"))

        assertThat(qualitySummaryResolution(1080, "live_1080", 1080, live)).isEqualTo("1080p60")
    }

    @Test
    fun `a blank label falls back to the height`() {
        val blank = listOf(QualityOption(360, "", 0L, streamKey = "18"))

        assertThat(qualitySummaryResolution(360, "18", 360, blank)).isEqualTo("360p")
    }
}
