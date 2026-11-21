package io.github.aedev.flow.ui.screens.history

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.engagement.HiddenContentStore
import io.github.aedev.flow.data.local.VideoHistoryEntry
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.local.dao.VideoDao
import io.github.aedev.flow.data.local.dao.WatchHistoryDao
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.shorts.ShortsContentFilter
import io.github.aedev.flow.data.shorts.queue.ShortsQueueHandoff
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val viewHistory: ViewHistory = mockk(relaxed = true)
    private val youTubeRepository: YouTubeRepository = mockk(relaxed = true)
    private val videoDao: VideoDao = mockk(relaxed = true)
    private val watchHistoryDao: WatchHistoryDao = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `initial state loads history entries`() =
        runTest {
            val historyList =
                listOf(
                    VideoHistoryEntry(
                        videoId = "vid_1",
                        title = "Video Title",
                        channelName = "Channel Name",
                        channelId = "ch_1",
                        thumbnailUrl = "https://example.com/thumb.jpg",
                        duration = 60000,
                        position = 30000,
                        timestamp = 1000L,
                    ),
                )
            coEvery { viewHistory.getAllHistory() } returns flowOf(historyList)
            coEvery { videoDao.getVideo("vid_1") } returns null

            val viewModel =
                HistoryViewModel(
                    viewHistory,
                    youTubeRepository,
                    videoDao,
                    watchHistoryDao,
                    ShortsContentFilter(flowOf(true)),
                    ShortsQueueHandoff(),
                    HiddenContentStore(CoroutineScope(Dispatchers.Unconfined)),
                )
            testDispatcher.scheduler.advanceUntilIdle()

            val uiState = viewModel.uiState.value
            assertThat(uiState.isLoading).isFalse()
            assertThat(uiState.historyEntries.size).isEqualTo(1)
            assertThat(uiState.historyEntries.first().videoId).isEqualTo("vid_1")
        }

    @Test
    fun `clearHistory delegates to viewHistory clearAllHistory`() =
        runTest {
            coEvery { viewHistory.getAllHistory() } returns flowOf(emptyList())

            val viewModel =
                HistoryViewModel(
                    viewHistory,
                    youTubeRepository,
                    videoDao,
                    watchHistoryDao,
                    ShortsContentFilter(flowOf(true)),
                    ShortsQueueHandoff(),
                    HiddenContentStore(CoroutineScope(Dispatchers.Unconfined)),
                )
            viewModel.clearHistory()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { viewHistory.clearAllHistory() }
        }

    @Test
    fun `removeFromHistory delegates to viewHistory clearVideoHistory`() =
        runTest {
            coEvery { viewHistory.getAllHistory() } returns flowOf(emptyList())

            val viewModel =
                HistoryViewModel(
                    viewHistory,
                    youTubeRepository,
                    videoDao,
                    watchHistoryDao,
                    ShortsContentFilter(flowOf(true)),
                    ShortsQueueHandoff(),
                    HiddenContentStore(CoroutineScope(Dispatchers.Unconfined)),
                )
            viewModel.removeFromHistory("vid_123")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { viewHistory.clearVideoHistory("vid_123") }
        }
}
