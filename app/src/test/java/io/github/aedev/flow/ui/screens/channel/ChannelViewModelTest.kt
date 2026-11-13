package io.github.aedev.flow.ui.screens.channel

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.notes.NotesRepository
import io.github.aedev.flow.innertube.pages.channel.ChannelTabKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
class ChannelViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val context: Context = mockk(relaxed = true)
    private val subscriptionRepository: SubscriptionRepository = mockk(relaxed = true)
    private val notesRepository: NotesRepository = mockk(relaxed = true)
    private val playerPreferences: PlayerPreferences = mockk(relaxed = true)

    private lateinit var viewModel: ChannelViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { subscriptionRepository.getSubscription(any()) } returns flowOf(null)
        coEvery { subscriptionRepository.getAllSubscriptions() } returns flowOf(emptyList())
        every { playerPreferences.effectiveChannelNotesEnabled } returns flowOf(true)
        every { playerPreferences.effectiveChannelShortsTabEnabled } returns flowOf(true)
        coEvery { notesRepository.observe(any(), any()) } returns flowOf(null)
        viewModel =
            ChannelViewModel(
                appContext = context,
                subscriptionRepository = subscriptionRepository,
                notesRepository = notesRepository,
                playerPreferences = playerPreferences,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `initial ui state has default values`() =
        runTest {
            val state = viewModel.uiState.value
            assertThat(state.channelId).isNull()
            assertThat(state.isLoading).isFalse()
            assertThat(state.isSubscribed).isFalse()
            assertThat(state.selectedTab).isNull()
        }

    @Test
    fun `selectTab updates selectedTab in uiState`() =
        runTest {
            viewModel.selectTab(ChannelTabKind.Live)
            testDispatcher.scheduler.advanceUntilIdle()

            assertThat(viewModel.uiState.value.selectedTab).isEqualTo(ChannelTabKind.Live)
        }

    @Test
    fun `saveScrollPosition retains index and offset`() =
        runTest {
            viewModel.saveScrollPosition(index = 7, offset = 120)

            assertThat(viewModel.listScrollIndex).isEqualTo(7)
            assertThat(viewModel.listScrollOffset).isEqualTo(120)
        }

    @Test
    fun `unsubscribe is a no-op until a channel is loaded`() =
        runTest {
            viewModel.unsubscribe()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 0) { subscriptionRepository.unsubscribe(any()) }
        }

    @Test
    fun `setNotificationState is a no-op until a channel is loaded`() =
        runTest {
            viewModel.setNotificationState(true)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 0) { subscriptionRepository.updateNotificationState(any(), any()) }
        }
}
