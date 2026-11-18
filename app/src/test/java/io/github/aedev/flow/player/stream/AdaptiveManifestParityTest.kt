package io.github.aedev.flow.player.stream

import androidx.media3.common.C
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The whole-ladder DASH manifest built straight into Media3's model, frozen attribute by attribute. */
class AdaptiveManifestParityTest {
    private val g = GoldenFormats
    private val r720 =
        g.video(136, "video/mp4; codecs=\"avc1.4d401f\"", 1280, 720, 30, "720p", "hd720", bitrate = 1_200_000, averageBitrate = 900_000)

    @Test
    fun `a rung needs a codec, a bitrate and both byte ranges`() {
        assertThat(adaptiveCanDescribe(g.H264_1080)).isTrue()
        assertThat(adaptiveCanDescribe(g.SHORTS_780)).isTrue()
        assertThat(adaptiveCanDescribe(g.GPP_144)).isFalse()
        assertThat(adaptiveCanDescribe(g.H264_1080.copy(mimeType = "video/mp4"))).isFalse()
        assertThat(adaptiveCanDescribe(g.H264_1080.copy(bitrate = 0, averageBitrate = 0))).isFalse()
        assertThat(adaptiveCanDescribe(g.H264_1080.copy(initRange = null))).isFalse()
        assertThat(adaptiveCanDescribe(g.H264_1080.copy(indexRange = g.H264_1080.indexRange!!.copy(end = "741")))).isFalse()
    }

    @Test
    fun `fewer than two describable rungs build nothing`() {
        assertThat(adaptiveManifest(listOf(g.H264_1080), 212)).isNull()
        assertThat(adaptiveManifest(listOf(g.H264_1080, g.GPP_144), 212)).isNull()
    }

    @Test
    fun `a two-rung ladder`() {
        assertThat(adaptiveManifest(listOf(g.H264_1080, r720), 99)?.snapshot()).isEqualTo(
            ManifestSnapshot(
                dynamic = false,
                durationMs = 212_000L,
                minBufferTimeMs = 1_500L,
                periods = 1,
                representations =
                    listOf(
                        videoRep("137", "video/mp4", "avc1.640028", 2_600_000, 1920, 1080, 30f, g.url(137)),
                        videoRep("136", "video/mp4", "avc1.4d401f", 900_000, 1280, 720, 30f, g.url(136)),
                    ),
            ),
        )
    }

    @Test
    fun `mixed codecs keep their own MIME and sample type`() {
        val manifest = adaptiveManifest(listOf(g.VP9_HDR_1080_60, g.AV1_1080, g.SHORTS_780), 99)!!
        assertThat(manifest.snapshot().representations)
            .containsExactly(
                videoRep("335", "video/webm", "vp09.02.51.10.01.09.16.09.00", 4_000_000, 1920, 1080, 60f, g.url(335)),
                videoRep("399", "video/mp4", "av01.0.08m.08", 1_400_000, 1920, 1080, 30f, g.url(399)),
                videoRep("780", "video/webm", "vp9", 950_000, 608, 1080, 60f, g.url(780)),
            ).inOrder()
        val set = manifest.getPeriod(0).adaptationSets.single()
        assertThat(set.type).isEqualTo(C.TRACK_TYPE_VIDEO)
        assertThat(set.representations.map { it.format.sampleMimeType })
            .containsExactly("video/x-vnd.on2.vp9", "video/av01", "video/x-vnd.on2.vp9")
            .inOrder()
        assertThat(set.representations.map { it.format.roleFlags }).containsExactly(0, 0, 0)
    }

    @Test
    fun `the duration is the first rung's that has one, then the one passed in`() {
        assertThat(
            adaptiveManifest(listOf(g.H264_1080.copy(approxDurationMs = null), r720.copy(approxDurationMs = "150500")), 99)!!.durationMs,
        ).isEqualTo(150_500L)
        assertThat(
            adaptiveManifest(listOf(g.H264_1080.copy(approxDurationMs = null), r720.copy(approxDurationMs = null)), 99)!!.durationMs,
        ).isEqualTo(99_000L)
        assertThat(adaptiveManifest(listOf(g.H264_1080.copy(approxDurationMs = null), r720.copy(approxDurationMs = null)), 0)).isNull()
    }

    @Test
    fun `itag table supplies - a rung's frame rate when the response has none, and a missing width is unset`() {
        val first = adaptiveManifest(listOf(g.H264_1080.copy(fps = null, width = null), r720), 99)!!.snapshot().representations.first()
        assertThat(first).isEqualTo(videoRep("137", "video/mp4", "avc1.640028", 2_600_000, -1, 1080, 30f, g.url(137)))
    }
}
