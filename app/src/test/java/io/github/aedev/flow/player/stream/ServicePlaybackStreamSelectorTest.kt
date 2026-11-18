package io.github.aedev.flow.player.stream

import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.player.stream.model.AudioStream
import io.github.aedev.flow.player.stream.model.AudioTrackType
import io.github.aedev.flow.player.stream.model.MediaFormat
import io.github.aedev.flow.player.stream.model.VideoStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

private fun audio(
    id: String,
    languageTag: String? = null,
    trackName: String? = null,
    trackType: AudioTrackType = AudioTrackType.DUBBED,
    bitrate: Int = 128_000,
): AudioStream =
    AudioStream(
        id = id,
        content = "https://example.invalid/$id",
        format = MediaFormat.M4A,
        itagItem = null,
        averageBitrate = bitrate,
        audioTrackName = trackName,
        audioLocale = languageTag?.let { Locale.forLanguageTag(it) },
        audioTrackType = trackType,
    )

private fun video(
    id: String,
    resolution: String,
    videoOnly: Boolean = true,
    format: MediaFormat = MediaFormat.MPEG_4,
): VideoStream =
    VideoStream(
        id = id,
        content = "https://example.invalid/$id",
        format = format,
        itagItem = null,
        resolution = resolution,
        isVideoOnly = videoOnly,
    )

class ServicePlaybackStreamSelectorTest {
    private fun select(
        audio: List<AudioStream> = emptyList(),
        videos: List<VideoStream> = emptyList(),
        quality: VideoQuality = VideoQuality.AUTO,
        language: String = "original",
        codec: String = "auto",
    ) = ServicePlaybackStreamSelector.selectStreams(
        videoCandidates = videos,
        audioCandidatesAll = audio,
        preferredQuality = quality,
        preferredAudioLanguage = language,
        preferredCodecKey = codec,
    )

    @Test
    fun `a region qualified preference matches its track instead of falling back to the original`() {
        // Regression guard for the copy this path used to carry: it compared only
        // audioLocale.language ("pt"), so "pt-BR" never matched and the original was chosen.
        val original = audio("orig", languageTag = "en", trackType = AudioTrackType.ORIGINAL)
        val brazilian = audio("ptbr", languageTag = "pt-BR")

        val (_, picked) = select(audio = listOf(original, brazilian), language = "pt-BR")

        assertEquals("ptbr", picked?.id)
    }

    @Test
    fun `a preference never matches a track whose name merely contains its code`() {
        val english =
            audio("en", languageTag = "en", trackName = "English original", trackType = AudioTrackType.ORIGINAL, bitrate = 128_000)
        val french = audio("fr", languageTag = "fr-FR", trackName = "French (FR)", bitrate = 130_000)
        val hindi = audio("hi", languageTag = "hi", trackName = "Hindi", bitrate = 128_000)
        val chinese = audio("zh", languageTag = "zh", trackName = "Chinese", bitrate = 130_000)
        val spanish = audio("es", languageTag = "es-US", trackName = "Spanish", bitrate = 128_000)
        val portuguese = audio("pt", languageTag = "pt-BR", trackName = "Portuguese", bitrate = 130_000)
        val tracks = listOf(english, french, hindi, chinese, spanish, portuguese)

        assertEquals("en", select(audio = tracks, language = "en").second?.id)
        assertEquals("hi", select(audio = tracks, language = "hi").second?.id)
        assertEquals("es", select(audio = tracks, language = "es").second?.id)
        assertEquals("pt", select(audio = tracks, language = "pt").second?.id)
    }

    @Test
    fun `a plain language preference still matches`() {
        val original = audio("orig", languageTag = "en", trackType = AudioTrackType.ORIGINAL)
        val spanish = audio("es", languageTag = "es")

        assertEquals("es", select(audio = listOf(original, spanish), language = "es").second?.id)
    }

