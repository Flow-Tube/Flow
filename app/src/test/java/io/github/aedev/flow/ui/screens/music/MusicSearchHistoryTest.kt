package io.github.aedev.flow.ui.screens.music

import io.github.aedev.flow.data.local.PrivacyGate
import io.github.aedev.flow.data.local.SearchHistoryRepository
import io.github.aedev.flow.data.local.SearchHistoryScope
import io.github.aedev.flow.data.local.SearchType
import io.github.aedev.flow.data.music.DownloadManager
import io.github.aedev.flow.data.newmusic.InnertubeMusicService
import io.github.aedev.flow.innertube.YouTube
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
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
class MusicSearchHistoryTest {
    private val testDispatcher = StandardTestDispatcher()
    private val history: SearchHistoryRepository = mockk(relaxed = true)
    private var deepFlow = false

    private fun viewModel() =
        MusicSearchViewModel(
            mockk<DownloadManager>(relaxed = true).also { every { it.downloadedTracks } returns flowOf(emptyList()) },
            history,
            PrivacyGate(
                deepFlowActive = { deepFlow },
                watchHistoryPaused = { false },
                scrobbleDuringDeepFlow = { false },
            ),
            mockk(relaxed = true),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { history.getSearchHistoryFlow(any()) } returns flowOf(emptyList())
        mockkObject(InnertubeMusicService, YouTube)
        coEvery { InnertubeMusicService.fetchMoodAndGenres() } returns emptyList()
        coEvery { YouTube.searchSummary(any()) } returns Result.failure(IllegalStateException("offline"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `a music search is saved while Deep Flow is off`() =
        runTest(testDispatcher) {
            viewModel().performSearch("  raga  ", SearchType.TEXT)
            testDispatcher.scheduler.runCurrent()

            coVerify(exactly = 1) { history.saveSearchQuery("raga", SearchType.TEXT, SearchHistoryScope.MUSIC) }
        }

    @Test
    fun `Deep Flow keeps a music search out of history`() =
        runTest(testDispatcher) {
            deepFlow = true
            viewModel().performSearch("raga", SearchType.TEXT)
            testDispatcher.scheduler.runCurrent()

            coVerify(exactly = 0) { history.saveSearchQuery(any(), any(), any(), any()) }
        }
}
