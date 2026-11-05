package io.github.aedev.flow.player.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * The quality selector has to name the high-frame-rate ladder, otherwise 1080p60 and 1080p30 are
 * indistinguishable rows (#847).
 */
class VideoCodecUtilsFrameRateTest {
    private fun stream(resolution: String): VideoStream =
        VideoStream
            .Builder()
            .setId("test")
            .setContent("https://example.invalid/test", true)
            .setMediaFormat(MediaFormat.MPEG_4)
            .setResolution(resolution)
            .setIsVideoOnly(true)
            .build()

    @Test
    fun `the frame rate is only spelled out above the standard ladder`() {
        assertEquals("1080p60", VideoCodecUtils.qualityLabelWithFrameRate(1080, 60))
        assertEquals("2160p50", VideoCodecUtils.qualityLabelWithFrameRate(2160, 50))
        assertEquals("1080p", VideoCodecUtils.qualityLabelWithFrameRate(1080, 30))
        assertEquals("720p", VideoCodecUtils.qualityLabelWithFrameRate(720, 24))
        assertEquals("360p", VideoCodecUtils.qualityLabelWithFrameRate(360, 0))
    }

    @Test
    fun `a stream without itag metadata still reports the frame rate from its label`() {
        assertEquals(60, VideoCodecUtils.frameRateFromStream(stream("1080p60")))
        assertEquals(0, VideoCodecUtils.frameRateFromStream(stream("1080p")))
    }

    @Test
    fun `only a frame rate suffix is read as one`() {
        assertEquals(60, VideoCodecUtils.frameRateFromLabel("1080p60"))
        assertNull(VideoCodecUtils.frameRateFromLabel("1080p"))
        assertNull(VideoCodecUtils.frameRateFromLabel(""))
        assertNull(VideoCodecUtils.frameRateFromLabel(null))
    }

    // The height parser has to stay blind to the frame rate, or a 1080p60 row files itself under 108.
    @Test
    fun `a high-frame-rate label still resolves to its resolution`() {
        assertEquals(1080, VideoCodecUtils.qualityHeightFromStream(stream("1080p60")))
        assertEquals(2160, VideoCodecUtils.qualityHeightFromStream(stream("2160p60")))
    }

    @Test
    fun `a stream is HDR only when YouTube's label says so`() {
        assertEquals(true, VideoCodecUtils.isHdr(stream("2160p60 HDR")))
        assertEquals(true, VideoCodecUtils.isHdr(stream("480p60 HDR")))
        assertEquals(false, VideoCodecUtils.isHdr(stream("2160p60")))
        assertEquals(false, VideoCodecUtils.isHdr(stream("480p")))
    }
}
