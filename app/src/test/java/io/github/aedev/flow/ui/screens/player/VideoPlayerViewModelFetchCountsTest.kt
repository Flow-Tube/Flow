package io.github.aedev.flow.ui.screens.player

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.data.comments.CommentsPageResult
import io.github.aedev.flow.data.local.ChannelSubscription
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.video.DownloadedVideo
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModelHarness.Companion.video
import io.mockk.Called
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Pins how many times each network entry point is entered per user-visible cause, with both
 * extraction failing (InnerTube returns null). Nothing here is a spec: every count is today's
 * behaviour, recorded so a refactor can prove it did not change it.
 *
 * Entry points counted:
 *  - InnerTube: [InnerTubeVideoStreamExtractor.extract]
 *  - Return YouTube Dislike: gated by `playerPreferences.rytdEnabled` — the HTTP call itself uses
 *    HttpURLConnection and cannot be intercepted, so the gate read is what is counted (it is
 *    disabled in the harness so no socket is ever opened)
 *  - premiere probe: [YouTube.player], entered from the both-failed error path
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerViewModelFetchCountsTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var harness: VideoPlayerViewModelHarness

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        harness = VideoPlayerViewModelHarness(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        harness.close()
    }

    private fun TestScope.newViewModel(): VideoPlayerViewModel {
        val viewModel = harness.createViewModel()
        advanceUntilIdle()
        return viewModel
    }

    private fun forgetRecordedCalls() {
        clearAllMocks(answers = false, childMocks = false)
    }

    @Test
    fun `a downloaded video opened by id plays its file, reads the watch page once and never extracts streams`() =
        runTest {
            val row = video("vid_dl").copy(title = "Downloaded title", channelId = "")
            coEvery { harness.videoDownloadManager.findLocalCopy("vid_dl") } returns
                DownloadedVideo(video = row, filePath = "/tmp/vid_dl.mp4")
            coEvery { harness.repository.enrichFromWatchMetadata(any()) } answers {
                firstArg<io.github.aedev.flow.data.model.Video>().copy(title = "Watch title", channelId = "UC_watch")
            }
            val viewModel = newViewModel()
            forgetRecordedCalls()

            viewModel.playVideo(row.copy(title = "", channelName = "", thumbnailUrl = "", duration = 0))
            advanceUntilIdle()

            coVerify(exactly = 0) { InnerTubeVideoStreamExtractor.extract(any(), any()) }
            coVerify(exactly = 0) { YouTube.player(any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { harness.repository.enrichFromWatchMetadata(any()) }
            coVerify(exactly = 1) { harness.repository.getRelatedCandidates("vid_dl") }
            coVerify(exactly = 1) { harness.repository.videoChapters("vid_dl") }
            coVerify(exactly = 1) { harness.repository.videoHeatmap("vid_dl") }
            coVerify(exactly = 0) { harness.repository.videoCategory(any()) }
            val state = viewModel.uiState.value
            assertThat(state.localFilePath).isEqualTo("/tmp/vid_dl.mp4")
            assertThat(state.cachedVideo?.title).isEqualTo("Watch title")
        }

    @Test
    fun `playVideo resets state then makes 1 InnerTube extraction 1 RYD gate read and 1 premiere probe`() =
        runTest {
            val viewModel = newViewModel()
            val video = video("vid_a")

            viewModel.uiState.test {
                assertThat(awaitItem().cachedVideo).isNull()

                viewModel.playVideo(video)

                val reset = expectMostRecentItem()
                assertThat(reset.cachedVideo).isEqualTo(video)
                assertThat(reset.isLoading).isTrue()
                assertThat(reset.error).isNull()
                assertThat(reset.errorHint).isNull()
                assertThat(reset.relatedVideos).isEmpty()
                assertThat(reset.channelAvatarUrl).isEqualTo(video.channelThumbnailUrl)
                assertThat(reset.isRestoredSession).isFalse()
                assertThat(reset.isBackgroundPlaybackMode).isFalse()
                assertThat(reset.shouldDismissPlayer).isFalse()
                assertThat(reset.localFilePath).isNull()

                advanceUntilIdle()

                val terminal = expectMostRecentItem()
                assertThat(terminal.cachedVideo).isEqualTo(video)
                assertThat(terminal.isLoading).isFalse()
                assertThat(terminal.error).isEqualTo("res:${R.string.error_generic}")
                assertThat(terminal.errorHint).isEqualTo("res:${R.string.error_generic_hint}")
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 1) { InnerTubeVideoStreamExtractor.extract("vid_a", forceSabr = false) }
            verify(exactly = 1) { harness.playerPreferences.rytdEnabled }
            coVerify(exactly = 1) { YouTube.player("vid_a", any(), any(), any(), any(), any(), any()) }
            verifyOrder {
                harness.playerManager.pause()
                harness.playerManager.clearAll()
                harness.playerManager.startBackgroundService("vid_a", video.title, video.channelName, video.thumbnailUrl)
            }
            verify(exactly = 1) { GlobalPlayerState.setCurrentVideo(video) }
            coVerify(exactly = 0) { harness.playerPreferences.markVideoUnplayable(any()) }
        }

    @Test
    fun `retryLoadVideo clears the player and repeats the full ladder`() =
        runTest {
            val viewModel = newViewModel()
            viewModel.playVideo(video("vid_a"))
            advanceUntilIdle()
            forgetRecordedCalls()

            viewModel.retryLoadVideo()

            val retrying = viewModel.uiState.value
            assertThat(retrying.isLoading).isTrue()
            assertThat(retrying.error).isNull()
            assertThat(retrying.errorHint).isNull()
            verify(exactly = 1) { harness.playerManager.clearCurrentVideo() }

            advanceUntilIdle()

            val terminal = viewModel.uiState.value
            assertThat(terminal.isLoading).isFalse()
            assertThat(terminal.error).isEqualTo("res:${R.string.error_generic}")
            coVerify(exactly = 1) { InnerTubeVideoStreamExtractor.extract("vid_a", forceSabr = false) }
            verify(exactly = 1) { harness.playerPreferences.rytdEnabled }
            coVerify(exactly = 1) { YouTube.player("vid_a", any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { harness.playerManager.pause() }
            verify(exactly = 0) { harness.playerManager.clearAll() }
        }

    @Test
    fun `the screen leaves refused streams to the player and only shows its give-up`() =
        runTest {
            val viewModel = newViewModel()
            viewModel.playVideo(video("vid_a"))
            advanceUntilIdle()
            forgetRecordedCalls()

            assertThat(harness.playbackAbandonedEvent.tryEmit(Unit)).isTrue()
            runCurrent()

            val terminal = viewModel.uiState.value
            assertThat(terminal.isLoading).isFalse()
            assertThat(terminal.error).isEqualTo("res:${R.string.error_all_stream_sources_failed}")
            assertThat(terminal.errorHint).isEqualTo("res:${R.string.error_playback_retry_hint}")
            coVerify(exactly = 1) { harness.playerPreferences.markVideoUnplayable("vid_a") }
            coVerify(exactly = 0) { InnerTubeVideoStreamExtractor.extract(any(), any()) }
            coVerify(exactly = 0) { harness.playerManager.clearCacheForCurrentVideo() }
        }

    @Test
    fun `retrying a video starts the player's refused-stream budget over`() =
        runTest {
            val viewModel = newViewModel()
            viewModel.playVideo(video("vid_a"))
            advanceUntilIdle()
            forgetRecordedCalls()

            viewModel.retryLoadVideo()
            advanceUntilIdle()

            verify(exactly = 1) { harness.playerManager.resetStreamRecovery() }
        }

    @Test
    fun `player reporting a foreign video id triggers a load without any cached metadata`() =
        runTest {
            val viewModel = newViewModel()

            harness.playerState.value = EnhancedPlayerState(currentVideoId = "ext_1")
            advanceUntilIdle()

            val terminal = viewModel.uiState.value
            assertThat(terminal.cachedVideo).isNull()
            assertThat(terminal.isLoading).isFalse()
            assertThat(terminal.error).isEqualTo("res:${R.string.error_generic}")
            coVerify(exactly = 1) { InnerTubeVideoStreamExtractor.extract("ext_1", forceSabr = false) }
            verify(exactly = 1) { harness.playerPreferences.rytdEnabled }
            coVerify(exactly = 1) { YouTube.player("ext_1", any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { harness.playerManager.startBackgroundService(any(), any(), any(), any()) }
        }

    @Test
    fun `a superseded load is cancelled at its first attempt and its outcome never reaches the ui`() =
        runTest {
            val viewModel = newViewModel()
            val videoA = video("vid_a")
            val videoB = video("vid_b")
            val gateA = CompletableDeferred<InnerTubeVideoStreamExtractor.VideoExtractionResult?>()
            val gateB = CompletableDeferred<InnerTubeVideoStreamExtractor.VideoExtractionResult?>()
            coEvery { InnerTubeVideoStreamExtractor.extract("vid_a", forceSabr = false) } coAnswers { gateA.await() }
            coEvery { InnerTubeVideoStreamExtractor.extract("vid_b", forceSabr = false) } coAnswers { gateB.await() }

            viewModel.uiState.test {
                awaitItem()

                viewModel.playVideo(videoA)
                runCurrent()
                assertThat(expectMostRecentItem().cachedVideo).isEqualTo(videoA)

                viewModel.playVideo(videoB)
                runCurrent()
                val loadingB = expectMostRecentItem()
                assertThat(loadingB.cachedVideo).isEqualTo(videoB)
                assertThat(loadingB.isLoading).isTrue()
                assertThat(loadingB.error).isNull()

                gateA.completeExceptionally(RuntimeException("A failed"))
                runCurrent()
                expectNoEvents()
                assertThat(viewModel.uiState.value.cachedVideo).isEqualTo(videoB)
                assertThat(viewModel.uiState.value.isLoading).isTrue()

                gateB.completeExceptionally(RuntimeException("B failed"))
                advanceUntilIdle()
                val terminal = expectMostRecentItem()
                assertThat(terminal.cachedVideo).isEqualTo(videoB)
                assertThat(terminal.isLoading).isFalse()
                assertThat(terminal.errorHint).isEqualTo("res:${R.string.error_generic_hint}")
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 1) { InnerTubeVideoStreamExtractor.extract("vid_a", forceSabr = false) }
            coVerify(exactly = 1) { InnerTubeVideoStreamExtractor.extract("vid_b", forceSabr = false) }
        }

    @Test
    fun `loadSubscriptionAndLikeState holds one collector per concern across three different ids`() =
        runTest {
            val viewModel = newViewModel()

            viewModel.loadSubscriptionAndLikeState("ch_1", "vid_1")
            advanceUntilIdle()
            viewModel.loadSubscriptionAndLikeState("ch_2", "vid_2")
            advanceUntilIdle()
            viewModel.loadSubscriptionAndLikeState("ch_3", "vid_3")
            advanceUntilIdle()

            assertThat(harness.isSubscribed.subscriptionCount.value).isEqualTo(1)
            assertThat(harness.subscription.subscriptionCount.value).isEqualTo(1)
            assertThat(harness.likeState.subscriptionCount.value).isEqualTo(1)
            verify(exactly = 1) { harness.subscriptionRepository.isSubscribed("ch_3") }
            verify(exactly = 1) { harness.subscriptionRepository.getSubscription("ch_3") }
            verify(exactly = 1) { harness.likedVideosRepository.getLikeState("vid_3") }

            harness.isSubscribed.value = true
            harness.subscription.value =
                ChannelSubscription(
                    channelId = "ch_3",
                    channelName = "Channel",
                    channelThumbnail = "",
                    isNotificationEnabled = true,
                )
            harness.likeState.value = "liked"
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.isSubscribed).isTrue()
            assertThat(viewModel.uiState.value.isNotificationsEnabled).isTrue()
            assertThat(viewModel.uiState.value.likeState).isEqualTo("liked")
        }

    @Test
    fun `loadSubscriptionAndLikeState does not re-collect ids it is already collecting`() =
        runTest {
            val viewModel = newViewModel()

            repeat(3) {
                viewModel.loadSubscriptionAndLikeState("ch_1", "vid_1")
                advanceUntilIdle()
            }

            assertThat(harness.isSubscribed.subscriptionCount.value).isEqualTo(1)
            assertThat(harness.subscription.subscriptionCount.value).isEqualTo(1)
            assertThat(harness.likeState.subscriptionCount.value).isEqualTo(1)
            verify(exactly = 1) { harness.subscriptionRepository.isSubscribed("ch_1") }
            verify(exactly = 1) { harness.subscriptionRepository.getSubscription("ch_1") }
            verify(exactly = 1) { harness.likedVideosRepository.getLikeState("vid_1") }
        }

    @Test
    fun `loadComments drops a second request for the same id while the first is in flight`() =
        runTest {
            val viewModel = newViewModel()
            viewModel.playVideo(video("vid_a"))
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            val comment =
                Comment(
                    id = "c1",
                    author = "author",
                    authorThumbnail = "",
                    text = "first",
                    likeCount = 0,
                    publishedTime = "",
                )
            coEvery { harness.repository.getVideoComments("vid_a", null) } coAnswers {
                gate.await()
                CommentsPageResult(comments = listOf(comment))
            }

            viewModel.loadComments("vid_a")
            runCurrent()
            assertThat(viewModel.isLoadingComments.value).isTrue()
            coVerify(exactly = 1) { harness.repository.getVideoComments("vid_a", null) }

            viewModel.loadComments("vid_a")
            runCurrent()
            coVerify(exactly = 1) { harness.repository.getVideoComments("vid_a", null) }
            assertThat(viewModel.isLoadingComments.value).isTrue()

            gate.complete(Unit)
            advanceUntilIdle()
            assertThat(viewModel.commentsState.value).containsExactly(comment)
            assertThat(viewModel.isLoadingComments.value).isFalse()
            assertThat(viewModel.hasMoreComments.value).isFalse()
        }

    @Test
    fun `loadComments for a video that is not the cached one never reaches the repository`() =
        runTest {
            val viewModel = newViewModel()
            viewModel.playVideo(video("vid_a"))
            advanceUntilIdle()

            viewModel.loadComments("vid_other")
            advanceUntilIdle()

            coVerify(exactly = 0) { harness.repository.getVideoComments(any(), any()) }
            assertThat(viewModel.isLoadingComments.value).isFalse()
        }

    @Test
    fun `loadComments for a local media id short-circuits without a coroutine`() =
        runTest {
            val viewModel = newViewModel()

            viewModel.loadComments("local_1")

            verify { harness.repository wasNot Called }
            assertThat(viewModel.isLoadingComments.value).isFalse()
            assertThat(viewModel.hasMoreComments.value).isFalse()
        }
}
