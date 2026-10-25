package io.github.aedev.flow.data.video

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import org.junit.Test
import java.util.Locale

/** Pins the download policy against InnerTube formats as the `/player` response carries them. */
class DownloadStreamPolicyTest {
    private fun audio(
        itag: Int,
        mimeType: String = M4A,
        bitrate: Int = 0,
        averageBitrate: Int? = null,
        trackId: String? = null,
        trackName: String? = null,
        isAutoDubbed: Boolean? = null,
        isDrc: Boolean? = null,
        url: String? = "https://example.invalid/$itag",
    ) = format(
        itag = itag,
        mimeType = mimeType,
        bitrate = bitrate,
        averageBitrate = averageBitrate,
        url = url,
        audioTrack =
            if (trackId != null || trackName != null || isAutoDubbed != null) {
                Format.AudioTrack(displayName = trackName, id = trackId, isAutoDubbed = isAutoDubbed)
            } else {
                null
            },
        isDrc = isDrc,
    )

    private fun video(
        itag: Int,
        mimeType: String,
        height: Int,
        width: Int = height * 16 / 9,
        qualityLabel: String? = "${height}p",
        fps: Int? = 30,
        hdr: Boolean = false,
        url: String? = "https://example.invalid/$itag",
    ) = format(
        itag = itag,
        mimeType = mimeType,
        bitrate = 1_000_000,
        width = width,
        height = height,
        qualityLabel = qualityLabel,
        fps = fps,
        url = url,
        colorInfo = if (hdr) Format.ColorInfo(transferCharacteristics = "COLOR_TRANSFER_CHARACTERISTICS_SMPTEST2084") else null,
    )

    private fun format(
        itag: Int,
        mimeType: String,
        bitrate: Int,
        url: String?,
        averageBitrate: Int? = null,
        width: Int? = null,
        height: Int? = null,
        qualityLabel: String? = null,
        fps: Int? = null,
        audioTrack: Format.AudioTrack? = null,
        isDrc: Boolean? = null,
        colorInfo: Format.ColorInfo? = null,
    ) = Format(
        itag = itag,
        url = url,
        mimeType = mimeType,
        bitrate = bitrate,
        width = width,
        height = height,
        contentLength = null,
        quality = "medium",
        fps = fps,
        qualityLabel = qualityLabel,
        averageBitrate = averageBitrate,
        audioQuality = null,
        approxDurationMs = null,
        audioSampleRate = null,
        audioChannels = null,
        loudnessDb = null,
        lastModified = null,
        signatureCipher = null,
        audioTrack = audioTrack,
        isDrc = isDrc,
        colorInfo = colorInfo,
    )

    private fun kbps(format: Format) = DownloadStreamPolicy.audioBitrateKbps(format)

    private fun pick(
        audio: List<Format>,
        preferredLang: String? = null,
    ) = DownloadStreamPolicy.pickAacAudio(audio, preferredLang)

    private fun ladder(vararg formats: Format) = DownloadStreamPolicy.buildDownloadVideoFormats(formats.toList())

    @Test
    fun `bitrate is reported in kbps from the average bitrate first`() {
        assertThat(kbps(audio(140, averageBitrate = 128_000, bitrate = 130_000))).isEqualTo(128)
        assertThat(kbps(audio(251, bitrate = 160_000))).isEqualTo(160)
        assertThat(kbps(audio(140))).isEqualTo(0)
    }

    @Test
    fun `values at or below 1000 are taken as kbps already`() {
        // Pins current behaviour: the unit is guessed from the magnitude, so 1000 stays 1000 while
        // 1001 collapses to 1.
        assertThat(kbps(audio(140, averageBitrate = 128))).isEqualTo(128)
        assertThat(kbps(audio(140, averageBitrate = 1_000))).isEqualTo(1_000)
        assertThat(kbps(audio(140, averageBitrate = 1_001))).isEqualTo(1)
    }

    @Test
    fun `format labels follow the container and codec`() {
        assertThat(DownloadStreamPolicy.audioFormatLabel(audio(251, mimeType = OPUS))).isEqualTo("OPUS")
        assertThat(DownloadStreamPolicy.audioFormatLabel(audio(171, mimeType = VORBIS))).isEqualTo("WEBM")
        assertThat(DownloadStreamPolicy.audioFormatLabel(audio(140))).isEqualTo("M4A")
        assertThat(DownloadStreamPolicy.audioFormatLabel(audio(0, mimeType = "audio/mpeg"), unknownLabel = "?")).isEqualTo("?")
    }

