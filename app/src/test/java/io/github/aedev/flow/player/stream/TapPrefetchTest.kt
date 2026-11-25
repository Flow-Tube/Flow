package io.github.aedev.flow.player.stream

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.ResponseContext
import io.github.aedev.flow.innertube.models.YouTubeClient
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TapPrefetchTest {
    private var now = 1_000L
    private var generation = 0
    private val extractions = mutableListOf<String>()
    private var answer = CompletableDeferred<InnerTubeVideoStreamExtractor.VideoExtractionResult?>()
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
            usedClient = YouTubeClient.VISIONOS,
            sabrInfo = null,
        )

    private fun TestScope.prefetcher() =
        TapPrefetch(
            scope = backgroundScope,
            extract = { videoId ->
                extractions += videoId
                answer.await()
            },
            identity = { generation },
            nowMs = { now },
        )

    @Test
    fun `a finished prefetch is taken by the load that follows the tap, once`() =
        runTest {
            val tap = prefetcher()
            tap.prefetch("tapped")
            answer.complete(result)
            runCurrent()

            assertThat(tap.take("tapped")).isSameInstanceAs(result)
            assertThat(tap.take("tapped")).isNull()
            assertThat(extractions).containsExactly("tapped")
        }

    @Test
    fun `a load that asks while the prefetch runs joins it, and the late result is not offered to a reload`() =
        runTest {
            val tap = prefetcher()
            tap.prefetch("tapped")
            runCurrent()

            assertThat(tap.take("tapped")).isNull()
            answer.complete(result)
            runCurrent()

            assertThat(tap.take("tapped")).isNull()
        }

    @Test
    fun `an offer older than YouTube's early-fetch lifetime is not used`() =
        runTest {
            val tap = prefetcher()
            tap.prefetch("tapped")
            answer.complete(result)
            runCurrent()
            now += TapPrefetch.MAX_AGE_MS + 1

            assertThat(tap.take("tapped")).isNull()
        }

    @Test
    fun `a visitor identity change while it ran or before the load drops the offer`() =
        runTest {
            val tap = prefetcher()
            tap.prefetch("tapped")
            generation++
            answer.complete(result)
            runCurrent()
            assertThat(tap.take("tapped")).isNull()

            answer = CompletableDeferred(result)
            tap.prefetch("again")
            runCurrent()
            generation++
            assertThat(tap.take("again")).isNull()
        }

    @Test
    fun `tapping the same video again while its result is held does not extract again`() =
        runTest {
            val tap = prefetcher()
            tap.prefetch("tapped")
            answer.complete(result)
            runCurrent()

            tap.prefetch("tapped")
            runCurrent()

            assertThat(extractions).containsExactly("tapped")
            assertThat(tap.take("tapped")).isSameInstanceAs(result)
        }

    @Test
    fun `a tap on another video replaces the first, whose result is never offered`() =
        runTest {
            val tap = prefetcher()
            tap.prefetch("first")
            runCurrent()
            tap.prefetch("second")
            answer.complete(result)
            runCurrent()

            assertThat(tap.take("first")).isNull()
            assertThat(extractions).containsExactly("first", "second")
        }

    @Test
    fun `a failed prefetch offers nothing`() =
        runTest {
            val tap = prefetcher()
            tap.prefetch("tapped")
            answer.complete(null)
            runCurrent()

            assertThat(tap.take("tapped")).isNull()
        }
}
