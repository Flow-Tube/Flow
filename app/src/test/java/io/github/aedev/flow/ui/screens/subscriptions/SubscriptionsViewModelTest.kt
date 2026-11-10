package io.github.aedev.flow.ui.screens.subscriptions

import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.AppDatabase
import io.github.aedev.flow.data.local.ChannelSubscription
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.local.dao.SubscriptionGroupDao
import io.github.aedev.flow.data.local.entity.SubscriptionGroupEntity
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FeedExclusions
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.subscriptions.SubscriptionFeedRefreshProgress
import io.github.aedev.flow.data.subscriptions.SubscriptionFeedRepository
import io.github.aedev.flow.data.subscriptions.SubscriptionRefreshPlan
import io.github.aedev.flow.data.subscriptions.SubscriptionWatchedVideos
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private val subscriptionRepository: SubscriptionRepository = mockk(relaxed = true)
    private val viewHistory: ViewHistory = mockk(relaxed = true)
    private val subscriptionFeedRepository: SubscriptionFeedRepository = mockk(relaxed = true)
    private val database: AppDatabase = mockk(relaxed = true)
    private val playerPreferences: PlayerPreferences = mockk(relaxed = true)
    private val subscriptionGroupDao: SubscriptionGroupDao = mockk(relaxed = true)
    private val neuroEngine: FlowNeuroEngine = mockk(relaxed = true)

    private lateinit var viewModel: SubscriptionsViewModel
    private val viewModelStore = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { subscriptionGroupDao.getAllGroups() } returns flowOf(emptyList())
        coEvery { playerPreferences.shortsShelfEnabled } returns flowOf(false)
        coEvery { playerPreferences.subscriptionShowVideos } returns flowOf(true)
        coEvery { playerPreferences.subscriptionShowShorts } returns flowOf(true)
        coEvery { playerPreferences.subscriptionShowLive } returns flowOf(true)
        coEvery { playerPreferences.subscriptionShortsExcludedChannels } returns flowOf(emptySet())
        coEvery { playerPreferences.subsFullWidthView } returns flowOf(false)
        coEvery { playerPreferences.subsSortMode } returns flowOf("DEFAULT")
        coEvery { playerPreferences.selectedSubscriptionGroup } returns flowOf(null)
        coEvery { playerPreferences.subscriptionLastRefreshTime } returns flowOf(0L)
        coEvery { playerPreferences.subscriptionLastRefreshedCount } returns flowOf(0)
        coEvery { playerPreferences.subscriptionShowCheckedVideoCount } returns flowOf(true)
        coEvery { viewHistory.getVideoWatchProgress() } returns flowOf(emptyList())
        coEvery { playerPreferences.hideWatchedVideosFromSubscriptions } returns flowOf(false)
        coEvery { playerPreferences.hideWatchedShorts } returns flowOf(true)
        coEvery { playerPreferences.watchedThreshold } returns flowOf(mockk(relaxed = true))
        coEvery { database.downloadDao().getVideoDownloads() } returns flowOf(emptyList())
        coEvery { playerPreferences.unplayableVideoIds } returns flowOf(emptySet())
        coEvery { playerPreferences.hideUnplayableVideosFromSubscriptions } returns flowOf(false)
        coEvery { subscriptionRepository.getAllSubscriptions() } returns flowOf(emptyList())
        coEvery { subscriptionFeedRepository.observeFeed(any()) } returns flowOf(emptyList())
        coEvery { subscriptionFeedRepository.planRefresh(any(), any()) } returns SubscriptionRefreshPlan.NOTHING_TO_DO
        coEvery { neuroEngine.feedExclusions(any()) } returns FeedExclusions.NONE

        viewModel =
            SubscriptionsViewModel(
                subscriptionRepository = subscriptionRepository,
                subscriptionFeedRepository = subscriptionFeedRepository,
                playerPreferences = playerPreferences,
                subscriptionGroupDao = subscriptionGroupDao,
                subscriptionWatchedVideos = SubscriptionWatchedVideos(viewHistory, playerPreferences, database),
                neuroEngine = neuroEngine,
                backupCoordinator = mockk(relaxed = true),
                watchHistoryDao = mockk(relaxed = true),
            )
        viewModelStore.put("subscriptions", viewModel)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        Dispatchers.resetMain()
        unmockkAll()
    }

    /**
     * Regression guard: the TV shell hoists this ViewModel at app launch, so merely constructing it
     * must not read preferences or kick off the subscription feed fetch. That work belongs to
     * [SubscriptionsViewModel.ensureStarted], which the screens call when the feed becomes visible.
     */
    @Test
    fun `construction does not start collectors or touch the feed`() =
        runTest {
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 0) { subscriptionRepository.getAllSubscriptions() }
            coVerify(exactly = 0) { subscriptionGroupDao.getAllGroups() }
            coVerify(exactly = 0) { subscriptionFeedRepository.observeFeed(any()) }
        }

    @Test
    fun `ensureStarted begins collecting subscriptions`() =
        runTest {
            viewModel.ensureStarted()

            coVerify(timeout = VERIFY_TIMEOUT_MS) { subscriptionRepository.getAllSubscriptions() }
            coVerify(timeout = VERIFY_TIMEOUT_MS) { subscriptionGroupDao.getAllGroups() }
        }

    @Test
    fun `ensureStarted is idempotent`() =
        runTest {
            viewModel.ensureStarted()
            coVerify(timeout = VERIFY_TIMEOUT_MS) { subscriptionGroupDao.getAllGroups() }

            viewModel.ensureStarted()
            viewModel.ensureStarted()

            coVerify(exactly = 1) { subscriptionGroupDao.getAllGroups() }
        }

    @Test
    fun `initial ui state has default properties`() =
        runTest {
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.isLoading).isFalse()
            assertThat(state.selectedGroupName).isNull()
            assertThat(state.sortMode).isEqualTo(SubscriptionSortMode.DEFAULT)
        }

    @Test
    fun `selectGroup updates selectedGroupName in state`() =
        runTest {
            viewModel.selectGroup("Tech")

            assertThat(viewModel.uiState.value.selectedGroupName).isEqualTo("Tech")
        }

    @Test
    fun `selectChannel updates selectedChannelId in state`() =
        runTest {
            viewModel.selectChannel("channel_123")

            assertThat(viewModel.uiState.value.selectedChannelId).isEqualTo("channel_123")
        }

    @Test
    fun `unsubscribe calls subscription repository`() =
        runTest {
            val channelId = "channel_abc"
            coEvery { subscriptionRepository.unsubscribe(channelId) } returns Unit

            viewModel.unsubscribe(channelId)

            coVerify(timeout = VERIFY_TIMEOUT_MS, exactly = 1) { subscriptionRepository.unsubscribe(channelId) }
        }

    @Test
    fun `updateNotificationState calls subscription repository`() =
        runTest {
            val channelId = "channel_xyz"
            coEvery { subscriptionRepository.updateNotificationState(channelId, true) } returns Unit

            viewModel.updateNotificationState(channelId, true)

            coVerify(timeout = VERIFY_TIMEOUT_MS, exactly = 1) { subscriptionRepository.updateNotificationState(channelId, true) }
        }

    @Test
    fun `unsubscribe and undo update a large channel list while the feed refresh is stalled`() =
        runTest {
            val original =
                List(373) { index ->
                    ChannelSubscription(channelId = "channel-$index", channelName = "Channel $index", channelThumbnail = "")
                }
            val subscriptions = MutableStateFlow(original)
            val refreshStarted = stallRefresh(subscriptions)
            val removed = original.first()
            coEvery { subscriptionRepository.unsubscribe(removed.channelId) } coAnswers {
                subscriptions.value = original.drop(1)
            }
            coEvery { subscriptionRepository.subscribe(removed) } coAnswers {
                subscriptions.value = original
            }
            viewModel.ensureStarted()
            awaitState { it.subscribedChannels.size == original.size }
            awaitRefresh(refreshStarted)

            viewModel.unsubscribe(removed.channelId)
            val afterRemoval = awaitState { it.subscribedChannels.size == original.size - 1 }
            assertThat(afterRemoval.subscribedChannels.map { it.id }).doesNotContain(removed.channelId)
            assertThat(afterRemoval.notificationStates).doesNotContainKey(removed.channelId)

            viewModel.subscribeChannel(removed)
            val restored = awaitState { it.subscribedChannels.size == original.size }
            assertThat(restored.subscribedChannels.first().id).isEqualTo(removed.channelId)
            coVerify(exactly = 1) { subscriptionFeedRepository.refresh(any()) }
        }

    @Test
    fun `notification and channel metadata changes publish without another feed fetch`() =
        runTest {
            val original = ChannelSubscription(channelId = "channel", channelName = "Original", channelThumbnail = "")
            val subscriptions = MutableStateFlow(listOf(original))
            val refreshStarted = stallRefresh(subscriptions)
            coEvery { subscriptionRepository.updateNotificationState(original.channelId, true) } coAnswers {
                subscriptions.value = listOf(original.copy(channelName = "Updated", isNotificationEnabled = true, isMusic = true))
            }
            viewModel.ensureStarted()
            awaitState { it.subscribedChannels.size == 1 }
            awaitRefresh(refreshStarted)

            viewModel.updateNotificationState(original.channelId, true)
            val updated = awaitState { it.subscribedChannels.single().name == "Updated" }
            assertThat(updated.subscribedChannels.single().isMusic).isTrue()
            assertThat(updated.notificationStates[original.channelId]).isTrue()
            coVerify(exactly = 1) { subscriptionFeedRepository.refresh(any()) }
        }

    @Test
    fun `successive notification changes do not wait behind a stalled feed refresh`() =
        runTest {
            val original = ChannelSubscription(channelId = "channel", channelName = "Channel", channelThumbnail = "")
            val subscriptions = MutableStateFlow(listOf(original))
            val refreshStarted = stallRefresh(subscriptions)
            val upstreamCollections = AtomicInteger()
            coEvery { subscriptionRepository.getAllSubscriptions() } returns subscriptions.onStart { upstreamCollections.incrementAndGet() }
            coEvery { subscriptionRepository.updateNotificationState(original.channelId, any()) } coAnswers {
                subscriptions.value = listOf(original.copy(isNotificationEnabled = secondArg()))
            }
            viewModel.ensureStarted()
            awaitState { it.subscribedChannels.size == 1 }
            awaitRefresh(refreshStarted)

            repeat(100) { index ->
                val enabled = index % 2 == 0
                viewModel.updateNotificationState(original.channelId, enabled)
                awaitState { it.notificationStates[original.channelId] == enabled }
            }

            assertThat(upstreamCollections.get()).isEqualTo(1)
            coVerify(exactly = 1) { subscriptionFeedRepository.refresh(any()) }
        }

    @Test
    fun `unsubscribing the last channel clears the list while the feed refresh is stalled`() =
        runTest {
            val original = ChannelSubscription(channelId = "channel", channelName = "Channel", channelThumbnail = "")
            val subscriptions = MutableStateFlow(listOf(original))
            val refreshStarted = stallRefresh(subscriptions)
            coEvery { subscriptionRepository.unsubscribe(original.channelId) } coAnswers {
                subscriptions.value = emptyList()
            }
            viewModel.ensureStarted()
            awaitState { it.subscribedChannels.size == 1 }
            awaitRefresh(refreshStarted)

            viewModel.unsubscribe(original.channelId)
            val empty = awaitState { it.subscribedChannels.isEmpty() }

            assertThat(empty.notificationStates).isEmpty()
            coVerify(exactly = 1) { subscriptionFeedRepository.refresh(any()) }
        }

    @Test
    fun `channel changes preserve progressive feed results and enrich their avatars`() =
        runTest {
            val original = ChannelSubscription(channelId = "channel", channelName = "Original", channelThumbnail = "")
            val subscriptions = MutableStateFlow(listOf(original))
            val refreshStarted = stallRefresh(subscriptions)
            val releasePreview = CompletableDeferred<Unit>()
            val cached =
                Video(
                    id = "cached",
                    title = "Cached video",
                    channelName = "Original",
                    channelId = original.channelId,
                    thumbnailUrl = "",
                    duration = 120,
                    viewCount = 0L,
                    uploadDate = "",
                )
            val preview = cached.copy(id = "preview", title = "New video")
            coEvery { subscriptionFeedRepository.observeFeed(any()) } returns flowOf(listOf(cached))
            coEvery { subscriptionFeedRepository.refresh(any()) } returns
                flow {
                    refreshStarted.complete(Unit)
                    releasePreview.await()
                    emit(
                        SubscriptionFeedRefreshProgress(
                            videos = listOf(preview),
                            failedChannelIds = emptySet(),
                            failedChannelReasons = emptyMap(),
                            processedChannels = 1,
                            totalChannels = 1,
                        ),
                    )
                    awaitCancellation()
                }
            viewModel.ensureStarted()
            awaitState { it.recentVideos.singleOrNull()?.id == cached.id }
            awaitRefresh(refreshStarted)
            releasePreview.complete(Unit)
            awaitState { it.recentVideos.singleOrNull()?.id == preview.id }

            subscriptions.value =
                listOf(original.copy(channelName = "Updated", channelThumbnail = "https://yt3.googleusercontent.com/avatar"))
            val updated =
                awaitState {
                    it.subscribedChannels.single().name == "Updated" &&
                        it.recentVideos
                            .singleOrNull()
                            ?.channelThumbnailUrl
                            ?.isNotBlank() == true
                }

            assertThat(updated.recentVideos.single().id).isEqualTo(preview.id)
            coVerify(exactly = 1) { subscriptionFeedRepository.refresh(any()) }
        }

    @Test
    fun `with a group selected a pull refreshes only that group's channels (1099)`() =
        runTest {
            coEvery { subscriptionGroupDao.getAllGroups() } returns
                flowOf(listOf(SubscriptionGroupEntity(name = "Gym", channelIds = "a,b")))
            coEvery { playerPreferences.selectedSubscriptionGroup } returns flowOf("Gym")
            viewModel.ensureStarted()
            awaitState { it.groups.isNotEmpty() && it.selectedGroupName == "Gym" }

            viewModel.refreshFeed()

            withContext(Dispatchers.Default) {
                withTimeout(VERIFY_TIMEOUT_MS) {
                    while (true) {
                        val asked = runCatching { coVerify { subscriptionFeedRepository.planRefresh(true, setOf("a", "b")) } }
                        if (asked.isSuccess) break
                        delay(20)
                    }
                }
            }
            coVerify(exactly = 0) { subscriptionFeedRepository.planRefresh(true, null) }
        }

    private fun stallRefresh(subscriptions: MutableStateFlow<List<ChannelSubscription>>): CompletableDeferred<Unit> {
        val started = CompletableDeferred<Unit>()
        val plan = SubscriptionRefreshPlan(subscriptions.value.map { it.channelId }, isFullRefresh = true)
        coEvery { subscriptionRepository.getAllSubscriptions() } returns subscriptions
        coEvery { subscriptionFeedRepository.planRefresh(false, null) } returns plan
        coEvery { subscriptionFeedRepository.refresh(plan) } returns
            flow {
                started.complete(Unit)
                awaitCancellation()
            }
        return started
    }

    private suspend fun awaitRefresh(started: CompletableDeferred<Unit>) =
        withContext(Dispatchers.Default) { withTimeout(VERIFY_TIMEOUT_MS) { started.await() } }

    private suspend fun awaitState(predicate: (SubscriptionsUiState) -> Boolean): SubscriptionsUiState =
        withContext(Dispatchers.Default) { withTimeout(VERIFY_TIMEOUT_MS) { viewModel.uiState.first(predicate) } }

    private companion object {
        /**
         * The ViewModel launches on [io.github.aedev.flow.utils.PerformanceDispatcher] rather than
         * on an injected dispatcher, so the test scheduler cannot join those coroutines. Verify
         * with a timeout instead of racing them.
         */
        const val VERIFY_TIMEOUT_MS = 2_000L
    }
}