    @Test
    fun `language label prefers the track name then the language then the track id`() {
        assertThat(DownloadStreamPolicy.audioLanguageLabel(audio(140, trackName = "English (US)", trackId = "fr.3")))
            .isEqualTo("English (US)")
        assertThat(DownloadStreamPolicy.audioLanguageLabel(audio(140, trackId = "fr.3"))).isEqualTo(Locale.FRENCH.displayLanguage)
        assertThat(DownloadStreamPolicy.audioLanguageLabel(audio(140, trackName = "   ", trackId = "x"))).isEqualTo("x")
        assertThat(DownloadStreamPolicy.audioLanguageLabel(audio(140))).isNull()
    }

    @Test
    fun `track type labels map original and dubbed to the given strings`() {
        fun label(format: Format) = DownloadStreamPolicy.audioTrackTypeLabel(format, originalLabel = "Original", dubbedLabel = "Dubbed")

        assertThat(label(audio(140))).isEqualTo("Original")
        assertThat(label(audio(140, trackId = "en.4"))).isEqualTo("Original")
        assertThat(label(audio(140, trackId = "fr.3", isAutoDubbed = true))).isEqualTo("Dubbed")
    }

    @Test
    fun `the audio list keeps only aac with a url, since every download is an mp4 or m4a`() {
        val kept = audio(141, averageBitrate = 256_000)

        assertThat(
            DownloadStreamPolicy.buildDownloadAudioFormats(
                listOf(
                    audio(140, url = null),
                    audio(139, url = ""),
                    audio(0, mimeType = "audio/mpeg"),
                    audio(251, mimeType = OPUS, averageBitrate = 160_000),
                    kept,
                ),
            ),
        ).containsExactly(kept)
    }

    @Test
    fun `the audio list keeps the first of two formats with the same label bitrate track and language`() {
        val first = audio(140, averageBitrate = 128_000, trackId = "en.4")
        val twin = audio(140, averageBitrate = 128_400, trackId = "en.4", url = "https://example.invalid/other")

        assertThat(DownloadStreamPolicy.buildDownloadAudioFormats(listOf(first, twin))).containsExactly(first)
    }

    @Test
    fun `the audio list keeps dubs apart even at the same bitrate`() {
        val en = audio(140, averageBitrate = 128_000, trackId = "en.4")
        val fr = audio(140, averageBitrate = 128_000, trackId = "fr.3", isAutoDubbed = true)

        assertThat(DownloadStreamPolicy.buildDownloadAudioFormats(listOf(en, fr))).containsExactly(en, fr).inOrder()
    }

    @Test
    fun `the audio list drops a DRC format whose normal twin is present`() {
        val normal = audio(140, averageBitrate = 130_000)
        val drc = audio(140, averageBitrate = 130_010, isDrc = true)

        assertThat(DownloadStreamPolicy.buildDownloadAudioFormats(listOf(drc, normal))).containsExactly(normal)
    }

    @Test
    fun `the audio list puts higher bitrates first`() {
        val low = audio(139, averageBitrate = 48_000)
        val high = audio(141, averageBitrate = 256_000)
        val mid = audio(140, averageBitrate = 128_000)

        assertThat(DownloadStreamPolicy.buildDownloadAudioFormats(listOf(low, high, mid)))
            .containsExactly(high, mid, low)
            .inOrder()
    }

    @Test
    fun `the audio list opens on the original track, then the dubs by language`() {
        val ar = audio(140, averageBitrate = 129_483, trackId = "ar.3", trackName = "Arabic")
        val enLow = audio(139, averageBitrate = 48_000, trackId = "en.4", trackName = "English original")
        val en = audio(140, averageBitrate = 129_477, trackId = "en.4", trackName = "English original")
        val fr = audio(140, averageBitrate = 129_479, trackId = "fr.3", trackName = "French")

        assertThat(DownloadStreamPolicy.buildDownloadAudioFormats(listOf(fr, ar, enLow, en)))
            .containsExactly(en, enLow, ar, fr)
            .inOrder()
    }

    @Test
    fun `two tracks in one language keep their own rows together`() {
        val hans = audio(140, averageBitrate = 129_000, trackId = "zh-Hans.3", trackName = "Chinese (Simplified)")
        val hansLow = audio(139, averageBitrate = 48_000, trackId = "zh-Hans.3", trackName = "Chinese (Simplified)")
        val hant = audio(140, averageBitrate = 129_000, trackId = "zh-Hant.3", trackName = "Chinese (Traditional)")
        val hantLow = audio(139, averageBitrate = 48_000, trackId = "zh-Hant.3", trackName = "Chinese (Traditional)")

        assertThat(DownloadStreamPolicy.buildDownloadAudioFormats(listOf(hant, hansLow, hantLow, hans)))
            .containsExactly(hans, hansLow, hant, hantLow)
            .inOrder()
    }

