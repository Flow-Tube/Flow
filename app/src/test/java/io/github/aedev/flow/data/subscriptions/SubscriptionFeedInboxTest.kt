package io.github.aedev.flow.data.subscriptions

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.AppDatabase
import io.github.aedev.flow.data.local.ChannelSubscription
import io.github.aedev.flow.data.local.NotificationRepository
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.local.dao.CacheDao
import io.github.aedev.flow.data.local.entity.NotificationEntity
import io.github.aedev.flow.data.local.entity.SubscriptionFeedEntity
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.notification.SubscriptionInbox
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Home's channel-tab top-up and the background check write to the feed cache before a refresh does.
 * Whatever they add is cached by the time the refresh runs, so they must offer it to the inbox too.
 */
class SubscriptionFeedInboxTest {
    private val now = System.currentTimeMillis()
    private val hour = 60L * 60L * 1000L
    private val bbc = "UC16niRr50-MSBwiO3YDb3RA"

    private val cache = MutableStateFlow<List<SubscriptionFeedEntity>>(emptyList())
    private val cacheDao: CacheDao =
        mockk(relaxed = true) {
            every { getSubscriptionFeed() } returns cache
            coEvery { getSubscriptionFeedForChannels(any()) } answers { cache.value.filter { it.channelId in firstArg<List<String>>() } }
            coEvery { insertSubscriptionFeedIfAbsent(any()) } answers {
                val known = cache.value.mapTo(HashSet()) { it.videoId }
                cache.value = cache.value + firstArg<List<SubscriptionFeedEntity>>().filter { it.videoId !in known }
            }
        }
    private val preferences: PlayerPreferences =
        mockk(relaxed = true) {
            every { subscriptionCollaborationsEnabled } returns flowOf(true)
            every { effectiveSubscriptionShowShorts } returns flowOf(true)
            every { notifShortsEnabled } returns flowOf(true)
            coEvery { inboxFeedSince(any()) } answers { firstArg() }
        }
    private val followed = ChannelSubscription(bbc, "BBC News", "", subscribedAt = now - 30 * 24 * hour)
    private val subscriptions: SubscriptionRepository =
        mockk(relaxed = true) {
            every { getAllSubscriptions() } returns flowOf(listOf(followed))
        }
    private val notifications: NotificationRepository = mockk(relaxed = true)
    private val repository =
        SubscriptionFeedRepository(
            subscriptionRepository = subscriptions,
            rssSubscriptionService = mockk(relaxed = true),
            cacheDao = cacheDao,
            database = mockk<AppDatabase>(relaxed = true),
            playerPreferences = preferences,
            collabIndex = mockk(relaxed = true),
            subscriptionGroupDao = mockk(relaxed = true),
            inbox = SubscriptionInbox(notifications, preferences, subscriptions),
        )

    private fun upload(
        id: String,
        hoursAgo: Long,
    ) = Video(
        id = id,
        title = id,
        channelName = "BBC News",
        channelId = bbc,
        thumbnailUrl = "",
        duration = 120,
        viewCount = 10,
        uploadDate = "$hoursAgo hours ago",
        timestamp = now - hoursAgo * hour,
    )

    @Test
    fun `uploads Home adds to an empty cache reach the inbox`() =
        runTest {
            val recorded = slot<List<NotificationEntity>>()
            coEvery { notifications.recordNew(capture(recorded)) } returns emptyList()

            repository.mergeChannelTabUploads(listOf(upload("evening", 1), upload("noon", 10), upload("lastWeek", 9 * 24)))

            assertThat(recorded.captured.map { it.videoId }).containsExactly("evening", "noon")
            assertThat(cache.value.map { it.videoId }).containsExactly("evening", "noon", "lastWeek")
        }

    @Test
    fun `an upload already cached is not offered again by a later writer`() =
        runTest {
            repository.mergeChannelTabUploads(listOf(upload("evening", 1)))
            repository.seedFromNotificationCheck(
                channelId = bbc,
                channelName = "BBC News",
                entries = listOf(ChannelRssEntry("evening", "evening", null, publishedAtMillis = now - hour)),
            )

            coVerify(exactly = 1) { notifications.recordNew(any()) }
        }

    @Test
    fun `uploads the background check seeds reach the inbox`() =
        runTest {
            val recorded = slot<List<NotificationEntity>>()
            coEvery { notifications.recordNew(capture(recorded)) } returns emptyList()

            repository.seedFromNotificationCheck(
                channelId = bbc,
                channelName = "BBC News",
                entries = listOf(ChannelRssEntry("seeded", "seeded", null, publishedAtMillis = now - 2 * hour)),
            )

            assertThat(recorded.captured.map { it.videoId }).containsExactly("seeded")
            assertThat(recorded.captured.single().timestamp).isEqualTo(now - 2 * hour)
        }
}
