package io.github.aedev.flow.player.stream

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import org.junit.Test

/**
 * How each InnerTube audio format family converts into the stream the player reads.
 *
 * Every field the app reads is frozen here as YouTube's formats convert today. Tests named
 * "itag table supplies" pin values that come from the extractor's itag table rather than the
 * response, which the app's own stream model has to reproduce.
 */
class AudioStreamGoldenTest {
    @Test
    fun `M4A 140`() {
        val g = GoldenFormats
        assertThat(convertAudio(listOf(g.M4A_140)))
            .containsExactlyElementsIn(
                listOf(
                    AudioSnapshot(
                        id = "140",
                        url = GoldenFormats.url(140),
                        itag = 140,
                        containerMime = "audio/mp4",
                        suffix = "m4a",
                        delivery = "PROGRESSIVE_HTTP",
                        averageBitrate = 129500,
                        bitrate = 129500,
                        codec = "mp4a.40.2",
                        initStart = 0,
                        initEnd = 631,
                        indexStart = 632,
                        indexEnd = 1003,
                        contentLength = 3400000L,
                        approxDurationMs = 212041L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        sampleRate = 44100,
                        channels = 2,
                        trackId = null,
                        trackName = null,
                        trackKind = "ORIGINAL",
                        locale = null,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `Opus 251`() {
        val g = GoldenFormats
        assertThat(convertAudio(listOf(g.OPUS_251)))
            .containsExactlyElementsIn(
                listOf(
                    AudioSnapshot(
                        id = "251",
                        url = GoldenFormats.url(251),
                        itag = 251,
                        containerMime = "audio/webm",
                        suffix = "webm",
                        delivery = "PROGRESSIVE_HTTP",
                        averageBitrate = 135000,
                        bitrate = 135000,
                        codec = "opus",
                        initStart = 0,
                        initEnd = 631,
                        indexStart = 632,
                        indexEnd = 1003,
                        contentLength = 3400000L,
                        approxDurationMs = 212041L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        sampleRate = 48000,
                        channels = 2,
                        trackId = null,
                        trackName = null,
                        trackKind = "ORIGINAL",
                        locale = null,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `Opus 250`() {
        val g = GoldenFormats
        assertThat(convertAudio(listOf(g.OPUS_250)))
            .containsExactlyElementsIn(
                listOf(
                    AudioSnapshot(
                        id = "250",
                        url = GoldenFormats.url(250),
                        itag = 250,
                        containerMime = "audio/webm",
                        suffix = "webm",
                        delivery = "PROGRESSIVE_HTTP",
                        averageBitrate = 70000,
                        bitrate = 70000,
                        codec = "opus",
                        initStart = 0,
                        initEnd = 631,
                        indexStart = 632,
                        indexEnd = 1003,
                        contentLength = 3400000L,
                        approxDurationMs = 212041L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        sampleRate = 48000,
                        channels = 2,
                        trackId = null,
                        trackName = null,
                        trackKind = "ORIGINAL",
                        locale = null,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `Vorbis 171`() {
        val g = GoldenFormats
        assertThat(convertAudio(listOf(g.VORBIS_171)))
            .containsExactlyElementsIn(
                listOf(
                    AudioSnapshot(
                        id = "171",
                        url = GoldenFormats.url(171),
                        itag = 171,
                        containerMime = "audio/webm",
                        suffix = "webm",
                        delivery = "PROGRESSIVE_HTTP",
                        averageBitrate = 117000,
                        bitrate = 117000,
                        codec = "vorbis",
                        initStart = 0,
                        initEnd = 631,
                        indexStart = 632,
                        indexEnd = 1003,
                        contentLength = 3400000L,
                        approxDurationMs = 212041L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        sampleRate = 44100,
                        channels = 2,
                        trackId = null,
                        trackName = null,
                        trackKind = "ORIGINAL",
                        locale = null,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `itag table supplies - M4A 140 without sample rate or channels - the itag table supplies neither`() {
        val g = GoldenFormats
        assertThat(convertAudio(listOf(g.audio(140, "audio/mp4; codecs=\"mp4a.40.2\"", null, null))))
            .containsExactlyElementsIn(
                listOf(
                    AudioSnapshot(
                        id = "140",
                        url = GoldenFormats.url(140),
                        itag = 140,
                        containerMime = "audio/mp4",
                        suffix = "m4a",
                        delivery = "PROGRESSIVE_HTTP",
                        averageBitrate = 129000,
                        bitrate = 129000,
                        codec = "mp4a.40.2",
                        initStart = 0,
                        initEnd = 631,
                        indexStart = 632,
                        indexEnd = 1003,
                        contentLength = 3400000L,
                        approxDurationMs = 212041L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        sampleRate = -1,
                        channels = -1,
                        trackId = null,
                        trackName = null,
                        trackKind = "ORIGINAL",
                        locale = null,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `itag table supplies - Opus 251 without sample rate channels or average bitrate`() {
        val g = GoldenFormats
        assertThat(convertAudio(listOf(g.audio(251, "audio/webm; codecs=\"opus\"", null, null, averageBitrate = null))))
            .containsExactlyElementsIn(
                listOf(
                    AudioSnapshot(
                        id = "251",
                        url = GoldenFormats.url(251),
                        itag = 251,
                        containerMime = "audio/webm",
                        suffix = "webm",
                        delivery = "PROGRESSIVE_HTTP",
                        averageBitrate = 140000,
                        bitrate = 140000,
                        codec = "opus",
                        initStart = 0,
                        initEnd = 631,
                        indexStart = 632,
                        indexEnd = 1003,
                        contentLength = 3400000L,
                        approxDurationMs = 212041L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        sampleRate = -1,
                        channels = -1,
                        trackId = null,
                        trackName = null,
                        trackKind = "ORIGINAL",
                        locale = null,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `an audio itag missing from the table is left undescribed`() {
        val g = GoldenFormats
        assertThat(convertAudio(listOf(g.audio(9998, "audio/mp4; codecs=\"mp4a.40.2\"", 44_100, 2))))
            .containsExactlyElementsIn(
                listOf(
                    AudioSnapshot(
                        id = "9998",
                        url = GoldenFormats.url(9998),
                        itag = -1,
                        containerMime = "audio/mp4",
                        suffix = "m4a",
                        delivery = "PROGRESSIVE_HTTP",
                        averageBitrate = 129000,
                        bitrate = 0,
                        codec = null,
                        initStart = 0,
                        initEnd = 0,
                        indexStart = 0,
                        indexEnd = 0,
                        contentLength = -1L,
                        approxDurationMs = -1L,
                        lastModified = -1L,
                        sampleRate = -1,
                        channels = -1,
                        trackId = null,
                        trackName = null,
                        trackKind = "ORIGINAL",
                        locale = null,
                        described = false,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `Opus 600`() {
        val g = GoldenFormats
        assertThat(convertAudio(listOf(g.audio(600, "audio/webm; codecs=\"opus\"", 48_000, 2))))
            .containsExactlyElementsIn(
                listOf(
                    AudioSnapshot(
                        id = "600",
                        url = GoldenFormats.url(600),
                        itag = 600,
                        containerMime = "audio/webm",
                        suffix = "webm",
                        delivery = "PROGRESSIVE_HTTP",
                        averageBitrate = 129000,
                        bitrate = 129000,
                        codec = "opus",
                        initStart = 0,
                        initEnd = 631,
                        indexStart = 632,
                        indexEnd = 1003,
                        contentLength = 3400000L,
                        approxDurationMs = 212041L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        sampleRate = 48000,
                        channels = 2,
                        trackId = null,
                        trackName = null,
                        trackKind = "ORIGINAL",
                        locale = null,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `HE-AAC 599`() {
        val g = GoldenFormats
        assertThat(convertAudio(listOf(g.audio(599, "audio/mp4; codecs=\"mp4a.40.5\"", 22_050, 2))))
            .containsExactlyElementsIn(
                listOf(
                    AudioSnapshot(
                        id = "599",
                        url = GoldenFormats.url(599),
                        itag = 599,
                        containerMime = "audio/mp4",
                        suffix = "m4a",
                        delivery = "PROGRESSIVE_HTTP",
                        averageBitrate = 129000,
                        bitrate = 129000,
                        codec = "mp4a.40.5",
                        initStart = 0,
                        initEnd = 631,
                        indexStart = 632,
                        indexEnd = 1003,
                        contentLength = 3400000L,
                        approxDurationMs = 212041L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        sampleRate = 22050,
                        channels = 2,
                        trackId = null,
                        trackName = null,
                        trackKind = "ORIGINAL",
                        locale = null,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `Opus 774 premium is missing from the table and left undescribed`() {
        val g = GoldenFormats
        assertThat(convertAudio(listOf(g.audio(774, "audio/webm; codecs=\"opus\"", 48_000, 2))))
            .containsExactlyElementsIn(
                listOf(
                    AudioSnapshot(
                        id = "774",
                        url = GoldenFormats.url(774),
                        itag = -1,
                        containerMime = "audio/webm",
                        suffix = "webm",
                        delivery = "PROGRESSIVE_HTTP",
                        averageBitrate = 129000,
                        bitrate = 0,
                        codec = null,
                        initStart = 0,
                        initEnd = 0,
                        indexStart = 0,
                        indexEnd = 0,
                        contentLength = -1L,
                        approxDurationMs = -1L,
                        lastModified = -1L,
                        sampleRate = -1,
                        channels = -1,
                        trackId = null,
                        trackName = null,
                        trackKind = "ORIGINAL",
                        locale = null,
                        described = false,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `M4A 141`() {
        val g = GoldenFormats
        assertThat(convertAudio(listOf(g.audio(141, "audio/mp4; codecs=\"mp4a.40.2\"", 44_100, 2))))
            .containsExactlyElementsIn(
                listOf(
                    AudioSnapshot(
                        id = "141",
                        url = GoldenFormats.url(141),
                        itag = 141,
                        containerMime = "audio/mp4",
                        suffix = "m4a",
                        delivery = "PROGRESSIVE_HTTP",
                        averageBitrate = 129000,
                        bitrate = 129000,
                        codec = "mp4a.40.2",
                        initStart = 0,
                        initEnd = 631,
                        indexStart = 632,
                        indexEnd = 1003,
                        contentLength = 3400000L,
                        approxDurationMs = 212041L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        sampleRate = 44100,
                        channels = 2,
                        trackId = null,
                        trackName = null,
                        trackKind = "ORIGINAL",
                        locale = null,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `51 M4A 258 is missing from the table and left undescribed`() {
        val g = GoldenFormats
        assertThat(convertAudio(listOf(g.audio(258, "audio/mp4; codecs=\"mp4a.40.2\"", 48_000, 6))))
            .containsExactlyElementsIn(
                listOf(
                    AudioSnapshot(
                        id = "258",
                        url = GoldenFormats.url(258),
                        itag = -1,
                        containerMime = "audio/mp4",
                        suffix = "m4a",
                        delivery = "PROGRESSIVE_HTTP",
                        averageBitrate = 129000,
                        bitrate = 0,
                        codec = null,
                        initStart = 0,
                        initEnd = 0,
                        indexStart = 0,
                        indexEnd = 0,
                        contentLength = -1L,
                        approxDurationMs = -1L,
                        lastModified = -1L,
                        sampleRate = -1,
                        channels = -1,
                        trackId = null,
                        trackName = null,
                        trackKind = "ORIGINAL",
                        locale = null,
                        described = false,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `an unknown audio MIME is dropped`() {
        val g = GoldenFormats
        assertThat(convertAudio(listOf(g.audio(140, "audio/ogg", 1, 1)))).isEmpty()
    }

    @Test
    fun `a dubbed track`() {
        val g = GoldenFormats
        assertThat(
            convertAudio(
                listOf(
                    g.audio(
                        251,
                        "audio/webm; codecs=\"opus\"",
                        48_000,
                        2,
                        track = Format.AudioTrack("Spanish", "es.3", audioIsDefault = false),
                    ),
                ),
            ),
        ).containsExactlyElementsIn(
            listOf(
                AudioSnapshot(
                    id = "251",
                    url = GoldenFormats.url(251),
                    itag = 251,
                    containerMime = "audio/webm",
                    suffix = "webm",
                    delivery = "PROGRESSIVE_HTTP",
                    averageBitrate = 129000,
                    bitrate = 129000,
                    codec = "opus",
                    initStart = 0,
                    initEnd = 631,
                    indexStart = 632,
                    indexEnd = 1003,
                    contentLength = 3400000L,
                    approxDurationMs = 212041L,
                    lastModified = GoldenFormats.LAST_MODIFIED,
                    sampleRate = 48000,
                    channels = 2,
                    trackId = "es.3",
                    trackName = "Spanish",
                    trackKind = "DUBBED",
                    locale = "es",
                    described = true,
                ),
            ),
        ).inOrder()
    }

    @Test
    fun `the original track`() {
        val g = GoldenFormats
        assertThat(
            convertAudio(
                listOf(
                    g.audio(
                        251,
                        "audio/webm; codecs=\"opus\"",
                        48_000,
                        2,
                        track = Format.AudioTrack("English original", "en.4", audioIsDefault = true),
                    ),
                ),
            ),
        ).containsExactlyElementsIn(
            listOf(
                AudioSnapshot(
                    id = "251",
                    url = GoldenFormats.url(251),
                    itag = 251,
                    containerMime = "audio/webm",
                    suffix = "webm",
                    delivery = "PROGRESSIVE_HTTP",
                    averageBitrate = 129000,
                    bitrate = 129000,
                    codec = "opus",
                    initStart = 0,
                    initEnd = 631,
                    indexStart = 632,
                    indexEnd = 1003,
                    contentLength = 3400000L,
                    approxDurationMs = 212041L,
                    lastModified = GoldenFormats.LAST_MODIFIED,
                    sampleRate = 48000,
                    channels = 2,
                    trackId = "en.4",
                    trackName = "English original",
                    trackKind = "ORIGINAL",
                    locale = "en",
                    described = true,
                ),
            ),
        ).inOrder()
    }

    @Test
    fun `an auto-dubbed track named by xtags`() {
        val g = GoldenFormats
        assertThat(
            convertAudio(
                listOf(
                    g.audio(
                        251,
                        "audio/webm; codecs=\"opus\"",
                        48_000,
                        2,
                        xtags =
                            g.xtags(
                                "acont" to "dubbed-auto",
                                "lang" to "fr",
                            ),
                    ),
                ),
            ),
        ).containsExactlyElementsIn(
            listOf(
                AudioSnapshot(
                    id = "251",
                    url = GoldenFormats.url(251),
                    itag = 251,
                    containerMime = "audio/webm",
                    suffix = "webm",
                    delivery = "PROGRESSIVE_HTTP",
                    averageBitrate = 129000,
                    bitrate = 129000,
                    codec = "opus",
                    initStart = 0,
                    initEnd = 631,
                    indexStart = 632,
                    indexEnd = 1003,
                    contentLength = 3400000L,
                    approxDurationMs = 212041L,
                    lastModified = GoldenFormats.LAST_MODIFIED,
                    sampleRate = 48000,
                    channels = 2,
                    trackId = null,
                    trackName = null,
                    trackKind = "DUBBED",
                    locale = "fr",
                    described = true,
                ),
            ),
        ).inOrder()
    }

    @Test
    fun `an original track named by xtags`() {
        val g = GoldenFormats
        assertThat(
            convertAudio(
                listOf(
                    g.audio(
                        251,
                        "audio/webm; codecs=\"opus\"",
                        48_000,
                        2,
                        xtags =
                            g.xtags(
                                "acont" to "original",
                                "lang" to "de",
                            ),
                    ),
                ),
            ),
        ).containsExactlyElementsIn(
            listOf(
                AudioSnapshot(
                    id = "251",
                    url = GoldenFormats.url(251),
                    itag = 251,
                    containerMime = "audio/webm",
                    suffix = "webm",
                    delivery = "PROGRESSIVE_HTTP",
                    averageBitrate = 129000,
                    bitrate = 129000,
                    codec = "opus",
                    initStart = 0,
                    initEnd = 631,
                    indexStart = 632,
                    indexEnd = 1003,
                    contentLength = 3400000L,
                    approxDurationMs = 212041L,
                    lastModified = GoldenFormats.LAST_MODIFIED,
                    sampleRate = 48000,
                    channels = 2,
                    trackId = null,
                    trackName = null,
                    trackKind = "ORIGINAL",
                    locale = "de",
                    described = true,
                ),
            ),
        ).inOrder()
    }

    @Test
    fun `an auto-dubbed track named by its audioTrack`() {
        val g = GoldenFormats
        assertThat(
            convertAudio(
                listOf(
                    g.audio(
                        251,
                        "audio/webm; codecs=\"opus\"",
                        48_000,
                        2,
                        track = Format.AudioTrack("Hindi (auto-dubbed)", "hi.3", isAutoDubbed = true),
                    ),
                ),
            ),
        ).containsExactlyElementsIn(
            listOf(
                AudioSnapshot(
                    id = "251",
                    url = GoldenFormats.url(251),
                    itag = 251,
                    containerMime = "audio/webm",
                    suffix = "webm",
                    delivery = "PROGRESSIVE_HTTP",
                    averageBitrate = 129000,
                    bitrate = 129000,
                    codec = "opus",
                    initStart = 0,
                    initEnd = 631,
                    indexStart = 632,
                    indexEnd = 1003,
                    contentLength = 3400000L,
                    approxDurationMs = 212041L,
                    lastModified = GoldenFormats.LAST_MODIFIED,
                    sampleRate = 48000,
                    channels = 2,
                    trackId = "hi.3",
                    trackName = "Hindi (auto-dubbed)",
                    trackKind = "DUBBED",
                    locale = "hi",
                    described = true,
                ),
            ),
        ).inOrder()
    }

    @Test
    fun `a track id with no language keeps no locale`() {
        val g = GoldenFormats
        assertThat(
            convertAudio(listOf(g.audio(251, "audio/webm; codecs=\"opus\"", 48_000, 2, track = Format.AudioTrack("x", "zz-invalid-")))),
        ).containsExactlyElementsIn(
            listOf(
                AudioSnapshot(
                    id = "251",
                    url = GoldenFormats.url(251),
                    itag = 251,
                    containerMime = "audio/webm",
                    suffix = "webm",
                    delivery = "PROGRESSIVE_HTTP",
                    averageBitrate = 129000,
                    bitrate = 129000,
                    codec = "opus",
                    initStart = 0,
                    initEnd = 631,
                    indexStart = 632,
                    indexEnd = 1003,
                    contentLength = 3400000L,
                    approxDurationMs = 212041L,
                    lastModified = GoldenFormats.LAST_MODIFIED,
                    sampleRate = 48000,
                    channels = 2,
                    trackId = "zz-invalid-",
                    trackName = "x",
                    trackKind = "ORIGINAL",
                    locale = null,
                    described = true,
                ),
            ),
        ).inOrder()
    }

    @Test
    fun `the DRC twin of a normal track is dropped`() {
        val g = GoldenFormats
        assertThat(convertAudio(listOf(g.OPUS_251, g.OPUS_251.copy(isDrc = true, url = "drc"))))
            .containsExactlyElementsIn(
                listOf(
                    AudioSnapshot(
                        id = "251",
                        url = GoldenFormats.url(251),
                        itag = 251,
                        containerMime = "audio/webm",
                        suffix = "webm",
                        delivery = "PROGRESSIVE_HTTP",
                        averageBitrate = 135000,
                        bitrate = 135000,
                        codec = "opus",
                        initStart = 0,
                        initEnd = 631,
                        indexStart = 632,
                        indexEnd = 1003,
                        contentLength = 3400000L,
                        approxDurationMs = 212041L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        sampleRate = 48000,
                        channels = 2,
                        trackId = null,
                        trackName = null,
                        trackKind = "ORIGINAL",
                        locale = null,
                        described = true,
                    ),
                ),
            ).inOrder()
    }

    @Test
    fun `a DRC format with no normal twin is kept`() {
        val g = GoldenFormats
        assertThat(convertAudio(listOf(g.OPUS_251.copy(isDrc = true, url = "drc"))))
            .containsExactlyElementsIn(
                listOf(
                    AudioSnapshot(
                        id = "251",
                        url = "drc",
                        itag = 251,
                        containerMime = "audio/webm",
                        suffix = "webm",
                        delivery = "PROGRESSIVE_HTTP",
                        averageBitrate = 135000,
                        bitrate = 135000,
                        codec = "opus",
                        initStart = 0,
                        initEnd = 631,
                        indexStart = 632,
                        indexEnd = 1003,
                        contentLength = 3400000L,
                        approxDurationMs = 212041L,
                        lastModified = GoldenFormats.LAST_MODIFIED,
                        sampleRate = 48000,
                        channels = 2,
                        trackId = null,
                        trackName = null,
                        trackKind = "ORIGINAL",
                        locale = null,
                        described = true,
                    ),
                ),
            ).inOrder()
    }
}
