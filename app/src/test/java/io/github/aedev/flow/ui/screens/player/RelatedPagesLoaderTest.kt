package io.github.aedev.flow.ui.screens.player

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.model.WatchRelatedPage
import io.github.aedev.flow.data.recommendation.FeedExclusions
import io.github.aedev.flow.data.repository.WatchRelatedPages
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.ui.screens.player.state.RelatedAppend
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelatedPagesLoaderTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(dispatcher)
    private val pages: WatchRelatedPages = mockk()
    private val playerManager: EnhancedPlayerManager =
        mockk(relaxed = true) {
            every { watchedSuggestionsSource } returns null
        }
    private val playerPreferences: PlayerPreferences =
        mockk(relaxed = true) {
            every { autoplayEnabled } returns flowOf(true)
        }
    private var shortsEnabled = true
    private val uiState =
        MutableStateFlow(
            VideoPlayerUiState(
                cachedVideo = video("playing"),
                relatedVideos = listOf(video("a"), video("b")),
                relatedContinuation = "t1",
            ),
        )

    private val loader =
        RelatedPagesLoader(
            pages = pages,
            uiState = uiState,
            playerManager = playerManager,
            playerPreferences = playerPreferences,
            scope = scope,
            networkDispatcher = dispatcher,
            shortsEnabled = { shortsEnabled },
            exclusions = { FeedExclusions.NONE },
        )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a page in flight is asked for once however often the end is reached`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<WatchRelatedPage?>()
            coEvery { pages.nextPage("t1") } coAnswers { pending.await() }

            repeat(3) { loader.loadMore("playing") }
            advanceUntilIdle()
            assertThat(uiState.value.relatedAppend).isEqualTo(RelatedAppend.LOADING)

            pending.complete(WatchRelatedPage(listOf(video("c")), continuation = "t2"))
            advanceUntilIdle()

            coVerify(exactly = 1) { pages.nextPage("t1") }
            assertThat(uiState.value.relatedContinuation).isEqualTo("t2")
            assertThat(uiState.value.relatedAppend).isEqualTo(RelatedAppend.IDLE)
        }

    @Test
    fun `a page appends only the videos the lane does not list yet and extends autoplay`() =
        runTest(dispatcher) {
            shortsEnabled = false
            coEvery { pages.nextPage("t1") } returns
                WatchRelatedPage(
                    videos = listOf(video("b"), video("c"), video("playing"), video("short", isShort = true), video("d")),
                    continuation = null,
                )

            loader.loadMore("playing")
            advanceUntilIdle()

            val lane = uiState.value.relatedVideos.map { it.id }
            assertThat(lane).containsExactly("a", "b", "c", "d").inOrder()
            assertThat(uiState.value.relatedContinuation).isNull()
            verify { playerManager.setAutoplayCandidates("playing", uiState.value.relatedVideos, true) }
        }

    @Test
    fun `the end of the lane asks for nothing more`() =
        runTest(dispatcher) {
            uiState.value = uiState.value.copy(relatedContinuation = null)

            loader.loadMore("playing")
            advanceUntilIdle()

            coVerify(exactly = 0) { pages.nextPage(any()) }
        }

    @Test
    fun `a page that lands after the video changed is dropped`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<WatchRelatedPage?>()
            coEvery { pages.nextPage("t1") } coAnswers { pending.await() }

            loader.loadMore("playing")
            advanceUntilIdle()
            uiState.value = VideoPlayerUiState(cachedVideo = video("other"), relatedVideos = listOf(video("x")))
            pending.complete(WatchRelatedPage(listOf(video("c")), continuation = "t2"))
            advanceUntilIdle()

            assertThat(uiState.value.relatedVideos.map { it.id }).containsExactly("x")
            verify(exactly = 0) { playerManager.setAutoplayCandidates(any(), any(), any()) }
        }

    @Test
    fun `a failed page waits for a retry instead of asking again`() =
        runTest(dispatcher) {
            coEvery { pages.nextPage("t1") } returns null

            loader.loadMore("playing")
            advanceUntilIdle()

            assertThat(uiState.value.relatedAppend).isEqualTo(RelatedAppend.FAILED)
            assertThat(uiState.value.relatedContinuation).isEqualTo("t1")

            coEvery { pages.nextPage("t1") } returns WatchRelatedPage(listOf(video("c")))
            loader.loadMore("playing")
            advanceUntilIdle()

            coVerify(exactly = 2) { pages.nextPage("t1") }
            assertThat(uiState.value.relatedVideos.map { it.id }).containsExactly("a", "b", "c").inOrder()
        }

    private fun video(
        id: String,
        isShort: Boolean = false,
    ) = Video(
        id = id,
        title = id,
        channelName = "Channel",
        channelId = "channel",
        thumbnailUrl = "https://example.invalid/$id.jpg",
        duration = 120,
        viewCount = 1L,
        uploadDate = "2026-01-01",
        isShort = isShort,
    )
}
