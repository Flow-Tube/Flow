package io.github.aedev.flow.player.sabr.integration

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import org.junit.Test

class SabrAudioTrackPickTest {
    private fun format(
        itag: Int,
        mimeType: String,
        trackId: String? = null,
        width: Int? = null,
        height: Int? = null,
    ) = PlayerResponse.StreamingData.Format(
        itag = itag,
        url = null,
        mimeType = mimeType,
        bitrate = 131_000,
        width = width,
        height = height,
        contentLength = null,
        quality = "tiny",
        fps = null,
        qualityLabel = null,
        averageBitrate = null,
        audioQuality = null,
        approxDurationMs = null,
        audioSampleRate = null,
        audioChannels = null,
        loudnessDb = null,
        lastModified = null,
        signatureCipher = null,
        audioTrack = trackId?.let { PlayerResponse.StreamingData.Format.AudioTrack(displayName = it, id = it) },
    )

    private val video = format(137, "video/mp4; codecs=\"avc1.640028\"", width = 1920, height = 1080)

    // Every dub on B599b52q2oo shares itag 140, with Arabic listed first.
    private val audio =
        listOf(
            format(140, "audio/mp4; codecs=\"mp4a.40.2\"", "ar.10"),
            format(140, "audio/mp4; codecs=\"mp4a.40.2\"", "ja.10"),
            format(140, "audio/mp4; codecs=\"mp4a.40.2\"", "en-US.4"),
        )

    @Test
    fun `the chosen dub is kept`() {
        assertThat(SabrUrlResolver.selectBestAudio(audio, video, "ja.10")?.audioTrack?.id).isEqualTo("ja.10")
    }

    @Test
    fun `no choice takes the original`() {
        assertThat(SabrUrlResolver.selectBestAudio(audio, video)?.audioTrack?.id).isEqualTo("en-US.4")
    }

    @Test
    fun `a choice the response no longer offers falls back to the original`() {
        assertThat(SabrUrlResolver.selectBestAudio(audio, video, "fr.10")?.audioTrack?.id).isEqualTo("en-US.4")
    }
}
