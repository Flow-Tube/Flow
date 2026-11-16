package io.github.aedev.flow.player.dlna

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.RepeatMode
import org.junit.Test

class CastQueuePolicyTest {
    @Test
    fun `the next song follows the repeat mode`() {
        assertThat(CastQueuePolicy.next(1, 3, RepeatMode.OFF)).isEqualTo(2)
        assertThat(CastQueuePolicy.next(2, 3, RepeatMode.OFF)).isNull()
        assertThat(CastQueuePolicy.next(2, 3, RepeatMode.ALL)).isEqualTo(0)
        assertThat(CastQueuePolicy.next(1, 3, RepeatMode.ONE)).isEqualTo(1)
        assertThat(CastQueuePolicy.next(0, 0, RepeatMode.ALL)).isNull()
    }

    @Test
    fun `a stop near the end finishes the song`() {
        val stopped = CastStatus("STOPPED", positionMs = 0, durationMs = null)

        assertThat(
            CastQueuePolicy.finishedSong(stopped, lastPositionMs = 197_000, durationMs = 200_000, expectedEndAtMs = null, nowMs = 0),
        ).isTrue()
        assertThat(
            CastQueuePolicy.finishedSong(stopped, lastPositionMs = 0, durationMs = 200_000, expectedEndAtMs = 50_000, nowMs = 51_000),
        ).isTrue()
    }

    @Test
    fun `a stop from the renderer's remote mid song is not the end`() {
        val stopped = CastStatus("STOPPED", positionMs = 0, durationMs = 200_000)
        val playing = CastStatus("PLAYING", positionMs = 199_000, durationMs = 200_000)

        assertThat(
            CastQueuePolicy.finishedSong(stopped, lastPositionMs = 60_000, durationMs = 200_000, expectedEndAtMs = 200_000, nowMs = 60_000),
        ).isFalse()
        assertThat(
            CastQueuePolicy.finishedSong(playing, lastPositionMs = 199_000, durationMs = 200_000, expectedEndAtMs = 0, nowMs = 1),
        ).isFalse()
    }

    @Test
    fun `the renderer is asked each second only while watched`() {
        assertThat(CastQueuePolicy.nextCheckMs(playing = true, positionMs = 0, durationMs = 200_000, watched = true)).isEqualTo(1_000)
        assertThat(
            CastQueuePolicy.nextCheckMs(playing = true, positionMs = 50_000, durationMs = 200_000, watched = false),
        ).isEqualTo(151_500)
        assertThat(
            CastQueuePolicy.nextCheckMs(playing = true, positionMs = 199_900, durationMs = 200_000, watched = false),
        ).isEqualTo(2_000)
        assertThat(CastQueuePolicy.nextCheckMs(playing = false, positionMs = 0, durationMs = 200_000, watched = false)).isNull()
    }

    @Test
    fun `the cast format is AAC in the player's audio track`() {
        val opus = format(251, "audio/webm; codecs=\"opus\"", 160_000)
        val aacLow = format(139, "audio/mp4; codecs=\"mp4a.40.5\"", 48_000)
        val aac = format(140, "audio/mp4; codecs=\"mp4a.40.2\"", 130_000)
        val dubbed = format(140, "audio/mp4; codecs=\"mp4a.40.2\"", 131_000, track = "es.1")
        val original = format(251, "audio/webm; codecs=\"opus\"", 160_000, track = "en.4")

        assertThat(CastQueuePolicy.castFormat(listOf(opus, aacLow, aac), opus)).isEqualTo(aac)
        assertThat(
            CastQueuePolicy.castFormat(listOf(aac.copy(audioTrack = track("en.4")), dubbed), original)?.audioTrack?.id,
        ).isEqualTo("en.4")
        assertThat(CastQueuePolicy.castFormat(listOf(opus), opus)).isNull()
    }

    private fun track(id: String) = PlayerResponse.StreamingData.Format.AudioTrack(displayName = id, id = id)

    private fun format(
        itag: Int,
        mime: String,
        bitrate: Int,
        track: String? = null,
    ) = PlayerResponse.StreamingData.Format(
        itag = itag,
        url = "https://rr1---sn.googlevideo.com/videoplayback?itag=$itag",
        mimeType = mime,
        bitrate = bitrate,
        width = null,
        height = null,
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
        audioTrack = track?.let(::track),
    )
}
