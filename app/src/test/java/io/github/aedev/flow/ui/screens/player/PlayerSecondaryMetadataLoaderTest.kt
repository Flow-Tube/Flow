package io.github.aedev.flow.ui.screens.player

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FeedExclusions
import io.github.aedev.flow.data.repository.WatchChannel
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.innertube.models.response.PaidPromotionLabel
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pins the fetch economy of [PlayerSecondaryMetadataLoader]: one request per concern per load,
 * a repeat while that request is in flight dropped, a new video cancelling the old one, and every
 * related list leaving through [io.github.aedev.flow.player.PlayerRelatedVideosPolicy].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSecondaryMetadataLoaderTest {
    private val testDispatcher = StandardTestDispatcher()
    private val repository: YouTubeRepository = mockk(relaxed = true)
    private val playerManager: EnhancedPlayerManager = mockk(relaxed = true)
    private val playerPreferences: PlayerPreferences = mockk(relaxed = true)
    private val playerState = MutableStateFlow(EnhancedPlayerState())
    private val loaderScope = CoroutineScope(testDispatcher)

    private var uiState = VideoPlayerUiState()
    private var currentToken = TOKEN_A
    private var shortsEnabled = true
    private var exclusions: FeedExclusions = FeedExclusions.NONE
    private val results = mutableListOf<SecondaryMetadata>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { playerManager.playerState } returns playerState
        every { playerManager.relatedCandidatesFor(any()) } returns emptyList()
        every { playerPreferences.originalTitles } returns flowOf(false)
        coEvery { repository.getRelatedCandidates(any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        loaderScope.cancel()
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun loader(): PlayerSecondaryMetadataLoader =
        PlayerSecondaryMetadataLoader(
            repository = repository,
            playerManager = playerManager,
            playerPreferences = playerPreferences,
            scope = loaderScope,
            networkDispatcher = testDispatcher,
            currentState = { uiState },
            relatedVideosFor = { videoId ->
                uiState
                    .takeIf { it.cachedVideo?.id == videoId }
                    ?.relatedVideos
                    .orEmpty()
            },
            shortsEnabled = { shortsEnabled },
            exclusions = { exclusions },
            isPlaybackCurrent = { it == currentToken },
            onResult = { results += it },
        )

    private fun playing(videoId: String) {
        playerState.value = EnhancedPlayerState(currentVideoId = videoId, isPlaying = true)
    }

    private fun channelResults(videoId: String) = results.filterIsInstance<SecondaryMetadata.Channel>().filter { it.videoId == videoId }

    private fun relatedResults() = results.filterIsInstance<SecondaryMetadata.Related>()

    @Test
    fun `each video id costs exactly one channel request`() =
        runTest(testDispatcher) {
            val first = WatchChannel(AVATAR_ONE, 10L)
            val second = WatchChannel(AVATAR_TWO, 20L)
            coEvery { repository.watchChannel("v1") } returns first
            coEvery { repository.watchChannel("v2") } returns second
            val loader = loader()

            playing("v1")
            loader.loadChannelMetadata("v1", embeddedAvatarUrls = emptyList(), loadToken = TOKEN_A)
            advanceUntilIdle()

            currentToken = TOKEN_B
            playing("v2")
            loader.loadChannelMetadata("v2", embeddedAvatarUrls = emptyList(), loadToken = TOKEN_B)
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.watchChannel("v1") }
            coVerify(exactly = 1) { repository.watchChannel("v2") }
            assertThat(channelResults("v1").single().subscriberCount).isEqualTo(10L)
            assertThat(channelResults("v2").single().subscriberCount).isEqualTo(20L)
        }

    @Test
    fun `a repeat channel request for the same load while the first is in flight is dropped`() =
        runTest(testDispatcher) {
            val info = WatchChannel(AVATAR_ONE, 10L)
            coEvery { repository.watchChannel("v1") } returns info
            val loader = loader()

            playing("v1")
            repeat(3) {
                loader.loadChannelMetadata(
                    videoId = "v1",
                    embeddedAvatarUrls = emptyList(),
                    loadToken = TOKEN_A,
                )
            }
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.watchChannel("v1") }
            assertThat(channelResults("v1")).hasSize(1)
        }

    @Test
    fun `a new video cancels the channel request the previous one left in flight`() =
        runTest(testDispatcher) {
            val stalled = CompletableDeferred<WatchChannel>()
            val late = WatchChannel(AVATAR_ONE, 10L)
            val second = WatchChannel(AVATAR_TWO, 20L)
            coEvery { repository.watchChannel("v1") } coAnswers { stalled.await() }
            coEvery { repository.watchChannel("v2") } returns second
            val loader = loader()

            playing("v1")
            loader.loadChannelMetadata("v1", embeddedAvatarUrls = emptyList(), loadToken = TOKEN_A)
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.watchChannel("v1") }

            currentToken = TOKEN_B
            playing("v2")
            loader.loadChannelMetadata("v2", embeddedAvatarUrls = emptyList(), loadToken = TOKEN_B)
            advanceUntilIdle()

            stalled.complete(late)
            advanceUntilIdle()

            assertThat(channelResults("v1")).isEmpty()
            assertThat(channelResults("v2").single().subscriberCount).isEqualTo(20L)
        }

    @Test
    fun `the paid promotion label asks for nothing while the switch is off`() =
        runTest(testDispatcher) {
            every { playerPreferences.paidPromotionLabel } returns flowOf(false)

            playing("v1")
            loader().loadPaidPromotion("v1", TOKEN_A)
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.paidPromotion(any()) }
            assertThat(results).isEmpty()
        }

    @Test
    fun `the paid promotion label costs one request per load with the switch on`() =
        runTest(testDispatcher) {
            val label = PaidPromotionLabel("Includes paid promotion", 10_000L)
            every { playerPreferences.paidPromotionLabel } returns flowOf(true)
            coEvery { repository.paidPromotion("v1") } returns label
            val loader = loader()

            playing("v1")
            repeat(3) { loader.loadPaidPromotion("v1", TOKEN_A) }
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.paidPromotion("v1") }
            assertThat(results.filterIsInstance<SecondaryMetadata.PaidPromotion>().single().label).isEqualTo(label)
        }

    @Test
    fun `a device file asks for no channel`() =
        runTest(testDispatcher) {
            val loader = loader()

            playing("local_1")
            loader.loadChannelMetadata("local_1", embeddedAvatarUrls = emptyList(), loadToken = TOKEN_A)
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.watchChannel(any()) }
        }

    @Test
    fun `the embedded avatar publishes before the request and the fetched one replaces it after`() =
        runTest(testDispatcher) {
            val info = WatchChannel(AVATAR_TWO, 10L)
            coEvery { repository.watchChannel("v1") } returns info
            val loader = loader()

            playing("v1")
            loader.loadChannelMetadata(
                videoId = "v1",
                embeddedAvatarUrls = listOf(AVATAR_ONE),
                loadToken = TOKEN_A,
            )

            val embedded = channelResults("v1").single()
            assertThat(embedded.fetchedAvatarUrl).isEqualTo(AVATAR_ONE)
            assertThat(embedded.embeddedAvatarUrl).isNull()
            assertThat(embedded.subscriberCount).isNull()

            advanceUntilIdle()

            val fetched = channelResults("v1").last()
            assertThat(fetched.fetchedAvatarUrl).isEqualTo(AVATAR_TWO)
            assertThat(fetched.embeddedAvatarUrl).isEqualTo(AVATAR_ONE)
            assertThat(fetched.subscriberCount).isEqualTo(10L)
        }

    @Test
    fun `related candidates are sanitised before they are published`() =
        runTest(testDispatcher) {
            shortsEnabled = false
            val loader = loader()

            loader.loadRelatedVideos(
                videoId = "v1",
                primaryCandidates =
                    listOf(
                        video("v1"),
                        video(""),
                        video("v2"),
                        video("v2"),
                        video("v3", isShort = true),
                        video("v4"),
                    ),
                loadToken = TOKEN_A,
            )
            advanceUntilIdle()

            assertThat(relatedResults().single().videos.map { it.id }).containsExactly("v2", "v4").inOrder()
            coVerify(exactly = 0) { repository.getRelatedCandidates(any()) }
        }

    @Test
    fun `the related fallback request runs once per load and is dropped while in flight`() =
        runTest(testDispatcher) {
            coEvery { repository.getRelatedCandidates("v1") } returns listOf(video("v2"))
            val loader = loader()

            playing("v1")
            repeat(3) { loader.loadRelatedVideos("v1", primaryCandidates = emptyList(), loadToken = TOKEN_A) }
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.getRelatedCandidates("v1") }
            assertThat(relatedResults().single().videos.map { it.id }).containsExactly("v2")
        }

    private companion object {
        const val TOKEN_A = 1L
        const val TOKEN_B = 2L
        const val AVATAR_ONE = "https://example.invalid/embedded.jpg"
        const val AVATAR_TWO = "https://example.invalid/fetched.jpg"

        fun video(
            id: String,
            isShort: Boolean = false,
        ): Video =
            Video(
                id = id,
                title = "Title $id",
                channelName = "Channel",
                channelId = "channel",
                thumbnailUrl = "https://example.invalid/$id.jpg",
                duration = 120,
                viewCount = 1L,
                uploadDate = "2026-01-01",
                isShort = isShort,
            )
    }
}
