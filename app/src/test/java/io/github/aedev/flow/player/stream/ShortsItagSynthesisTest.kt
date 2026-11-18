package io.github.aedev.flow.player.stream

import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import io.github.aedev.flow.player.stream.model.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The 608×1080 Shorts tier (itags 779/780/787/788) is missing from NewPipe's table. Without an
 * [io.github.aedev.flow.player.stream.model.ItagInfo] the bridge cannot describe the format
 * for a DASH manifest, and the reel falls back to the throttled progressive path.
 */
class ShortsItagSynthesisTest {
    private fun format(
        itag: Int,
        mimeType: String,
        width: Int? = 608,
        height: Int? = 1080,
    ) = Format(
        itag = itag,
        url = "https://example.invalid/videoplayback?itag=$itag",
        mimeType = mimeType,
        bitrate = 900_000,
        width = width,
        height = height,
        contentLength = 3_000_000L,
        quality = "medium",
        fps = width?.let { 30 },
        qualityLabel = width?.let { "480p" },
        averageBitrate = 900_000,
        audioQuality = null,
        approxDurationMs = "28000",
        audioSampleRate = null,
        audioChannels = null,
        loudnessDb = null,
        lastModified = null,
        signatureCipher = null,
        initRange = Format.Range(start = "0", end = "740"),
        indexRange = Format.Range(start = "741", end = "1560"),
    )

    @Test
    fun `an itag NewPipe does not know is described from the response`() {
        val streams =
            InnerTubeStreamBridge.convertVideoFormats(
                listOf(
                    format(780, "video/webm; codecs=\"vp9\""),
                    format(788, "video/mp4; codecs=\"av01.0.05M.08\""),
                ),
            )

        assertEquals(2, streams.size)
        streams.forEach { stream ->
            val item = stream.itagItem!!
            assertEquals(608, item.width)
            assertEquals(1080, item.height)
            assertEquals(30, item.fps)
            assertEquals("480p", item.resolutionString)
            assertEquals(741, item.indexStart)
        }
        assertEquals(MediaFormat.WEBM, streams[0].itagItem!!.mediaFormat)
        assertEquals(MediaFormat.MPEG_4, streams[1].itagItem!!.mediaFormat)
    }

    @Test
    fun `a known itag still comes from the table`() {
        val stream =
            InnerTubeStreamBridge
                .convertVideoFormats(
                    listOf(format(248, "video/webm; codecs=\"vp9\"", width = 1080, height = 1920)),
                ).single()
        assertEquals(248, stream.itagItem!!.id)
    }

    @Test
    fun `an unknown audio itag is left undescribed`() {
        val stream =
            InnerTubeStreamBridge
                .convertAudioFormats(
                    listOf(format(999, "audio/mp4; codecs=\"mp4a.40.2\"", width = null, height = null)),
                ).single()
        assertNull(stream.itagItem)
    }
}