    @Test
    fun `a video download offers each track once, at its best aac, original first`() {
        val en = audio(140, averageBitrate = 129_477, trackId = "en.4", trackName = "English original")
        val enLow = audio(139, averageBitrate = 48_000, trackId = "en.4", trackName = "English original")
        val ar = audio(140, averageBitrate = 129_483, trackId = "ar.3", trackName = "Arabic")
        val arLow = audio(139, averageBitrate = 48_000, trackId = "ar.3", trackName = "Arabic")
        val opus = audio(251, mimeType = OPUS, bitrate = 160_000, trackId = "ar.3", trackName = "Arabic")

        val choices =
            DownloadStreamPolicy.audioTrackChoices(
                DownloadStreamPolicy.buildDownloadAudioFormats(listOf(arLow, opus, ar, enLow, en)),
            )

        assertThat(choices).containsExactly(en, ar).inOrder()
        assertThat(DownloadStreamPolicy.audioTrackChoices(listOf(audio(140, bitrate = 128_000), audio(139, bitrate = 48_000)))).hasSize(1)
    }

    @Test
    fun `an audio-only download defaults to the original when every dub shares its bitrate`() {
        val streams =
            DownloadStreamPolicy.buildDownloadAudioFormats(
                listOf(
                    audio(140, averageBitrate = 129_483, trackId = "ar.3", trackName = "Arabic"),
                    audio(140, averageBitrate = 129_477, trackId = "en.4", trackName = "English original"),
                ),
            )

        val index = DownloadStreamPolicy.defaultAudioIndex(streams, preferredLang = "", rememberedLabel = null) { "" }

        assertThat(streams[index].audioTrack?.id).isEqualTo("en.4")
    }

    @Test
    fun `a remembered dub does not become the default for the next video`() {
        val ar = audio(140, averageBitrate = 129_000, trackId = "ar.3", trackName = "Arabic")
        val en = audio(140, averageBitrate = 129_000, trackId = "en.4", trackName = "English original")
        val enLow = audio(139, averageBitrate = 48_000, trackId = "en.4", trackName = "English original")
        val streams = listOf(en, enLow, ar)
        val labels = mapOf(en to "M4A 129 en", enLow to "M4A 48 en", ar to "M4A 129 ar")

        assertThat(DownloadStreamPolicy.defaultAudioIndex(streams, "", "M4A 129 ar") { labels.getValue(it) }).isEqualTo(0)
        assertThat(DownloadStreamPolicy.defaultAudioIndex(streams, "", "M4A 48 en") { labels.getValue(it) }).isEqualTo(1)
        assertThat(DownloadStreamPolicy.defaultAudioIndex(streams, "ar", "M4A 129 ar") { labels.getValue(it) }).isEqualTo(2)
    }

    @Test
    fun `aac is taken even when opus has the higher bitrate`() {
        val opus = audio(251, mimeType = OPUS, bitrate = 160_000)
        val aac = audio(140, bitrate = 128_000)

        assertThat(pick(listOf(opus, aac))).isSameInstanceAs(aac)
    }

    @Test
    fun `opus alone is never paired`() {
        assertThat(pick(listOf(audio(251, mimeType = OPUS, bitrate = 160_000)))).isNull()
    }

    @Test
    fun `an empty list yields nothing`() {
        assertThat(pick(emptyList())).isNull()
    }

    @Test
    fun `the pick ranks by the average bitrate and falls back to the bitrate`() {
        val low = audio(139, averageBitrate = 48_000, bitrate = 300_000)
        val high = audio(141, bitrate = 256_000)

        assertThat(pick(listOf(low, high))).isSameInstanceAs(high)
    }

    @Test
    fun `a preferred language wins over the original track`() {
        val en = audio(140, bitrate = 128_000, trackId = "en.4")
        val fr = audio(140, bitrate = 128_000, trackId = "fr.3", isAutoDubbed = true)

        assertThat(pick(listOf(en, fr), preferredLang = "fr")).isSameInstanceAs(fr)
        assertThat(pick(listOf(en, fr), preferredLang = "FR")).isSameInstanceAs(fr)
    }

    @Test
    fun `a regional preference matches only a regional track`() {
        // "fr-FR" is compared against the track's language ("fr") and its full tag, so a bare "fr"
        // track does not match it and the pick falls through to every track.
        val en = audio(140, bitrate = 256_000, trackId = "en.4")
        val fr = audio(140, bitrate = 128_000, trackId = "fr.3", isAutoDubbed = true)
        val frFr = audio(140, bitrate = 64_000, trackId = "fr-FR.3", isAutoDubbed = true)

        assertThat(pick(listOf(en, fr), preferredLang = "fr-FR")).isSameInstanceAs(en)
        assertThat(pick(listOf(en, fr, frFr), preferredLang = "fr-FR")).isSameInstanceAs(frFr)
    }

