package io.github.aedev.flow.ui.components.shared.quickactions

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.engagement.VideoEngagementUseCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickActionsSubscriptionStateTest {
    private val testDispatcher = StandardTestDispatcher()
    private val engagement: VideoEngagementUseCase = mockk(relaxed = true)
    private val subscribed = MutableStateFlow(false)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { engagement.subscriptionState(any()) } returns subscribed
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun viewModel() =
        QuickActionsViewModel(
            repository = mockk(relaxed = true),
            playlistRepository = mockk(relaxed = true),
            videoDownloadManager = mockk(relaxed = true),
            engagement = engagement,
            feedback = mockk(relaxed = true),
            downloadOptions = mockk(relaxed = true),
            likedMedia = mockk(relaxed = true),
            playerManager = { mockk(relaxed = true) },
            musicVideos = mockk(relaxed = true),
            autoDownload = mockk(relaxed = true),
            aiReporter = mockk(relaxed = true),
        )

    @Test
    fun `opening a sheet again does not add another observer for the same channel`() =
        runTest {
            val viewModel = viewModel()

            repeat(5) { viewModel.loadSubscriptionState("UCa") }
            testDispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 1) { engagement.subscriptionState("UCa") }
        }

    @Test
    fun `each channel is observed once and follows its subscription`() =
        runTest {
            val viewModel = viewModel()

            viewModel.loadSubscriptionState("UCa")
            viewModel.loadSubscriptionState("UCb")
            testDispatcher.scheduler.advanceUntilIdle()
            subscribed.value = true
            testDispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 1) { engagement.subscriptionState("UCa") }
            verify(exactly = 1) { engagement.subscriptionState("UCb") }
            assertThat(viewModel.subscribedChannelIds.value).containsExactly("UCa", "UCb")
        }
}
