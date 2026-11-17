package io.github.aedev.flow.player.stream

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import org.junit.Test

/**
 * How each InnerTube video format family converts into the stream the player reads.
 *
 * Every field the app reads is frozen here as YouTube's formats convert today. Tests named
 * "itag table supplies" pin values that come from the extractor's itag table rather than the
 * response, which the app's own stream model has to reproduce.
 */
class VideoStreamGoldenTest {
    @Test
    fun `itag table supplies - VP9 1080p60 without fps takes 60 from the itag table`() {
        val g = GoldenFormats
        assertThat(convertVideo(listOf(g.VP9_1080_60.copy(fps = null))))
            .containsExactlyElementsIn(
                listOf(
                    VideoSnapshot(
                        id = "303",
                        url = GoldenFormats.url(303),
                        itag = 303,
                        containerMime = "video/webm",
                        suffix = "webm",
                        label = "1080p60",
                        videoOnly = true,
                        delivery = "PROGRESSIVE_HTTP",
                        codec = "vp9",
                        bitrate = 2900000,
                        width = 1920,
                        height = 1080,
                        fps = 60,
                        quality = "hd1080",
                        initStart = 0,
                        initEnd = 740,
                        indexStart = 741,
                        indexEnd = 1560,
                        contentLength = 40000000L,
                        approxDurationMs = 212000L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `itag table supplies - H264 1080p without fps or width - fps 30 from the itag table width stays 0`() {
        val g = GoldenFormats
        assertThat(convertVideo(listOf(g.H264_1080.copy(fps = null, width = null))))
            .containsExactlyElementsIn(
                listOf(
                    VideoSnapshot(
                        id = "137",
                        url = GoldenFormats.url(137),
                        itag = 137,
                        containerMime = "video/mp4",
                        suffix = "mp4",
                        label = "1080p",
                        videoOnly = true,
                        delivery = "PROGRESSIVE_HTTP",
                        codec = "avc1.640028",
                        bitrate = 2600000,
                        width = 0,
                        height = 1080,
                        fps = 30,
                        quality = "hd1080",
                        initStart = 0,
                        initEnd = 740,
                        indexStart = 741,
                        indexEnd = 1560,
                        contentLength = 40000000L,
                        approxDurationMs = 212000L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `a Shorts-tier format without fps width or label falls back to 30 fps and a height label`() {
        val g = GoldenFormats
        assertThat(convertVideo(listOf(g.SHORTS_780.copy(fps = null, width = null, qualityLabel = null))))
            .containsExactlyElementsIn(
                listOf(
                    VideoSnapshot(
                        id = "780",
                        url = GoldenFormats.url(780),
                        itag = 780,
                        containerMime = "video/webm",
                        suffix = "webm",
                        label = "1080p",
                        videoOnly = true,
                        delivery = "PROGRESSIVE_HTTP",
                        codec = "vp9",
                        bitrate = 950000,
                        width = 0,
                        height = 1080,
                        fps = 30,
                        quality = "large",
                        initStart = 0,
                        initEnd = 740,
                        indexStart = 741,
                        indexEnd = 1560,
                        contentLength = 40000000L,
                        approxDurationMs = 212000L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `muxed 360p itag 18 keeps the first codec and is marked video-only`() {
        val g = GoldenFormats
        assertThat(
            convertVideo(
                listOf(
                    g.video(
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
                ),
            ),
        ).containsExactlyElementsIn(
            listOf(
                VideoSnapshot(
                    id = "18",
                    url = GoldenFormats.url(18),
                    itag = 18,
                    containerMime = "video/mp4",
                    suffix = "mp4",
                    label = "360p",
                    videoOnly = true,
                    delivery = "PROGRESSIVE_HTTP",
                    codec = "avc1.42001e",
                    bitrate = 1500000,
                    width = 640,
                    height = 360,
                    fps = 30,
                    quality = "medium",
                    initStart = 0,
                    initEnd = 0,
                    indexStart = 0,
                    indexEnd = 0,
                    contentLength = 40000000L,
                    approxDurationMs = 212000L,
                    lastModified = GoldenFormats.LAST_MODIFIED,
                    described = true,
                ),
            ),
        ).inOrder()
    }

    @Test
    fun `itag table supplies - muxed 720p itag 22 without fps takes 30 from the itag table`() {
        val g = GoldenFormats
        assertThat(
            convertVideo(
                listOf(
                    g.video(
                        22,
                        "video/mp4; codecs=\"avc1.64001F, mp4a.40.2\"",
                        1280,
                        720,
                        null,
                        "720p",
                        "hd720",
                        initRange = null,
                        indexRange = null,
                    ),
                ),
            ),
        ).containsExactlyElementsIn(
            listOf(
                VideoSnapshot(
                    id = "22",
                    url = GoldenFormats.url(22),
                    itag = 22,
                    containerMime = "video/mp4",
                    suffix = "mp4",
                    label = "720p",
                    videoOnly = true,
                    delivery = "PROGRESSIVE_HTTP",
                    codec = "avc1.64001f",
                    bitrate = 1500000,
                    width = 1280,
                    height = 720,
                    fps = 30,
                    quality = "hd720",
                    initStart = 0,
                    initEnd = 0,
                    indexStart = 0,
                    indexEnd = 0,
                    contentLength = 40000000L,
                    approxDurationMs = 212000L,
                    lastModified = GoldenFormats.LAST_MODIFIED,
                    described = true,
                ),
            ),
        ).inOrder()
    }

    @Test
    fun `a format without a URL is dropped`() {
        val g = GoldenFormats
        assertThat(convertVideo(listOf(g.H264_1080.copy(url = null)))).isEmpty()
    }

    @Test
    fun `a video format without a height is dropped`() {
        val g = GoldenFormats
        assertThat(convertVideo(listOf(g.H264_1080.copy(height = null)))).isEmpty()
    }

    @Test
    fun `the response MIME wins over the itag table's container`() {
        val g = GoldenFormats
        assertThat(convertVideo(listOf(g.H264_1080.copy(mimeType = "video/webm; codecs=\"vp9\""))))
            .containsExactlyElementsIn(
                listOf(
                    VideoSnapshot(
                        id = "137",
                        url = GoldenFormats.url(137),
                        itag = 137,
                        containerMime = "video/webm",
                        suffix = "webm",
                        label = "1080p",
                        videoOnly = true,
                        delivery = "PROGRESSIVE_HTTP",
                        codec = "vp9",
                        bitrate = 2600000,
                        width = 1920,
                        height = 1080,
                        fps = 30,
                        quality = "hd1080",
                        initStart = 0,
                        initEnd = 740,
                        indexStart = 741,
                        indexEnd = 1560,
                        contentLength = 40000000L,
                        approxDurationMs = 212000L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `itag table supplies - an unparsable range start leaves the start at 0`() {
        val g = GoldenFormats
        assertThat(
            convertVideo(
                listOf(
                    g.video(160, "video/mp4; codecs=\"avc1.4d400c\"", 256, 144, null, null, "tiny", initRange = Format.Range("x", "740")),
                ),
            ),
        ).containsExactlyElementsIn(
            listOf(
                VideoSnapshot(
                    id = "160",
                    url = GoldenFormats.url(160),
                    itag = 160,
                    containerMime = "video/mp4",
                    suffix = "mp4",
                    label = "144p",
                    videoOnly = true,
                    delivery = "PROGRESSIVE_HTTP",
                    codec = "avc1.4d400c",
                    bitrate = 1500000,
                    width = 256,
                    height = 144,
                    fps = 30,
                    quality = "tiny",
                    initStart = 0,
                    initEnd = 740,
                    indexStart = 741,
                    indexEnd = 1560,
                    contentLength = 40000000L,
                    approxDurationMs = 212000L,
                    lastModified = GoldenFormats.LAST_MODIFIED,
                    described = true,
                ),
            ),
        ).inOrder()
    }

    @Test
    fun `H264 1080p`() {
        val g = GoldenFormats
        assertThat(convertVideo(listOf(g.H264_1080)))
            .containsExactlyElementsIn(
                listOf(
                    VideoSnapshot(
                        id = "137",
                        url = GoldenFormats.url(137),
                        itag = 137,
                        containerMime = "video/mp4",
                        suffix = "mp4",
                        label = "1080p",
                        videoOnly = true,
                        delivery = "PROGRESSIVE_HTTP",
                        codec = "avc1.640028",
                        bitrate = 2600000,
                        width = 1920,
                        height = 1080,
                        fps = 30,
                        quality = "hd1080",
                        initStart = 0,
                        initEnd = 740,
                        indexStart = 741,
                        indexEnd = 1560,
                        contentLength = 40000000L,
                        approxDurationMs = 212000L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `VP9 1080p60`() {
        val g = GoldenFormats
        assertThat(convertVideo(listOf(g.VP9_1080_60)))
            .containsExactlyElementsIn(
                listOf(
                    VideoSnapshot(
                        id = "303",
                        url = GoldenFormats.url(303),
                        itag = 303,
                        containerMime = "video/webm",
                        suffix = "webm",
                        label = "1080p60",
                        videoOnly = true,
                        delivery = "PROGRESSIVE_HTTP",
                        codec = "vp9",
                        bitrate = 2900000,
                        width = 1920,
                        height = 1080,
                        fps = 60,
                        quality = "hd1080",
                        initStart = 0,
                        initEnd = 740,
                        indexStart = 741,
                        indexEnd = 1560,
                        contentLength = 40000000L,
                        approxDurationMs = 212000L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `VP9 HDR 1080p60`() {
        val g = GoldenFormats
        assertThat(convertVideo(listOf(g.VP9_HDR_1080_60)))
            .containsExactlyElementsIn(
                listOf(
                    VideoSnapshot(
                        id = "335",
                        url = GoldenFormats.url(335),
                        itag = 335,
                        containerMime = "video/webm",
                        suffix = "webm",
                        label = "1080p60 HDR",
                        videoOnly = true,
                        delivery = "PROGRESSIVE_HTTP",
                        codec = "vp09.02.51.10.01.09.16.09.00",
                        bitrate = 4000000,
                        width = 1920,
                        height = 1080,
                        fps = 60,
                        quality = "hd1080",
                        initStart = 0,
                        initEnd = 740,
                        indexStart = 741,
                        indexEnd = 1560,
                        contentLength = 40000000L,
                        approxDurationMs = 212000L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `AV1 1080p codec lower-cased`() {
        val g = GoldenFormats
        assertThat(convertVideo(listOf(g.AV1_1080)))
            .containsExactlyElementsIn(
                listOf(
                    VideoSnapshot(
                        id = "399",
                        url = GoldenFormats.url(399),
                        itag = 399,
                        containerMime = "video/mp4",
                        suffix = "mp4",
                        label = "1080p",
                        videoOnly = true,
                        delivery = "PROGRESSIVE_HTTP",
                        codec = "av01.0.08m.08",
                        bitrate = 1400000,
                        width = 1920,
                        height = 1080,
                        fps = 30,
                        quality = "hd1080",
                        initStart = 0,
                        initEnd = 740,
                        indexStart = 741,
                        indexEnd = 1560,
                        contentLength = 40000000L,
                        approxDurationMs = 212000L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `3GPP 144p without ranges`() {
        val g = GoldenFormats
        assertThat(convertVideo(listOf(g.GPP_144)))
            .containsExactlyElementsIn(
                listOf(
                    VideoSnapshot(
                        id = "17",
                        url = GoldenFormats.url(17),
                        itag = 17,
                        containerMime = "video/3gpp",
                        suffix = "3gp",
                        label = "144p",
                        videoOnly = true,
                        delivery = "PROGRESSIVE_HTTP",
                        codec = "mp4v.20.3",
                        bitrate = 78000,
                        width = 176,
                        height = 144,
                        fps = 12,
                        quality = "small",
                        initStart = 0,
                        initEnd = 0,
                        indexStart = 0,
                        indexEnd = 0,
                        contentLength = 40000000L,
                        approxDurationMs = 212000L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `Shorts tier 779`() {
        val g = GoldenFormats
        assertThat(convertVideo(listOf(g.SHORTS_779)))
            .containsExactlyElementsIn(
                listOf(
                    VideoSnapshot(
                        id = "779",
                        url = GoldenFormats.url(779),
                        itag = 779,
                        containerMime = "video/webm",
                        suffix = "webm",
                        label = "480p",
                        videoOnly = true,
                        delivery = "PROGRESSIVE_HTTP",
                        codec = "vp9",
                        bitrate = 700000,
                        width = 608,
                        height = 1080,
                        fps = 30,
                        quality = "large",
                        initStart = 0,
                        initEnd = 740,
                        indexStart = 741,
                        indexEnd = 1560,
                        contentLength = 40000000L,
                        approxDurationMs = 212000L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `Shorts tier 780`() {
        val g = GoldenFormats
        assertThat(convertVideo(listOf(g.SHORTS_780)))
            .containsExactlyElementsIn(
                listOf(
                    VideoSnapshot(
                        id = "780",
                        url = GoldenFormats.url(780),
                        itag = 780,
                        containerMime = "video/webm",
                        suffix = "webm",
                        label = "480p60",
                        videoOnly = true,
                        delivery = "PROGRESSIVE_HTTP",
                        codec = "vp9",
                        bitrate = 950000,
                        width = 608,
                        height = 1080,
                        fps = 60,
                        quality = "large",
                        initStart = 0,
                        initEnd = 740,
                        indexStart = 741,
                        indexEnd = 1560,
                        contentLength = 40000000L,
                        approxDurationMs = 212000L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `Shorts tier 787`() {
        val g = GoldenFormats
        assertThat(convertVideo(listOf(g.SHORTS_787)))
            .containsExactlyElementsIn(
                listOf(
                    VideoSnapshot(
                        id = "787",
                        url = GoldenFormats.url(787),
                        itag = 787,
                        containerMime = "video/mp4",
                        suffix = "mp4",
                        label = "480p",
                        videoOnly = true,
                        delivery = "PROGRESSIVE_HTTP",
                        codec = "av01.0.05m.08",
                        bitrate = 600000,
                        width = 608,
                        height = 1080,
                        fps = 30,
                        quality = "large",
                        initStart = 0,
                        initEnd = 740,
                        indexStart = 741,
                        indexEnd = 1560,
                        contentLength = 40000000L,
                        approxDurationMs = 212000L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `Shorts tier 788`() {
        val g = GoldenFormats
        assertThat(convertVideo(listOf(g.SHORTS_788)))
            .containsExactlyElementsIn(
                listOf(
                    VideoSnapshot(
                        id = "788",
                        url = GoldenFormats.url(788),
                        itag = 788,
                        containerMime = "video/mp4",
                        suffix = "mp4",
                        label = "480p60",
                        videoOnly = true,
                        delivery = "PROGRESSIVE_HTTP",
                        codec = "av01.0.05m.08",
                        bitrate = 820000,
                        width = 608,
                        height = 1080,
                        fps = 60,
                        quality = "large",
                        initStart = 0,
                        initEnd = 740,
                        indexStart = 741,
                        indexEnd = 1560,
                        contentLength = 40000000L,
                        approxDurationMs = 212000L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `itag table supplies - H264 720p with only height - fps 30 from the itag table lengths unknown`() {
        val g = GoldenFormats
        assertThat(
            convertVideo(
                listOf(
                    g.video(
                        136,
                        "video/mp4; codecs=\"avc1.4d401f\"",
                        null,
                        720,
                        null,
                        null,
                        "hd720",
                        averageBitrate = null,
                        contentLength = null,
                        approxDurationMs = null,
                    ),
                ),
            ),
        ).containsExactlyElementsIn(
            listOf(
                VideoSnapshot(
                    id = "136",
                    url = GoldenFormats.url(136),
                    itag = 136,
                    containerMime = "video/mp4",
                    suffix = "mp4",
                    label = "720p",
                    videoOnly = true,
                    delivery = "PROGRESSIVE_HTTP",
                    codec = "avc1.4d401f",
                    bitrate = 2000000,
                    width = 0,
                    height = 720,
                    fps = 30,
                    quality = "hd720",
                    initStart = 0,
                    initEnd = 740,
                    indexStart = 741,
                    indexEnd = 1560,
                    contentLength = -1L,
                    approxDurationMs = -1L,
                    lastModified = GoldenFormats.LAST_MODIFIED,
                    described = true,
                ),
            ),
        ).inOrder()
    }

    @Test
    fun `VP9 without a codec string`() {
        val g = GoldenFormats
        assertThat(convertVideo(listOf(g.video(248, "video/webm", 1920, 1080, 30, "1080p", "hd1080"))))
            .containsExactlyElementsIn(
                listOf(
                    VideoSnapshot(
                        id = "248",
                        url = GoldenFormats.url(248),
                        itag = 248,
                        containerMime = "video/webm",
                        suffix = "webm",
                        label = "1080p",
                        videoOnly = true,
                        delivery = "PROGRESSIVE_HTTP",
                        codec = null,
                        bitrate = 1500000,
                        width = 1920,
                        height = 1080,
                        fps = 30,
                        quality = "hd1080",
                        initStart = 0,
                        initEnd = 740,
                        indexStart = 741,
                        indexEnd = 1560,
                        contentLength = 40000000L,
                        approxDurationMs = 212000L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `an unknown video MIME is dropped`() {
        val g = GoldenFormats
        assertThat(convertVideo(listOf(g.video(5000, "video/x-flv", 1, 1, 1, "1p", "q")))).isEmpty()
    }

    @Test
    fun `an unknown itag with a blank label is described from the response`() {
        val g = GoldenFormats
        assertThat(convertVideo(listOf(g.video(9999, "video/mp4; codecs=\"avc1.4d401f\"", 1280, 720, 25, "", "hd720"))))
            .containsExactlyElementsIn(
                listOf(
                    VideoSnapshot(
                        id = "9999",
                        url = GoldenFormats.url(9999),
                        itag = 9999,
                        containerMime = "video/mp4",
                        suffix = "mp4",
                        label = "720p",
                        videoOnly = true,
                        delivery = "PROGRESSIVE_HTTP",
                        codec = "avc1.4d401f",
                        bitrate = 1500000,
                        width = 1280,
                        height = 720,
                        fps = 25,
                        quality = "hd720",
                        initStart = 0,
                        initEnd = 740,
                        indexStart = 741,
                        indexEnd = 1560,
                        contentLength = 40000000L,
                        approxDurationMs = 212000L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        described = true,
                    ),
                ),
            ).inOrder()
    }
}
