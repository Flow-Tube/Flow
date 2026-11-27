package io.github.aedev.flow.notification

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.entity.NotificationEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SubscriptionNotificationsTest {
    private val videos =
        listOf(
            SubscriptionNotifications.NewVideoEntry("Channel", "First", "a", "https://i/a.jpg"),
            SubscriptionNotifications.NewVideoEntry("Channel", "Second", "b", null),
        )

    @Test
    fun `the inbox fills without the notification permission`() =
        runTest {
            val recorded = mutableListOf<NotificationEntity>()
            var posted = false

            SubscriptionNotifications.deliver(videos, { recorded += it }, mayNotify = { false }, notify = { posted = true })

            assertThat(recorded.map { it.videoId }).containsExactly("a", "b").inOrder()
            assertThat(recorded.first().title).isEqualTo("First")
            assertThat(recorded.first().thumbnailUrl).isEqualTo("https://i/a.jpg")
            assertThat(posted).isFalse()
        }

    @Test
    fun `system notifications go out after the inbox is written`() =
        runTest {
            val order = mutableListOf<String>()

            SubscriptionNotifications.deliver(videos, { order += "record" }, mayNotify = { true }, notify = { order += "notify" })

            assertThat(order).containsExactly("record", "notify").inOrder()
        }

    @Test
    fun `no videos touches neither`() =
        runTest {
            var touched = false

            SubscriptionNotifications.deliver(emptyList(), { touched = true }, mayNotify = { true }, notify = { touched = true })

            assertThat(touched).isFalse()
        }
}
