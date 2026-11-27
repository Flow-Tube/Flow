package io.github.aedev.flow.data.subscriptions

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

    private fun answer(
        status: PlayabilityStatus,
        details: PlayerResponse.VideoDetails = DETAILS,
    ) {
        coEvery { YouTube.player(any(), any(), YouTubeClient.ANDROID, any(), any(), any(), any()) } returns
            Result.success(response(status, details))
    }

    @Test
    fun `a stream on air is live with no length`() =
        runTest {
            answer(OK, DETAILS.copy(lengthSeconds = "0", isLive = true, isLiveContent = true))

            val row = fetchSubscriptionPlayerMetadata(video("v1"), TIMEOUT_MS)?.enriched

            assertThat(row?.isLive).isTrue()
            assertThat(row?.duration).isEqualTo(0)
        }

    @Test
    fun `an ended stream is a video with its length, also when the row said live`() =
        runTest {
            answer(OK, DETAILS.copy(lengthSeconds = "5400", isLive = null, isLiveContent = true))

            val row = fetchSubscriptionPlayerMetadata(video("v1").copy(isLive = true), TIMEOUT_MS)?.enriched

            assertThat(row?.isLive).isFalse()
            assertThat(row?.duration).isEqualTo(5400)
            assertThat(video("v1").copy(isLive = true).withPlayerMetadata(row!!).isLive).isFalse()
        }

    @Test
    fun `an ended stream is marked as streamed and keeps the mark on the row`() =
        runTest {
            answer(OK, DETAILS.copy(lengthSeconds = "5400", isLive = false, isLiveContent = true))
            val stored = video("v1").copy(uploadDate = "2 days ago")

            val row = fetchSubscriptionPlayerMetadata(stored, TIMEOUT_MS)?.enriched

            assertThat(row?.uploadDate).isEqualTo("Streamed 2 days ago")
            assertThat(stored.copy(uploadDate = "3 days ago").withPlayerMetadata(row!!).uploadDate).isEqualTo("Streamed 3 days ago")
        }

    @Test
    fun `a plain upload is not marked`() =
        runTest {
            answer(OK, DETAILS.copy(lengthSeconds = "300", isLive = false, isLiveContent = false))

            val row = fetchSubscriptionPlayerMetadata(video("v1").copy(uploadDate = "2 days ago"), TIMEOUT_MS)?.enriched

            assertThat(row?.uploadDate).isEqualTo("2 days ago")
        }

    @Test
    fun `a stream that ended and has no length yet clears the live badge`() =
        runTest {
            answer(OK, DETAILS.copy(lengthSeconds = "0", isLive = false, isLiveContent = true))

            assertThat(fetchSubscriptionPlayerMetadata(video("v1"), TIMEOUT_MS)?.enriched).isNull()
            val cleared = fetchSubscriptionPlayerMetadata(video("v1").copy(isLive = true), TIMEOUT_MS)?.enriched
            assertThat(cleared?.isLive).isFalse()
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

        val OK = PlayabilityStatus(status = "OK", reason = null)

        val DETAILS =
            PlayerResponse.VideoDetails(
                videoId = "v1",
                title = "Original title",
                author = "Channel",
                channelId = "UC1",
                lengthSeconds = "70",
            )

        fun response(
            status: PlayabilityStatus,
            details: PlayerResponse.VideoDetails = DETAILS,
        ) = PlayerResponse(
            responseContext = ResponseContext(visitorData = null, serviceTrackingParams = null),
            playabilityStatus = status,
            playerConfig = null,
            streamingData = null,
            videoDetails = details,
            playbackTracking = null,
        )
    }
}
