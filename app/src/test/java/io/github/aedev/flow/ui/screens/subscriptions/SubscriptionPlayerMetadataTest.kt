package io.github.aedev.flow.ui.screens.subscriptions

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.ResponseContext
import io.github.aedev.flow.innertube.models.YouTubeClient
import io.github.aedev.flow.innertube.models.YouTubeLocale
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.innertube.models.response.PlayerResponse.PlayabilityStatus
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModelHarness.Companion.video
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class SubscriptionPlayerMetadataTest {
    @Before
    fun setUp() {
        mockkObject(YouTube)
    }

    @After
    fun tearDown() {
        unmockkObject(YouTube)
    }

    private fun answer(status: PlayabilityStatus) {
        coEvery { YouTube.player(any(), any(), YouTubeClient.ANDROID, any(), any(), any(), any()) } returns
            Result.success(response(status))
    }

    @Test
    fun `a country refusal is reported and the row still gets its duration`() =
        runTest {
            answer(REGION)

            val lookup = fetchSubscriptionPlayerMetadata(video("v1"), TIMEOUT_MS)

            assertThat(lookup?.refusedToViewer).isTrue()
            assertThat(lookup?.enriched?.duration).isEqualTo(70)
            coVerify { YouTube.player("v1", any(), YouTubeClient.ANDROID, any(), any(), YouTubeLocale.EXTRACTION, any()) }
        }

    @Test
    fun `a playable video and a plain unplayable one are not refused`() =
        runTest {
            answer(PlayabilityStatus(status = "OK", reason = null))
            assertThat(fetchSubscriptionPlayerMetadata(video("v1"), TIMEOUT_MS)?.refusedToViewer).isFalse()

            answer(PlayabilityStatus(status = "UNPLAYABLE", reason = "Playback on other apps has been disabled by the video owner"))
            assertThat(fetchSubscriptionPlayerMetadata(video("v1"), TIMEOUT_MS)?.refusedToViewer).isFalse()
        }

    private companion object {
        const val TIMEOUT_MS = 5_000L

        val REGION =
            PlayabilityStatus(
                status = "UNPLAYABLE",
                reason = "Video unavailable",
                errorScreen =
                    PlayabilityStatus.ErrorScreen(
                        PlayabilityStatus.ErrorScreen.PlayerErrorMessageRenderer(
                            subreason =
                                PlayerResponse.Captions.Text(
                                    simpleText = "The uploader has not made this video available in your country",
                                ),
                        ),
                    ),
            )

        fun response(status: PlayabilityStatus) =
            PlayerResponse(
                responseContext = ResponseContext(visitorData = null, serviceTrackingParams = null),
                playabilityStatus = status,
                playerConfig = null,
                streamingData = null,
                videoDetails =
                    PlayerResponse.VideoDetails(
                        videoId = "v1",
                        title = "Original title",
                        author = "Channel",
                        channelId = "UC1",
                        lengthSeconds = "70",
                    ),
                playbackTracking = null,
            )
    }
}
