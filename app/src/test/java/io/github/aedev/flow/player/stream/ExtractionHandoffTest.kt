package io.github.aedev.flow.player.stream

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.ResponseContext
import io.github.aedev.flow.innertube.models.YouTubeClient
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import org.junit.Test

class ExtractionHandoffTest {
    private var now = 1_000L
    private val handoff = ExtractionHandoff { now }
    private val result =
        InnerTubeVideoStreamExtractor.VideoExtractionResult(
            videoFormats = emptyList(),
            audioFormats = emptyList(),
            playerResponse =
                PlayerResponse(
                    responseContext = ResponseContext(visitorData = null, serviceTrackingParams = null),
                    playabilityStatus = PlayerResponse.PlayabilityStatus(status = "OK", reason = null),
                    playerConfig = null,
                    streamingData = null,
                    videoDetails = null,
                    playbackTracking = null,
                ),
            usedClient = YouTubeClient.WEB,
            sabrInfo = null,
        )

    @Test
    fun `the promoted video's load takes the offered extraction`() {
        handoff.offer("next", result)

        assertThat(handoff.take("next")).isSameInstanceAs(result)
    }

    @Test
    fun `an offer serves one load only, so a later reload extracts fresh`() {
        handoff.offer("next", result)
        handoff.take("next")

        assertThat(handoff.take("next")).isNull()
    }

    @Test
    fun `a load for another video gets nothing and spends the offer`() {
        handoff.offer("next", result)

        assertThat(handoff.take("other")).isNull()
        assertThat(handoff.take("next")).isNull()
    }

    @Test
    fun `an offer nobody took in time is not used`() {
        handoff.offer("next", result)
        now += ExtractionHandoff.MAX_AGE_MS + 1

        assertThat(handoff.take("next")).isNull()
    }

    @Test
    fun `nothing offered means nothing taken`() {
        assertThat(handoff.take("next")).isNull()
    }
}
