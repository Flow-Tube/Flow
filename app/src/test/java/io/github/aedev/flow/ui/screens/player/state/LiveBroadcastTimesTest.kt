package io.github.aedev.flow.ui.screens.player.state

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import kotlinx.serialization.json.Json
import org.junit.Test

class LiveBroadcastTimesTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private fun response(
        videoDetails: String,
        broadcast: String?,
    ): PlayerResponse {
        val microformat = broadcast?.let { ""","microformat":{"playerMicroformatRenderer":{"liveBroadcastDetails":$it}}""" }.orEmpty()
        return json.decodeFromString(
            """{"responseContext":{},"playabilityStatus":{"status":"OK"},""" +
                """"videoDetails":{"videoId":"v","channelId":"c",$videoDetails}$microformat}""",
        )
    }

    @Test
    fun `a stream on air has a start and is not archived`() {
        val onAir =
            response(
                """"isLive":true,"isLiveContent":true""",
                """{"isLiveNow":true,"startTimestamp":"2026-09-15T23:00:27+00:00"}""",
            )

        assertThat(LiveBroadcastTimes.startMs(onAir)).isEqualTo(1_789_513_227_000L)
        assertThat(LiveBroadcastTimes.isArchived(onAir)).isFalse()
    }

    @Test
    fun `an end time marks the stream archived even without the live flags`() {
        val ended =
            response(
                """"isLiveContent":true""",
                """{"isLiveNow":false,"startTimestamp":"2026-09-15T16:00:27-07:00","endTimestamp":"2026-09-15T18:00:00-07:00"}""",
            )

        assertThat(LiveBroadcastTimes.isArchived(ended)).isTrue()
        assertThat(LiveBroadcastTimes.startMs(ended)).isEqualTo(1_789_513_227_000L)
    }

    @Test
    fun `no microformat or an unreadable time gives no start`() {
        assertThat(LiveBroadcastTimes.startMs(response(""""isLiveContent":true""", null))).isNull()
        assertThat(LiveBroadcastTimes.startMs(response(""""isLiveContent":true""", """{"startTimestamp":"yesterday"}"""))).isNull()
        assertThat(LiveBroadcastTimes.isArchived(response(""""isLiveContent":false""", null))).isFalse()
    }

    @Test
    fun `a creator who hides likes is read from the video details`() {
        assertThat(response(""""allowRatings":false""", null).videoDetails?.allowRatings).isFalse()
        assertThat(response(""""allowRatings":true""", null).videoDetails?.allowRatings).isTrue()
    }
}
