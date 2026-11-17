package io.github.aedev.flow.player.resolver

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import io.github.aedev.flow.player.stream.GoldenFormats
import io.github.aedev.flow.player.stream.InnerTubeStreamBridge
import io.github.aedev.flow.player.stream.ManifestSnapshot
import io.github.aedev.flow.player.stream.fromXml
import io.github.aedev.flow.player.stream.model.ItagInfo
import io.github.aedev.flow.player.stream.model.Stream
import org.junit.Test
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator

/**
 * The native progressive manifest against NewPipe's creator on the same converted stream: either
 * both refuse, or both describe it identically. Lives only as long as the NewPipe dependency; the
 * frozen values in ProgressiveManifestParityTest carry the same guarantee afterwards.
 */
class ProgressiveDashManifestNewPipeDiffTest {
    private var urlCounter = 0

    // NewPipe's creator caches by URL, so each case gets its own.
    private fun Format.fresh() = copy(url = "https://rr3---sn-diff.googlevideo.com/videoplayback?itag=$itag&n=${urlCounter++}&a=1")

    private fun newPipe(
        stream: Stream,
        durationSeconds: Long,
    ): String? =
        runCatching {
            YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(
                stream.content,
                stream.itagItem!!.toNewPipe(),
                durationSeconds,
            )
        }.getOrNull()

    private fun ItagInfo.toNewPipe(): ItagItem =
        ItagItem(
            id,
            ItagItem.ItagType.valueOf(itagType.name),
            mediaFormat?.let {
                org.schabi.newpipe.extractor.MediaFormat
                    .valueOf(it.name)
            },
            resolutionString,
            fps,
        ).also { item ->
            item.bitrate = bitrate
            item.codec = codec
            item.width = width
            item.height = height
            item.initStart = initStart
            item.initEnd = initEnd
            item.indexStart = indexStart
            item.indexEnd = indexEnd
            item.contentLength = contentLength
            item.approxDurationMs = approxDurationMs
            item.quality = quality
            item.sampleRate = sampleRate
            item.audioChannels = audioChannels
        }

    private fun assertSame(
        stream: Stream?,
        durationSeconds: Long,
    ) {
        stream ?: return
        if (stream.itagItem == null) return
        val expected = newPipe(stream, durationSeconds)?.let { ManifestSnapshot.fromXml(it) }
        val actual =
            ManifestGenerator
                .generateProgressiveManifest(
                    stream,
                    stream.itagItem!!,
                    durationSeconds,
                )?.let { ManifestSnapshot.fromXml(it) }
        assertThat(actual).isEqualTo(expected)
        val raw = ManifestGenerator.generateProgressiveManifest(stream, stream.itagItem!!, durationSeconds)
        if (raw != null) {
            assertThat(raw).contains("<Role schemeIdUri=\"urn:mpeg:DASH:role:2011\" value=\"main\"/>")
            assertThat(raw).doesNotContain("audioSamplingRate")
            assertThat(raw).doesNotContain(" lang=")
        }
    }

    private fun video(
        format: Format,
        durationSeconds: Long = 212,
    ) = assertSame(InnerTubeStreamBridge.convertVideoFormats(listOf(format.fresh())).singleOrNull(), durationSeconds)

    private fun audio(
        format: Format,
        durationSeconds: Long = 212,
    ) = assertSame(InnerTubeStreamBridge.convertAudioFormats(listOf(format.fresh())).singleOrNull(), durationSeconds)

    @Test
    fun `video formats of every family`() {
        video(GoldenFormats.video(137, "video/mp4; codecs=\"avc1.640028\"", 1920, 1080, 30, "1080p", "hd1080"))
        video(GoldenFormats.video(303, "video/webm; codecs=\"vp9\"", 1920, 1080, null, "1080p60", "hd1080"))
        video(GoldenFormats.video(248, "video/webm; codecs=\"vp9\"", 1920, 1080, 30, "1080p", "hd1080"))
        video(GoldenFormats.video(337, "video/webm; codecs=\"vp09.02.51.10.01.09.16.09.00\"", 3840, 2160, 60, "2160p60 HDR", "hd2160"))
        video(GoldenFormats.video(399, "video/mp4; codecs=\"av01.0.08M.08\"", 1920, 1080, 30, "1080p", "hd1080"))
        video(GoldenFormats.video(780, "video/webm; codecs=\"vp9\"", 608, 1080, 30, "480p", "large"))
        video(GoldenFormats.video(788, "video/mp4; codecs=\"av01.0.05M.08\"", 608, 1080, null, "480p", "large"))
        video(
            GoldenFormats.video(
                17,
                "video/3gpp; codecs=\"mp4v.20.3, mp4a.40.2\"",
                176,
                144,
                8,
                "144p",
                "small",
                initRange = null,
                indexRange = null,
            ),
        )
        video(
            GoldenFormats.video(
                18,
                "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"",
                640,
                360,
                30,
                "360p",
                "medium",
                initRange = null,
                indexRange = null,
            ),
        )
        video(GoldenFormats.video(160, "video/mp4; codecs=\"avc1.4d400c\"", null, 144, 30, "144p", "tiny"))
    }

    @Test
    fun `video edge cases`() {
        video(
            GoldenFormats.video(137, "video/mp4; codecs=\"avc1.640028\"", 1920, 1080, 30, "1080p", "hd1080", approxDurationMs = null),
            212,
        )
        video(GoldenFormats.video(137, "video/mp4; codecs=\"avc1.640028\"", 1920, 1080, 30, "1080p", "hd1080", approxDurationMs = null), 0)
        video(
            GoldenFormats.video(137, "video/mp4; codecs=\"avc1.640028\"", 1920, 1080, 30, "1080p", "hd1080", approxDurationMs = "1234567"),
            5,
        )
        video(GoldenFormats.video(137, "video/mp4; codecs=\"avc1.640028\"", 1920, 1080, 30, "1080p", "hd1080", indexRange = null))
        video(GoldenFormats.video(137, "video/mp4; codecs=\"avc1.640028\"", 1920, 1080, 30, "1080p", "hd1080", averageBitrate = null))
        video(
            GoldenFormats.video(
                137,
                "video/mp4; codecs=\"avc1.640028\"",
                1920,
                1080,
                30,
                "1080p",
                "hd1080",
                bitrate = 0,
                averageBitrate = null,
            ),
        )
        video(GoldenFormats.video(137, "video/mp4", 1920, 1080, 30, "1080p", "hd1080"))
    }

    @Test
    fun `audio formats of every family`() {
        audio(GoldenFormats.audio(251, "audio/webm; codecs=\"opus\"", 48000, 2))
        audio(GoldenFormats.audio(250, "audio/webm; codecs=\"opus\"", 48000, 2))
        audio(GoldenFormats.audio(140, "audio/mp4; codecs=\"mp4a.40.2\"", 44100, 2))
        audio(GoldenFormats.audio(139, "audio/mp4; codecs=\"mp4a.40.5\"", 22050, 1))
        audio(GoldenFormats.audio(140, "audio/mp4; codecs=\"mp4a.40.2\"", 44100, null))
        audio(GoldenFormats.audio(140, "audio/mp4; codecs=\"mp4a.40.2\"", null, 6))
        audio(GoldenFormats.audio(256, "audio/mp4; codecs=\"mp4a.40.5\"", 44100, 6))
        audio(GoldenFormats.audio(774, "audio/webm; codecs=\"opus\"", 48000, 2))
    }
}
