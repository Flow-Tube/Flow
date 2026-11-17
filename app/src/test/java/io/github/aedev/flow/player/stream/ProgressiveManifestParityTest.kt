package io.github.aedev.flow.player.stream

import androidx.media3.common.C
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.shorts.ShortsDashManifest
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import org.junit.Test

/**
 * The single-representation DASH manifests the player and the Shorts pool wrap a progressive URL
 * in, frozen attribute by attribute. A builder on Media3's own manifest model has to produce the
 * same values, read back through [ManifestSnapshot].
 *
 * Each case gets its own URL: the extractor's manifest creator caches by URL, so a reused one would
 * return an earlier case's manifest.
 */
class ProgressiveManifestParityTest {
    private val g = GoldenFormats

    private fun Format.at(case: String) = copy(url = "https://rr3---sn-golden.googlevideo.com/videoplayback?itag=$itag&case=$case")

    private fun video(
        format: Format,
        durationSeconds: Long = 212,
    ) = progressiveVideoManifest(format, durationSeconds)?.let { ManifestSnapshot.fromXml(it) }

    private fun audio(
        format: Format,
        durationSeconds: Long = 212,
    ) = progressiveAudioManifest(format, durationSeconds)?.let { ManifestSnapshot.fromXml(it) }

    @Test
    fun `H264 1080p`() {
        val format = g.H264_1080.at("h264")
        assertThat(video(format)).isEqualTo(
            single(212_000L, videoRep("137", "video/mp4", "avc1.640028", 2_600_000, 1920, 1080, 30f, format.url!!)),
        )
    }

    @Test
    fun `VP9 HDR 1080p60`() {
        val format = g.VP9_HDR_1080_60.at("hdr")
        assertThat(video(format)).isEqualTo(
            single(212_000L, videoRep("335", "video/webm", "vp09.02.51.10.01.09.16.09.00", 4_000_000, 1920, 1080, 60f, format.url!!)),
        )
    }

    @Test
    fun `AV1 1080p keeps the lower-cased codec`() {
        val format = g.AV1_1080.at("av1")
        assertThat(video(format)).isEqualTo(
            single(212_000L, videoRep("399", "video/mp4", "av01.0.08m.08", 1_400_000, 1920, 1080, 30f, format.url!!)),
        )
    }

    @Test
    fun `a Shorts-tier itag the table lacks is described from the response`() {
        val format = g.SHORTS_780.at("shorts")
        assertThat(video(format)).isEqualTo(
            single(212_000L, videoRep("780", "video/webm", "vp9", 950_000, 608, 1080, 60f, format.url!!)),
        )
    }

    @Test
    fun `3GPP without byte ranges gets zero-length ranges`() {
        val format = g.GPP_144.at("3gpp")
        assertThat(video(format)).isEqualTo(
            single(
                212_000L,
                videoRep("17", "video/3gpp", "mp4v.20.3", 78_000, 176, 144, 12f, format.url!!, init = 0L to 1L, index = 0L to 1L),
            ),
        )
    }

    @Test
    fun `itag table supplies - the container MIME of a known itag, even against the response`() {
        val format = g.H264_1080.copy(mimeType = "video/webm; codecs=\"vp9\"").at("mismatch")
        assertThat(video(format)).isEqualTo(
            single(212_000L, videoRep("137", "video/mp4", "vp9", 2_600_000, 1920, 1080, 30f, format.url!!)),
        )
    }

    @Test
    fun `itag table supplies - the frame rate of a known itag, and a missing width is left out`() {
        val format = g.H264_1080.copy(fps = null, width = null).at("nofps")
        assertThat(video(format)).isEqualTo(
            single(212_000L, videoRep("137", "video/mp4", "avc1.640028", 2_600_000, -1, 1080, 30f, format.url!!)),
        )
    }

    @Test
    fun `the duration comes from the format and falls back to the one passed in`() {
        val format = g.H264_1080.copy(approxDurationMs = null).at("noapprox")
        assertThat(video(format, durationSeconds = 30)!!.durationMs).isEqualTo(30_000L)
        assertThat(video(g.H264_1080.copy(approxDurationMs = null).at("dur0"), durationSeconds = 0)).isNull()
    }

    @Test
    fun `an unknown content length does not matter`() {
        val format = g.H264_1080.copy(contentLength = null).at("nolength")
        assertThat(video(format)).isEqualTo(
            single(212_000L, videoRep("137", "video/mp4", "avc1.640028", 2_600_000, 1920, 1080, 30f, format.url!!)),
        )
    }

    @Test
    fun `M4A 140`() {
        val format = g.M4A_140.at("m4a")
        assertThat(audio(format)).isEqualTo(single(212_041L, audioRep("140", "audio/mp4", "mp4a.40.2", 129_500, format.url!!)))
    }

    @Test
    fun `Opus 251`() {
        val format = g.OPUS_251.at("opus")
        assertThat(audio(format)).isEqualTo(single(212_041L, audioRep("251", "audio/webm", "opus", 135_000, format.url!!)))
    }