    @Test
    fun `original is preferred when no language is requested`() {
        val dubbed = audio("dub", languageTag = "es")
        val original = audio("orig", languageTag = "en", trackType = AudioTrackType.ORIGINAL)

        assertEquals("orig", select(audio = listOf(dubbed, original), language = "original").second?.id)
        assertEquals("orig", select(audio = listOf(dubbed, original), language = "").second?.id)
    }

    @Test
    fun `an unavailable language falls back rather than returning nothing`() {
        val original = audio("orig", languageTag = "en", trackType = AudioTrackType.ORIGINAL)
        val spanish = audio("es", languageTag = "es")

        val picked = select(audio = listOf(original, spanish), language = "ja").second

        assertNotNull(picked)
        assertEquals("orig", picked?.id)
    }

    @Test
    fun `the highest bitrate wins among tracks of the same language`() {
        val low = audio("low", languageTag = "en", trackType = AudioTrackType.ORIGINAL, bitrate = 64_000)
        val high = audio("high", languageTag = "en", trackType = AudioTrackType.ORIGINAL, bitrate = 256_000)

        assertEquals("high", select(audio = listOf(low, high), language = "original").second?.id)
    }

    @Test
    fun `duplicate urls do not change which track is chosen`() {
        val original = audio("orig", languageTag = "en", trackType = AudioTrackType.ORIGINAL)

        assertEquals("orig", select(audio = listOf(original, original), language = "original").second?.id)
    }

    @Test
    fun `AUTO quality leaves the video track to adaptive selection`() {
        val streams = listOf(video("v1", "1080p"), video("v2", "480p"))

        assertNull(select(videos = streams, audio = listOf(audio("a", trackType = AudioTrackType.ORIGINAL))).first)
    }

    @Test
    fun `an explicit quality picks the closest available height`() {
        val streams = listOf(video("v1080", "1080p"), video("v480", "480p"), video("v144", "144p"))

        val picked =
            select(
                videos = streams,
                audio = listOf(audio("a", trackType = AudioTrackType.ORIGINAL)),
                quality = VideoQuality.Q_480P,
            ).first

        assertEquals("v480", picked?.id)
    }

    @Test
    fun `with no audio at all a muxed stream is preferred so playback still has sound`() {
        val muxed = video("muxed", "360p", videoOnly = false)
        val videoOnly = video("vonly", "1080p", videoOnly = true)

        val (picked, audioPicked) = select(videos = listOf(videoOnly, muxed), audio = emptyList())

        assertNull(audioPicked)
        assertTrue("expected the muxed stream, got ${picked?.id}", picked?.id == "muxed")
    }

    @Test
    fun `an all dubbed track list still yields a track when the original is asked for`() {
        // The player screen used to carry its own copy of this selection, whose last resort was
        // "the first stream in the list"; nothing may return null while any track exists.
        val es = audio("es", languageTag = "es", bitrate = 96_000)
        val fr = audio("fr", languageTag = "fr", bitrate = 160_000)

        assertEquals("fr", select(audio = listOf(es, fr), language = "original").second?.id)
    }

    @Test
    fun `streams the player cannot mux are ignored whatever their height`() {
        val threeGp = video("3gp", "1080p", format = MediaFormat.v3GPP)
        val mp4 = video("mp4", "360p", format = MediaFormat.MPEG_4)

        val picked = select(videos = listOf(threeGp, mp4), quality = VideoQuality.Q_1080P).first

        assertEquals("mp4", picked?.id)
    }

    @Test
    fun `the codec preference breaks a tie between two streams of the same height`() {
        val avc = video("avc", "1080p", format = MediaFormat.MPEG_4)
        val webm = video("webm", "1080p", format = MediaFormat.WEBM)

        assertEquals("webm", select(videos = listOf(avc, webm), quality = VideoQuality.Q_1080P, codec = "vp9").first?.id)
        assertEquals("avc", select(videos = listOf(webm, avc), quality = VideoQuality.Q_1080P, codec = "avc").first?.id)
    }
}
