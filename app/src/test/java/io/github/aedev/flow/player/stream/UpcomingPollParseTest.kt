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

    private fun details(extra: String) = """"videoDetails":{"videoId":"v","title":"T","author":"A","channelId":"UC1"$extra}"""

    @Test
    fun `a scheduled stream is upcoming by its status whatever the reason says`() {
        val offline =
            response(
                """"playabilityStatus":{"status":"LIVE_STREAM_OFFLINE","reason":"La emisión en directo comenzará pronto"},""" +
                    details(""","isUpcoming":true,"isLiveContent":true"""),
            )

        val upcoming = UpcomingPremiereProbe.upcomingOf(offline, nowMs = 0L)

        assertThat(upcoming.isUpcoming).isTrue()
        assertThat(upcoming.details?.isScheduledLive).isTrue()
    }

    @Test
    fun `a premiere is upcoming by its flag and is not a scheduled stream`() {
        val premiere =
            response(
                """"playabilityStatus":{"status":"UNPLAYABLE","reason":"El estreno empezará en 2 horas"},""" +
                    details(""","isUpcoming":true,"isLiveContent":false"""),
            )

        val upcoming = UpcomingPremiereProbe.upcomingOf(premiere, nowMs = 0L)

        assertThat(upcoming.isUpcoming).isTrue()
        assertThat(upcoming.details?.isScheduledLive).isFalse()
    }

    @Test
    fun `wording alone no longer makes a video upcoming`() {
        val refused =
            response(
                """"playabilityStatus":{"status":"UNPLAYABLE","reason":"This premiere will begin when the scheduled time comes"},""" +
                    details(""),
            )

        assertThat(UpcomingPremiereProbe.upcomingOf(refused, nowMs = 0L)).isEqualTo(UpcomingPremiere.NOT_UPCOMING)
        assertThat(UpcomingPremiereProbe.pollOf(refused).isUpcoming).isFalse()
    }

    @Test
    fun `a start time already past gives no countdown`() {
        val offline =
            response(
                """"playabilityStatus":{"status":"LIVE_STREAM_OFFLINE","liveStreamability":{"liveStreamabilityRenderer":""" +
                    """{"offlineSlate":{"liveStreamOfflineSlateRenderer":{"scheduledStartTime":"1000"}}}}}""",
            )

        assertThat(UpcomingPremiereProbe.upcomingOf(offline, nowMs = 2_000_000L).scheduledStartMs).isNull()
        assertThat(UpcomingPremiereProbe.upcomingOf(offline, nowMs = 0L).scheduledStartMs).isEqualTo(1_000_000L)
    }
}
