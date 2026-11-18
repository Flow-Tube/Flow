package io.github.aedev.flow.player.sabr.integration

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.ResponseContext
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import io.github.aedev.flow.player.stream.PlaybackTestStreams.av1Format
import io.github.aedev.flow.player.stream.PlaybackTestStreams.h264Format
import io.github.aedev.flow.player.stream.PlaybackTestStreams.opusFormat
import io.github.aedev.flow.player.stream.PlaybackTestStreams.videoFormat
import io.github.aedev.flow.player.stream.PlaybackTestStreams.vp9Format
import org.junit.Test

/** The video format a SABR session starts on, and the one a quality pick moves it to. */
class SabrVideoSelectionTest {
    private fun response(formats: List<Format>) =
        PlayerResponse(
            responseContext = ResponseContext(visitorData = "visitor", serviceTrackingParams = null),
            playabilityStatus = PlayerResponse.PlayabilityStatus(status = "OK", reason = null),
            playerConfig =
                PlayerResponse.PlayerConfig(
                    mediaCommonConfig =
                        PlayerResponse.PlayerConfig.MediaCommonConfig(
                            PlayerResponse.PlayerConfig.MediaCommonConfig.MediaUstreamerRequestConfig("CgQIARAB"),
                        ),
                ),
            streamingData =
                PlayerResponse.StreamingData(
                    adaptiveFormats = formats + opusFormat(),
                    serverAbrStreamingUrl = "https://sabr.invalid/videoplayback",
                ),
            videoDetails = null,
            playbackTracking = null,
        )

    private val ladder =
        listOf(h264Format(720, 2_500_000), vp9Format(1080, 3_800_000), av1Format(1080, 3_000_000), h264Format(1080, 4_500_000))

    private fun start(
        formats: List<Format>,
        codec: String? = null,
    ) = SabrUrlResolver.resolve(response(formats), codec, injectedPoToken = "pot", injectedVisitorData = "visitor")!!

    private fun pick(
        formats: List<Format>,
        height: Int,
        codec: String? = null,
    ) = SabrUrlResolver.resolveForQuality(response(formats), height, codec, injectedPoToken = "pot", injectedVisitorData = "visitor")

    @Test
    fun `a session starts at the tallest height in the most compatible codec`() {
        val info = start(ladder)

        assertThat(info.videoItag).isEqualTo(137)
        assertThat(info.videoHeight).isEqualTo(1080)
        assertThat(info.audioItag).isEqualTo(251)
        assertThat(info.durationMs).isEqualTo(212_000L)
    }

    @Test
    fun `a preferred codec wins at the tallest height`() {
        assertThat(start(ladder, "av1").videoItag).isEqualTo(399)
        assertThat(start(ladder, "vp9").videoItag).isEqualTo(248)
    }

    @Test
    fun `a quality pick takes that height in the preferred codec, then its fallback`() {
        assertThat(pick(ladder, 1080, "vp9")!!.videoItag).isEqualTo(248)
        assertThat(pick(ladder, 720, "vp9,av1")!!.videoItag).isEqualTo(136)
        assertThat(pick(ladder, 1080, "av1,vp9")!!.videoItag).isEqualTo(399)
    }

    @Test
    fun `without a preference a quality pick follows the itag table at that height`() {
        assertThat(pick(ladder, 1080)!!.videoItag).isEqualTo(248)
    }

    @Test
    fun `a height nobody serves lands on the nearest one`() {
        assertThat(pick(ladder, 800)!!.videoHeight).isEqualTo(720)
        val odd = listOf(videoFormat(9001, 1440, "video/mp4; codecs=\"avc1.640033\"", 8_000_000))
        assertThat(pick(odd, 1440)!!.videoItag).isEqualTo(9001)
    }

    @Test
    fun `no video formats means no session`() {
        assertThat(SabrUrlResolver.resolve(response(emptyList()), injectedPoToken = "pot", injectedVisitorData = "visitor")).isNull()
        assertThat(pick(emptyList(), 720)).isNull()
    }
}