    @Test
    fun `audio without a channel count gets no manifest`() {
        assertThat(audio(g.audio(140, "audio/mp4; codecs=\"mp4a.40.2\"", null, null).at("nochannels"))).isNull()
    }

    @Test
    fun `audio itags the table lacks get no manifest`() {
        assertThat(audio(g.audio(774, "audio/webm; codecs=\"opus\"", 48_000, 2).at("774"))).isNull()
        assertThat(audio(g.audio(256, "audio/mp4; codecs=\"mp4a.40.5\"", 48_000, 6).at("256"))).isNull()
    }

    @Test
    fun `Shorts video manifest uses the resolved URL`() {
        val manifest = ShortsDashManifest.forVideo(g.SHORTS_788.at("orig"), "https://resolved.invalid/788", 28_500)
        assertThat(manifest?.let { ManifestSnapshot.fromXml(it) }).isEqualTo(
            single(212_000L, videoRep("788", "video/mp4", "av01.0.05m.08", 820_000, 608, 1080, 60f, "https://resolved.invalid/788")),
        )
    }

    @Test
    fun `Shorts audio manifest uses the resolved URL`() {
        val manifest = ShortsDashManifest.forAudio(g.OPUS_251.at("orig"), "https://resolved.invalid/251", 28_500)
        assertThat(manifest?.let { ManifestSnapshot.fromXml(it) }).isEqualTo(
            single(212_041L, audioRep("251", "audio/webm", "opus", 135_000, "https://resolved.invalid/251")),
        )
    }

    @Test
    fun `Shorts manifest over an already converted stream`() {
        val video = g.SHORTS_787.at("converted")
        val audio = g.M4A_140.at("converted")
        assertThat(shortsManifestForConvertedVideo(video, 28_500)?.let { ManifestSnapshot.fromXml(it) }).isEqualTo(
            single(212_000L, videoRep("787", "video/mp4", "av01.0.05m.08", 600_000, 608, 1080, 30f, video.url!!)),
        )
        assertThat(shortsManifestForConvertedAudio(audio, 28_500)?.let { ManifestSnapshot.fromXml(it) }).isEqualTo(
            single(212_041L, audioRep("140", "audio/mp4", "mp4a.40.2", 129_500, audio.url!!)),
        )
    }

    @Test
    fun `Shorts gets no manifest without a duration of at least a second, a URL, ranges or a known format`() {
        assertThat(ShortsDashManifest.forVideo(g.SHORTS_788, "https://resolved.invalid/a", null)).isNull()
        assertThat(ShortsDashManifest.forVideo(g.SHORTS_788, "https://resolved.invalid/b", 999)).isNull()
        assertThat(ShortsDashManifest.forVideo(g.SHORTS_788, "", 28_500)).isNull()
        assertThat(
            ShortsDashManifest.forVideo(g.SHORTS_788.copy(initRange = null, indexRange = null), "https://resolved.invalid/c", 28_500),
        ).isNull()
        assertThat(
            ShortsDashManifest.forAudio(g.audio(774, "audio/webm; codecs=\"opus\"", 48_000, 2), "https://resolved.invalid/d", 28_500),
        ).isNull()
        assertThat(ShortsDashManifest.forVideo(g.SHORTS_788.copy(mimeType = "video/x-flv"), "https://resolved.invalid/e", 28_500)).isNull()
    }
}

internal fun single(
    durationMs: Long,
    representation: RepresentationSnapshot,
) = ManifestSnapshot(
    dynamic = false,
    durationMs = durationMs,
    minBufferTimeMs = 1_500L,
    periods = 1,
    representations = listOf(representation),
)

internal fun videoRep(
    id: String,
    mime: String,
    codecs: String,
    bitrate: Int,
    width: Int,
    height: Int,
    frameRate: Float,
    url: String,
    init: Pair<Long, Long> = 0L to 741L,
    index: Pair<Long, Long> = 741L to 820L,
) = RepresentationSnapshot(
    trackType = C.TRACK_TYPE_VIDEO,
    id = id,
    containerMime = mime,
    codecs = codecs,
    bitrate = bitrate,
    width = width,
    height = height,
    frameRate = frameRate,
    sampleRate = -1,
    channels = -1,
    baseUrl = url,
    initStart = init.first,
    initLength = init.second,
    indexStart = index.first,
    indexLength = index.second,
)

/** Audio as the extractor writes it: channel count, but no sampling rate attribute. */
internal fun audioRep(
    id: String,
    mime: String,
    codecs: String,
    bitrate: Int,
    url: String,
) = RepresentationSnapshot(
    trackType = C.TRACK_TYPE_AUDIO,
    id = id,
    containerMime = mime,
    codecs = codecs,
    bitrate = bitrate,
    width = -1,
    height = -1,
    frameRate = -1f,
    sampleRate = -1,
    channels = 2,
    baseUrl = url,
    initStart = 0L,
    initLength = 632L,
    indexStart = 632L,
    indexLength = 372L,
)
