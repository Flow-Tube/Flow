package io.github.aedev.flow.ui.screens.notifications

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.entity.NotificationEntity
import org.junit.Test

class NotificationChannelGroupingTest {
    private fun upload(
        id: Int,
        channel: String,
        type: String = "NEW_VIDEO",
    ) = NotificationEntity(id = id, videoId = "v$id", title = "t$id", channelName = channel, thumbnailUrl = null, type = type)

    @Test
    fun `a channel's uploads become one row where its newest was`() {
        val rows = groupByChannel(NotificationBucket.TODAY, listOf(upload(1, "a"), upload(2, "b"), upload(3, "a")))

        assertThat(rows).hasSize(2)
        val channel = rows[0] as InboxRow.Channel
        assertThat(channel.channelName).isEqualTo("a")
        assertThat(channel.notifications.map { it.id }).containsExactly(1, 3).inOrder()
        assertThat((rows[1] as InboxRow.Single).notification.id).isEqualTo(2)
    }

    @Test
    fun `a channel with one upload stays a single row`() {
        val rows = groupByChannel(NotificationBucket.TODAY, listOf(upload(1, "a"), upload(2, "b")))

        assertThat(rows.map { it::class }).containsExactly(InboxRow.Single::class, InboxRow.Single::class)
    }

    @Test
    fun `other notification types and nameless channels never group`() {
        val rows =
            groupByChannel(
                NotificationBucket.TODAY,
                listOf(upload(1, "a", type = "GENERAL"), upload(2, "a", type = "GENERAL"), upload(3, ""), upload(4, "")),
            )

        assertThat(rows.all { it is InboxRow.Single }).isTrue()
        assertThat(rows).hasSize(4)
    }

    @Test
    fun `an open channel row lists its uploads under it`() {
        val rows = groupByChannel(NotificationBucket.TODAY, listOf(upload(1, "a"), upload(2, "a"), upload(3, "b")))
        val key = rows.first().key

        val closed = inboxLines(rows, expanded = emptySet())
        val open = inboxLines(rows, expanded = setOf(key))

        assertThat(closed.map { it.key }).containsExactly(key, "n3").inOrder()
        assertThat(open).hasSize(4)
        assertThat((open[0] as InboxLine.Header).expanded).isTrue()
        assertThat(open.drop(1).map { (it as InboxLine.Item).notification.id }).containsExactly(1, 2, 3).inOrder()
        assertThat(open.map { it.key }.toSet()).hasSize(4)
    }

    @Test
    fun `the same channel groups separately per section`() {
        val today = groupByChannel(NotificationBucket.TODAY, listOf(upload(1, "a"), upload(2, "a")))
        val yesterday = groupByChannel(NotificationBucket.YESTERDAY, listOf(upload(3, "a"), upload(4, "a")))

        assertThat(today.single().key).isNotEqualTo(yesterday.single().key)
    }
}
