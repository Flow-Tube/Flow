package io.github.aedev.flow.ui.screens.player

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.repository.YouTubeRepository.LiveWatchMetadata
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
 * What a live video's screen is refreshed with once its watch metadata lands, and what it costs.
 * The second extraction stack is stubbed to answer nothing, which is how a live open behaves when
 * it is gone: the InnerTube answer alone has to fill every field.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerLiveWatchMetadataTest {
    private val testDispatcher = StandardTestDispatcher()
    private val repository: YouTubeRepository = mockk(relaxed = true)
    private val playerManager: EnhancedPlayerManager = mockk(relaxed = true)
    private val playerPreferences: PlayerPreferences = mockk(relaxed = true)
    private val loaderScope = CoroutineScope(testDispatcher)
    private val results = mutableListOf<SecondaryMetadata>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { playerManager.watchedSuggestionsSource } returns null
        every { playerPreferences.originalTitles } returns flowOf(false)
        every { playerPreferences.rytdEnabled } returns flowOf(false)
        coEvery { repository.getLiveWatchMetadataFromNewPipe(any()) } returns null
    }

    @After
    fun tearDown() {
        loaderScope.cancel()
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun loader() =
        PlayerSecondaryMetadataLoader(
            repository = repository,
            playerManager = playerManager,
            playerPreferences = playerPreferences,
            scope = loaderScope,
            networkDispatcher = testDispatcher,
            currentState = { VideoPlayerUiState() },
            relatedVideosFor = { emptyList() },
            shortsEnabled = { true },
            exclusions = { io.github.aedev.flow.data.recommendation.FeedExclusions.NONE },
            isPlaybackCurrent = { it == TOKEN },
            onResult = { results += it },
        )

    private fun video(
        id: String,
        title: String = "Video $id",
        channelName: String = "Channel",
    ) = Video(
        id = id,
        title = title,
        channelName = channelName,
        channelId = "UC_channel",
        thumbnailUrl = "https://i.ytimg.com/vi/$id/hq720.jpg",
        duration = 0,
        viewCount = 0,
        uploadDate = "",
    )

    private val card = video("live1", title = "From the card", channelName = "Card channel")

    private val innerTube =
        LiveWatchMetadata(
            title = "Lofi radio",
            channelName = "Lofi Girl",
            channelId = "UCSJ4gkVC6NrvII8umztf0Ow",
            channelAvatarUrl = "https://yt3.ggpht.com/lofi=s176",
            subscriberCount = 15_600_000,
            viewCount = 42_000,
            description = "beats to relax to",
            relatedVideos = listOf(video("r1"), video("live1"), video("r2"), video("r1")),
        )

    private fun liveResult() = results.filterIsInstance<SecondaryMetadata.LiveWatch>().single()

    @Test
    fun `a live video is refreshed with the watch page's title, channel, counts and related videos`() =
        runTest(testDispatcher) {
            coEvery { repository.getLiveWatchMetadata("live1") } returns innerTube

            loader().refreshLiveWatchMetadata("live1", card, TOKEN)
            advanceUntilIdle()

            val result = liveResult()
            assertThat(result.video.title).isEqualTo("Lofi radio")
            assertThat(result.video.channelName).isEqualTo("Lofi Girl")
            assertThat(result.video.channelId).isEqualTo("UCSJ4gkVC6NrvII8umztf0Ow")
            assertThat(result.video.channelThumbnailUrl).isEqualTo("https://yt3.ggpht.com/lofi=s176")
            assertThat(result.video.viewCount).isEqualTo(42_000)
            assertThat(result.video.description).isEqualTo("beats to relax to")
            assertThat(result.video.isLive).isTrue()
            assertThat(result.channelAvatarUrl).isEqualTo("https://yt3.ggpht.com/lofi=s176")
            assertThat(result.subscriberCount).isEqualTo(15_600_000)
            assertThat(result.relatedVideos.map { it.id }).containsExactly("r1", "r2").inOrder()
        }

    @Test
    fun `blank fields keep what the card already showed`() =
        runTest(testDispatcher) {
            coEvery { repository.getLiveWatchMetadata("live1") } returns
                innerTube.copy(title = " ", channelName = null, channelId = "", channelAvatarUrl = null, viewCount = null)

            loader().refreshLiveWatchMetadata("live1", card, TOKEN)
            advanceUntilIdle()

            val video = liveResult().video
            assertThat(video.title).isEqualTo("From the card")
            assertThat(video.channelName).isEqualTo("Card channel")
            assertThat(video.channelId).isEqualTo("UC_channel")
            assertThat(video.channelThumbnailUrl).isEqualTo(card.channelThumbnailUrl)
            assertThat(video.viewCount).isEqualTo(0)
        }

    @Test
    fun `with no related videos on the watch page the related lane is searched once`() =
        runTest(testDispatcher) {
            coEvery { repository.getLiveWatchMetadata("live1") } returns innerTube.copy(relatedVideos = emptyList())
            coEvery { repository.getLiveRelatedVideosBySearch("live1", "Lofi radio", "Lofi Girl") } returns listOf(video("s1"))

            loader().refreshLiveWatchMetadata("live1", card, TOKEN)
            advanceUntilIdle()

            assertThat(liveResult().relatedVideos.map { it.id }).containsExactly("s1")
            coVerify(exactly = 1) { repository.getLiveRelatedVideosBySearch(any(), any(), any()) }
        }

    @Test
    fun `nothing is published when the watch page cannot be read`() =
        runTest(testDispatcher) {
            coEvery { repository.getLiveWatchMetadata("live1") } returns null

            loader().refreshLiveWatchMetadata("live1", card, TOKEN)
            advanceUntilIdle()

            assertThat(results).isEmpty()
        }

    @Test
    fun `a live open asks the InnerTube watch page once, and repeats for the same load are dropped`() =
        runTest(testDispatcher) {
            coEvery { repository.getLiveWatchMetadata("live1") } returns innerTube
            val loader = loader()

            repeat(3) { loader.refreshLiveWatchMetadata("live1", card, TOKEN) }
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.getLiveWatchMetadata("live1") }
            coVerify(atMost = 1) { repository.getLiveWatchMetadataFromNewPipe(any()) }
            assertThat(results.filterIsInstance<SecondaryMetadata.LiveWatch>()).hasSize(1)
        }

    private companion object {
        const val TOKEN = 1L
    }
}
