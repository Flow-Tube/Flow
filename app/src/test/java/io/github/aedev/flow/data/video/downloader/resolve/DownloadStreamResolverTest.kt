package io.github.aedev.flow.data.video.downloader.resolve

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.MusicAudioQuality
import io.github.aedev.flow.data.video.downloader.request.DownloadRequest
import io.github.aedev.flow.data.video.downloader.tags.DownloadKind
import io.github.aedev.flow.data.video.downloader.tags.DownloadTags
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format.AudioTrack
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor.VideoExtractionResult
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class DownloadStreamResolverTest {
    private val defaults = DownloadStreamResolver.SelectionDefaults(height = 720, codec = null, language = null)

    private fun request(
        kind: DownloadKind = DownloadKind.VIDEO,
        audioOnly: Boolean = false,
        targetHeight: Int? = null,
        videoCodec: String? = null,
        videoItag: Int? = null,
        audioItag: Int? = null,
        audioTrackId: String? = null,
    ) = DownloadRequest(
        tags = DownloadTags(kind = kind, videoId = "v", title = "t"),
        audioOnly = audioOnly,
        targetHeight = targetHeight,
        videoCodec = videoCodec,
        videoItag = videoItag,
        audioItag = audioItag,
        audioTrackId = audioTrackId,
    )

    private fun result(
        video: List<Format>,
        audio: List<Format>,
    ): VideoExtractionResult {
        val result = mockk<VideoExtractionResult>(relaxed = true)
        every { result.videoFormats } returns video
        every { result.audioFormats } returns audio
        return result
    }

    private fun select(
        request: DownloadRequest,
        video: List<Format> = LADDER,
        audio: List<Format> = listOf(AAC, OPUS),
        avoid: Set<Int> = emptySet(),
    ) = DownloadStreamResolver.select(request, result(video, audio), avoid, defaults)

    private fun streams(outcome: ResolveOutcome) = (outcome as ResolveOutcome.Resolved).streams

    @Test
    fun `a song follows the song download quality, a video always takes the best aac`() {
        val low = format(139, "audio/mp4; codecs=\"mp4a.40.5\"", bitrate = 48_000)
        val high = format(141, "audio/mp4; codecs=\"mp4a.40.2\"", bitrate = 256_000)
        val audio = listOf(low, AAC, high)

        fun pick(
            request: DownloadRequest,
            quality: MusicAudioQuality,
        ) = streams(
            DownloadStreamResolver.select(request, result(LADDER, audio), emptySet(), defaults.copy(musicQuality = quality)),
        ).audio.itag
        val song = request(kind = DownloadKind.MUSIC, audioOnly = true)

        assertThat(pick(song, MusicAudioQuality.HIGH)).isEqualTo(141)
        assertThat(pick(song, MusicAudioQuality.MEDIUM)).isEqualTo(140)
        assertThat(pick(song, MusicAudioQuality.LOW)).isEqualTo(139)
        assertThat(pick(request(), MusicAudioQuality.LOW)).isEqualTo(141)
    }

    @Test
    fun `the default quality and aac are taken when nothing was picked`() {
        val picked = streams(select(request()))

        assertThat(picked.video?.itag).isEqualTo(247)
        assertThat(picked.audio.itag).isEqualTo(140)
        assertThat(picked.durationMs).isEqualTo(60_000)
    }

    @Test
    fun `a dialog's exact streams are kept while the extraction still offers them`() {
        val picked = streams(select(request(videoItag = 399, audioItag = 140)))

        assertThat(picked.video?.itag).isEqualTo(399)
    }

    @Test
    fun `a stream refused twice is swapped for another codec at the same height`() {
        val picked = streams(select(request(videoItag = 399, targetHeight = 1080, videoCodec = "av1"), avoid = setOf(399)))

        assertThat(picked.video?.itag).isAnyOf(137, 248)
        assertThat(picked.video?.itag).isNotEqualTo(399)
    }

    @Test
    fun `a picked dub keeps its track although every dub shares the itag`() {
        val dubs = listOf(dub("ar.3", "Arabic", 129_483), dub("en.4", "English original", 129_477), dub("es.3", "Spanish", 129_480))

        assertThat(streams(select(request(audioItag = 140, audioTrackId = "en.4"), audio = dubs)).audio.audioTrack?.id).isEqualTo("en.4")
        assertThat(streams(select(request(audioItag = 140, audioTrackId = "es.3"), audio = dubs)).audio.audioTrack?.id).isEqualTo("es.3")
    }

    @Test
    fun `a track that is gone falls back to the original, not to the first dub`() {
        val dubs = listOf(dub("ar.3", "Arabic", 129_483), dub("en.4", "English original", 129_477))

        assertThat(streams(select(request(audioItag = 140, audioTrackId = "fr.3"), audio = dubs)).audio.audioTrack?.id).isEqualTo("en.4")
    }

    @Test
    fun `songs and audio-only downloads fetch no video`() {
        assertThat(streams(select(request(kind = DownloadKind.MUSIC))).video).isNull()
        assertThat(streams(select(request(audioOnly = true))).video).isNull()
    }

    @Test
    fun `an opus pick is replaced by aac, since every download is an mp4`() {
        assertThat(streams(select(request(audioOnly = true, audioItag = 251))).audio.itag).isEqualTo(140)
    }

    @Test
    fun `only opus means no compatible audio, and nothing at all means unavailable`() {
        assertThat(select(request(), audio = listOf(OPUS))).isEqualTo(ResolveOutcome.NoCompatibleAudio)
        assertThat(select(request(), audio = emptyList())).isEqualTo(ResolveOutcome.Unavailable)
        assertThat(select(request(), video = emptyList())).isEqualTo(ResolveOutcome.Unavailable)
    }

    private companion object {
        fun format(
            itag: Int,
            mimeType: String,
            height: Int? = null,
            bitrate: Int = 1_000,
        ) = Format(
            itag = itag,
            url = "https://x.googlevideo.com/videoplayback?itag=$itag",
            mimeType = mimeType,
            bitrate = bitrate,
            width = height?.let { it * 16 / 9 },
            height = height,
            contentLength = 1_000L,
            quality = "medium",
            fps = height?.let { 30 },
            qualityLabel = height?.let { "${it}p" },
            averageBitrate = bitrate,
            audioQuality = null,
            approxDurationMs = "60000",
            audioSampleRate = null,
            audioChannels = null,
            loudnessDb = null,
            lastModified = null,
            signatureCipher = null,
        )

        fun dub(
            id: String,
            name: String,
            bitrate: Int,
        ) = format(140, "audio/mp4; codecs=\"mp4a.40.2\"", bitrate = bitrate).copy(audioTrack = AudioTrack(displayName = name, id = id))

        val AAC = format(140, "audio/mp4; codecs=\"mp4a.40.2\"", bitrate = 128_000)
        val OPUS = format(251, "audio/webm; codecs=\"opus\"", bitrate = 160_000)
        val LADDER =
            listOf(
                format(137, "video/mp4; codecs=\"avc1.640028\"", 1080),
                format(248, "video/webm; codecs=\"vp9\"", 1080),
                format(399, "video/mp4; codecs=\"av01.0.08M.08\"", 1080),
                format(247, "video/webm; codecs=\"vp9\"", 720),
                format(136, "video/mp4; codecs=\"avc1.4d401f\"", 720),
            )
    }
}
