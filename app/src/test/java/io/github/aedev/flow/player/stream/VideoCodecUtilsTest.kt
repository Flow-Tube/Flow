package io.github.aedev.flow.player.stream

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.stream.model.MediaFormat
import io.github.aedev.flow.player.stream.model.VideoStream
import org.junit.Test

class VideoCodecUtilsTest {
    @Test
    fun `codec keys come from the codecs parameter of the mime type`() {
        assertThat(VideoCodecUtils.codecKeyFromMimeType("video/mp4; codecs=\"avc1.640028\"")).isEqualTo("h264")
        assertThat(VideoCodecUtils.codecKeyFromMimeType("video/webm; codecs=\"vp09.00.40.08\"")).isEqualTo("vp9")
        assertThat(VideoCodecUtils.codecKeyFromMimeType("video/mp4; codecs=\"av01.0.08M.08\"")).isEqualTo("av1")
        assertThat(VideoCodecUtils.codecKeyFromMimeType("video/mp4; codecs=\"hev1.1.6.L93.B0\"")).isEqualTo("hevc")
        assertThat(VideoCodecUtils.codecKeyFromMimeType("video/webm")).isEqualTo("vp9")
        assertThat(VideoCodecUtils.codecKeyFromMimeType("")).isEqualTo("h264")
    }

    @Test
    fun `codec labels and size keys are simple mappings`() {
        assertThat(VideoCodecUtils.codecLabelFromKey("av1")).isEqualTo("AV1")
        assertThat(VideoCodecUtils.codecLabelFromKey("h264")).isEqualTo("H264")
        assertThat(VideoCodecUtils.codecLabelFromKey("custom")).isEqualTo("CUSTOM")
        assertThat(VideoCodecUtils.streamSizeKey(1080, "vp9")).isEqualTo("1080_vp9")
    }

    @Test
    fun `stream helpers read the resolution label and the container`() {
        val stream =
            VideoStream(
                id = "248",
                content = "https://example.invalid/248.webm",
                format = MediaFormat.WEBM,
                itagItem = null,
                resolution = "1080p60",
                isVideoOnly = true,
            )

        assertThat(VideoCodecUtils.codecKeyFromStream(stream)).isEqualTo("vp9")
        assertThat(VideoCodecUtils.qualityHeightFromStream(stream)).isEqualTo(1080)
    }
}
