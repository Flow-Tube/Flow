package io.github.aedev.flow.player.live

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import kotlinx.serialization.json.Json
import org.junit.Test

/** The format mix VISIONOS lists for a 24/7 live stream, written by hand. */
class LiveSegmentFormatsTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private fun format(
        itag: Int,
        mime: String,
        extra: String = "",
        url: String = "https://r1.googlevideo.com/videoplayback?itag=$itag",
    ) = """{"itag": $itag, "url": "$url", "mimeType": "$mime", "bitrate": 1000, "quality": "q", "targetDurationSec": 5$extra}"""

    private val response =
        json.decodeFromString<PlayerResponse>(
            """
            {
              "responseContext": {},
              "playabilityStatus": {"status": "OK"},
              "streamingData": {"adaptiveFormats": [
                ${format(137, "video/mp4; codecs=\\\"avc1.640028\\\"", ", \"width\": 1920, \"height\": 1080, \"fps\": 30")},
                ${format(248, "video/webm; codecs=\\\"vp9\\\"", ", \"width\": 1920, \"height\": 1080")},
                ${format(399, "video/mp4; codecs=\\\"av01.0.08M.08\\\"", ", \"width\": 1920, \"height\": 1080")},
                ${format(140, "audio/mp4; codecs=\\\"mp4a.40.2\\\"", ", \"audioChannels\": 2, \"audioSampleRate\": \"48000\"")},
                ${format(386, "text/mp4; codecs=\\\"unknown\\\"")},
                ${format(
                136,
                "video/mp4; codecs=\\\"avc1.4d401f\\\"",
                ", \"width\": 1280, \"height\": 720",
                url = "https://r1.googlevideo.com/videoplayback?itag=136&n=abc",
            )}
              ]}
            }
            """,
        )

    @Test
    fun `only MP4 audio and video with a direct URL are played from segments`() {
        val formats = LiveSegmentSessions.segmentFormats(response).map { it.first }

        assertThat(formats.map { it.itag }).containsExactly(137, 399, 140).inOrder()
        assertThat(
            formats.first(),
        ).isEqualTo(LiveSegmentFormat(137, "video/mp4", "avc1.640028", 1000, width = 1920, height = 1080, frameRate = 30))
        assertThat(formats.last().channels).isEqualTo(2)
        assertThat(formats.last().sampleRate).isEqualTo(48_000)
    }
}
