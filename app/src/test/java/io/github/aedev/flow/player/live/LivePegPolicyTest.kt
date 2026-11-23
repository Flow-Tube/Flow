package io.github.aedev.flow.player.live

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.ResponseContext
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import org.junit.Test

class LivePegPolicyTest {
    private fun response(
        dvr: Boolean?,
        latency: String? = "MDE_STREAM_OPTIMIZATIONS_RENDERER_LATENCY_NORMAL",
        readaheadSeconds: Double? = 12.0,
        isLive: Boolean = true,
    ) = PlayerResponse(
        responseContext = ResponseContext(visitorData = null, serviceTrackingParams = null),
        playabilityStatus = PlayerResponse.PlayabilityStatus(status = "OK", reason = null),
        playerConfig = PlayerResponse.PlayerConfig(livePlayerConfig = PlayerResponse.PlayerConfig.LivePlayerConfig(readaheadSeconds)),
        streamingData = null,
        videoDetails =
            PlayerResponse.VideoDetails(
                videoId = "v",
                title = null,
                author = null,
                channelId = "c",
                isLive = isLive,
                isLiveDvrEnabled = dvr,
                latencyClass = latency,
            ),
        playbackTracking = null,
    )

    @Test
    fun `a stream with DVR is never pegged`() {
        assertThat(LivePegPolicy.ruleFor(response(dvr = true))).isNull()
        assertThat(LivePegPolicy.ruleFor(response(dvr = null))).isNull()
        assertThat(LivePegPolicy.shouldPeg(null, behindLiveMs = 3_600_000)).isFalse()
    }

    @Test
    fun `without DVR the limit is the readahead plus the latency class margin`() {
        assertThat(LivePegPolicy.ruleFor(response(dvr = false))!!.maxBehindLiveMs).isEqualTo(27_000)
        assertThat(
            LivePegPolicy.ruleFor(response(dvr = false, latency = "MDE_STREAM_OPTIMIZATIONS_RENDERER_LATENCY_LOW"))!!.maxBehindLiveMs,
        ).isEqualTo(18_000)
        assertThat(
            LivePegPolicy.ruleFor(response(dvr = false, latency = "MDE_STREAM_OPTIMIZATIONS_RENDERER_LATENCY_ULTRA_LOW"))!!.maxBehindLiveMs,
        ).isEqualTo(16_000)
        assertThat(LivePegPolicy.ruleFor(response(dvr = false, readaheadSeconds = null))!!.maxBehindLiveMs).isEqualTo(27_000)
    }

    @Test
    fun `only a stall that leaves playback past the limit pegs`() {
        val rule = LivePegPolicy.Rule(maxBehindLiveMs = 27_000)
        assertThat(LivePegPolicy.shouldPeg(rule, behindLiveMs = 27_000)).isFalse()
        assertThat(LivePegPolicy.shouldPeg(rule, behindLiveMs = 42_000)).isTrue()
    }

    @Test
    fun `a stream that is not live has no rule`() {
        assertThat(LivePegPolicy.ruleFor(response(dvr = false, isLive = false))).isNull()
    }
}
