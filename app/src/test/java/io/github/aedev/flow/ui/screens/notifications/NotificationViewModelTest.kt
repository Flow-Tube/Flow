package io.github.aedev.flow.ui.screens.notifications

import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import io.github.aedev.flow.data.engagement.VideoFeedbackUseCase
import io.github.aedev.flow.data.local.NotificationRepository
import io.github.aedev.flow.data.local.entity.NotificationEntity
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.subscriptions.SubscriptionFeedRepository
import io.github.aedev.flow.data.subscriptions.SubscriptionRefreshPlan
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationViewModelTest {
    private val unread = NotificationEntity(id = 1, videoId = "a", title = "a", channelName = "c", thumbnailUrl = null, isRead = false)
    private val read = NotificationEntity(id = 2, videoId = "b", title = "b", channelName = "c", thumbnailUrl = null, isRead = true)
    private val repository: NotificationRepository = mockk(relaxed = true)
    private val feed: SubscriptionFeedRepository = mockk()
    private val plan: SubscriptionRefreshPlan = mockk()
    private val feedback: VideoFeedbackUseCase = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { repository.allNotifications } returns flowOf(listOf(unread, read))
        every { repository.unreadCount } returns flowOf(1)
        coEvery { feed.planAllRefresh(force = false) } returns plan
        every { feed.refresh(plan) } returns emptyFlow()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `opening the inbox keeps what was unread as new and clears the badge once`() {
        val viewModel = viewModel()

        viewModel.openInbox()
        viewModel.openInbox()

        assertThat(viewModel.newIds.value).containsExactly(1)
        coVerify(exactly = 1) { repository.markAllAsRead() }
    }

    @Test
    fun `opening the inbox refreshes the feed only if it is due, once`() {
        val viewModel = viewModel()

        viewModel.openInbox()
        viewModel.openInbox()

        coVerify(exactly = 1) { feed.planAllRefresh(force = false) }
        coVerify(exactly = 1) { feed.refresh(plan) }
    }

    @Test
    fun `a failed feed refresh leaves the inbox working`() {
        coEvery { feed.planAllRefresh(force = false) } throws IllegalStateException("offline")
        val viewModel = viewModel()

        viewModel.openInbox()

        assertThat(viewModel.newIds.value).containsExactly(1)
    }

    @Test
    fun `a channel row is removed in one write and undo restores every video`() {
        val viewModel = viewModel()

        assertThat(viewModel.deleteNotifications(listOf(unread, read))).isTrue()
        viewModel.undoDelete()

        coVerify(exactly = 1) { repository.deleteNotifications(listOf(unread, read)) }
        coVerify(exactly = 1) { repository.restoreNotifications(listOf(unread.copy(isRead = true), read.copy(isRead = true))) }
    }

    @Test
    fun `a repeated dismiss of part of the removed group neither deletes again nor shrinks the undo`() {
        val viewModel = viewModel()

        viewModel.deleteNotifications(listOf(unread, read))
        val again = viewModel.deleteNotifications(listOf(read))
        viewModel.undoDelete()

        assertThat(again).isFalse()
        coVerify(exactly = 1) { repository.deleteNotifications(any()) }
        coVerify(exactly = 1) { repository.restoreNotifications(listOf(unread.copy(isRead = true), read.copy(isRead = true))) }
    }

    @Test
    fun `the badge instance never marks anything read`() {
        viewModel()

        coVerify(exactly = 0) { repository.markAllAsRead() }
    }

    @Test
    fun `undo puts the removed notification back as read`() {
        val viewModel = viewModel()

        viewModel.deleteNotifications(listOf(unread))
        viewModel.undoDelete()
        viewModel.undoDelete()

        coVerify(exactly = 1) { repository.deleteNotifications(listOf(unread)) }
        coVerify(exactly = 1) { repository.restoreNotifications(listOf(unread.copy(isRead = true))) }
    }

    @Test
    fun `saving uses the feed's copy of the video and undo takes it out again`() {
        val cached =
            Video(
                id = "a",
                title = "a",
                channelName = "c",
                channelId = "UCc",
                thumbnailUrl = "",
                duration = 600,
                viewCount = 9,
                uploadDate = "",
            )
        coEvery { feed.cachedVideo("a") } returns cached
        coEvery { feedback.saveToWatchLater(cached) } returns true
        val results = mutableListOf<Boolean>()
        val viewModel = viewModel()

        viewModel.saveToWatchLater(unread) { results += it }
        viewModel.undoSave()
        viewModel.undoSave()

        assertThat(results).containsExactly(true)
        coVerify(exactly = 1) { feedback.setWatchLater(cached, saved = false) }
    }

    @Test
    fun `a video already in Watch later is reported and never taken out by undo`() {
        coEvery { feed.cachedVideo("b") } returns null
        coEvery { feedback.saveToWatchLater(any()) } returns false
        val results = mutableListOf<Boolean>()
        val viewModel = viewModel()

        viewModel.saveToWatchLater(read) { results += it }
        viewModel.undoSave()

        assertThat(results).containsExactly(false)
        coVerify { feedback.saveToWatchLater(match { it.id == "b" && it.title == "b" && it.channelName == "c" }) }
        coVerify(exactly = 0) { feedback.setWatchLater(any(), any()) }
    }

    private fun viewModel() = NotificationViewModel(repository, Lazy { feed }, Lazy { feedback })
}
