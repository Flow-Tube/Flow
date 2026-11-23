package io.github.aedev.flow.player.live

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import kotlinx.serialization.json.Json
import org.junit.Test

/** Shapes follow VISIONOS's live answer for a 24/7 news stream on 23 Nov, written by hand. */
class LiveWindowTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    private fun details(
        dvr: Boolean,
        latency: String,
    ) = """{"videoId": "v", "channelId": "c", "isLive": true, "isLiveDvrEnabled": $dvr,
        |"latencyClass": "MDE_STREAM_OPTIMIZATIONS_RENDERER_LATENCY_$latency"}
        """.trimMargin()

    private fun response(
        videoDetails: String = details(dvr = true, latency = "NORMAL"),
        playerConfig: String = """{"livePlayerConfig": {"liveReadaheadSeconds": 12, "isLiveHeadPlayable": true}}""",
        formats: String =
            """
            {"itag": 135, "url": "https://r1.googlevideo.com/videoplayback?itag=135", "mimeType": "video/mp4; codecs=\"avc1.4D401F\"", "bitrate": 1350025, "width": 854, "height": 480, "quality": "large", "targetDurationSec": 5, "maxDvrDurationSec": 43200},
            {"itag": 140, "url": "https://r1.googlevideo.com/videoplayback?itag=140", "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 144000, "quality": "tiny", "targetDurationSec": 5, "maxDvrDurationSec": 43200}
            """,
    ) = json.decodeFromString<PlayerResponse>(
        """
        {
          "responseContext": {},
          "playabilityStatus": {"status": "OK"},
          "playerConfig": $playerConfig,
          "videoDetails": $videoDetails,
          "streamingData": {"adaptiveFormats": [$formats]}
        }
        """,
    )

    @Test
    fun `a live response gives its segment length, DVR depth and readahead`() {
        assertThat(LiveWindow.of(response()))
            .isEqualTo(
                LiveWindow(
                    segmentDurationMs = 5_000,
                    maxDvrDurationMs = 43_200_000,
                    readaheadMs = 12_000,
                    isDvrEnabled = true,
                    isLowLatency = false,
                ),
            )
    }

    @Test
    fun `a stream without DVR and a low latency class says so`() {
        val window =
            LiveWindow.of(
                response(
                    videoDetails = details(dvr = false, latency = "LOW"),
                ),
            )!!
        assertThat(window.isDvrEnabled).isFalse()
        assertThat(window.isLowLatency).isTrue()
    }

    @Test
    fun `a fractional readahead decodes, and a missing one falls back to YouTube's default`() {
        assertThat(LiveWindow.of(response(playerConfig = """{"livePlayerConfig": {"liveReadaheadSeconds": 10.5}}"""))!!.readaheadMs)
            .isEqualTo(10_500)
        assertThat(LiveWindow.of(response(playerConfig = "{}"))!!.readaheadMs).isEqualTo(LiveWindow.DEFAULT_READAHEAD_MS)
    }

    @Test
    fun `a VOD response has no live window`() {
        val vod =
            """{"itag": 137, "url": "https://r1.googlevideo.com/videoplayback?itag=137", "mimeType": "video/mp4; codecs=\"avc1.640028\"", "bitrate": 4000000, "width": 1920, "height": 1080, "quality": "hd1080"}"""
        assertThat(LiveWindow.of(response(formats = vod))).isNull()
    }
}
