package io.github.aedev.flow.player.stream

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import kotlinx.serialization.json.Json
import org.junit.Test

class UpcomingPollParseTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private fun response(body: String): PlayerResponse = json.decodeFromString("""{"responseContext":{},$body}""")

    @Test
    fun `a stream that has not started gives its broadcast and the server's poll delay`() {
        val offline =
            response(
                """"playabilityStatus":{"status":"LIVE_STREAM_OFFLINE","reason":"Waiting",""" +
                    """"liveStreamability":{"liveStreamabilityRenderer":{"videoId":"v","broadcastId":"1","pollDelayMs":"15000"}}},""" +
                    """"heartbeatParams":{"intervalMilliseconds":"30000"}""",
            )

        val poll = UpcomingPremiereProbe.pollOf(offline)

        assertThat(poll).isEqualTo(UpcomingPoll(false, true, "1", 15_000L, 30_000L))
    }

    @Test
    fun `a stream that started is playable and no longer upcoming`() {
        val live =
            response(
                """"playabilityStatus":{"status":"OK"},"streamingData":{"hlsManifestUrl":"https://manifest.googlevideo.com/x"}""",
            )

        val poll = UpcomingPremiereProbe.pollOf(live)

        assertThat(poll.isPlayable).isTrue()
        assertThat(poll.isUpcoming).isFalse()
        assertThat(poll.pollDelayMs).isNull()
    }

    @Test
    fun `a refusal is neither playable nor upcoming`() {
        val poll = UpcomingPremiereProbe.pollOf(response(""""playabilityStatus":{"status":"ERROR","reason":"Video unavailable"}"""))

        assertThat(poll.isPlayable).isFalse()
        assertThat(poll.isUpcoming).isFalse()
    }
}