    @Test
    fun `a preferred language with no match ignores the original flag`() {
        val en = audio(140, bitrate = 128_000, trackId = "en.4")
        val frLoud = audio(141, bitrate = 256_000, trackId = "fr.3", isAutoDubbed = true)

        assertThat(pick(listOf(en, frLoud), preferredLang = "de")).isSameInstanceAs(frLoud)
    }

    @Test
    fun `without a preference the original track wins`() {
        val original = audio(140, bitrate = 128_000, trackId = "en.4")
        val dubbed = audio(141, bitrate = 256_000, trackId = "fr.3", isAutoDubbed = true)

        listOf(null, "", "original").forEach { preference ->
            assertThat(pick(listOf(dubbed, original), preference)).isSameInstanceAs(original)
        }
        assertThat(pick(listOf(dubbed))).isSameInstanceAs(dubbed)
    }

    @Test
    fun `a language with only opus crosses to another language's aac`() {
        val frOpus = audio(251, mimeType = OPUS, bitrate = 160_000, trackId = "fr.3", isAutoDubbed = true)
        val enAac = audio(140, bitrate = 128_000, trackId = "en.4")

        assertThat(pick(listOf(frOpus, enAac), preferredLang = "fr")).isSameInstanceAs(enAac)
    }

    @Test
    fun `the ladder is one row per resolution and codec, tallest first and vp9 ahead of h264 ahead of av1`() {
        val vp91080 = video(248, VP9, 1080)
        val h2641080 = video(137, H264, 1080)
        val av11080 = video(399, AV1, 1080)
        val vp9720 = video(247, VP9, 720)

        assertThat(ladder(av11080, h2641080, vp9720, vp91080))
            .containsExactly(vp91080, h2641080, av11080, vp9720)
            .inOrder()
    }

    @Test
    fun `a format with no url never reaches the ladder`() {
        val playable = video(137, H264, 720)

        assertThat(ladder(video(248, VP9, 1080, url = null), video(247, VP9, 1080, url = ""), playable))
            .containsExactly(playable)
    }

    @Test
    fun `the first format wins when two carry the same resolution codec frame rate and range`() {
        val first = video(137, H264, 1080)
        val second = video(137, H264, 1080, url = "https://example.invalid/other")

        assertThat(ladder(first, second)).containsExactly(first)
    }

    @Test
    fun `hdr and frame rate variants keep their own rows, sdr and the faster one first`() {
        val hdr = video(337, VP9, 2160, qualityLabel = "2160p60 HDR", fps = 60, hdr = true)
        val sdr60 = video(315, VP9, 2160, qualityLabel = "2160p60", fps = 60)
        val sdr30 = video(313, VP9, 2160, qualityLabel = "2160p", fps = 30)

        assertThat(ladder(hdr, sdr30, sdr60)).containsExactly(sdr60, sdr30, hdr).inOrder()
        assertThat(DownloadStreamPolicy.videoQualityLabel(hdr, hdrLabel = "HDR")).isEqualTo("VP9 2160p60 HDR")
        assertThat(DownloadStreamPolicy.videoQualityLabel(sdr30, hdrLabel = "HDR")).isEqualTo("VP9 2160p")
    }

    @Test
    fun `an av1 short is keyed by its quality label and its mime codec`() {
        // 787/788 are the 608x1080 Shorts tier: portrait, so height is the long side, and an itag
        // table that does not know them used to file them under h264.
        val short = video(788, AV1, height = 1920, width = 1080, qualityLabel = "1080p")

        assertThat(DownloadStreamPolicy.videoHeight(short)).isEqualTo(1080)
        assertThat(DownloadStreamPolicy.videoCodecKey(short)).isEqualTo("av1")
        assertThat(DownloadStreamPolicy.videoQualityLabel(short, hdrLabel = "HDR")).isEqualTo("AV1 1080p")
    }

    private companion object {
        const val M4A = "audio/mp4; codecs=\"mp4a.40.2\""
        const val OPUS = "audio/webm; codecs=\"opus\""
        const val VORBIS = "audio/webm; codecs=\"vorbis\""
        const val H264 = "video/mp4; codecs=\"avc1.640028\""
        const val VP9 = "video/webm; codecs=\"vp9\""
        const val AV1 = "video/mp4; codecs=\"av01.0.08M.08\""
    }
}
