package io.github.aedev.flow.data.subscriptions

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import org.junit.Test

/** The Quick access dot on channels with videos the viewer hasn't seen yet (#1755). */
class SubscriptionNewUploadsTest {
    private fun video(
        id: String,
        channelId: String,
        timestamp: Long,
        isUpcoming: Boolean = false,
    ) = Video(
        id = id,
        title = id,
        channelName = channelId,
        channelId = channelId,
        thumbnailUrl = "",
        duration = 0,
        viewCount = 0L,
        uploadDate = "",
        timestamp = timestamp,
        isUpcoming = isUpcoming,
    )

    @Test
    fun `an upload after the start marks its channel`() {
        val videos = listOf(video("v1", "a", timestamp = 200L), video("v2", "b", timestamp = 50L))

        assertThat(channelsWithNewUploads(videos, emptyMap(), since = 100L, startedIds = emptySet())).containsExactly("a")
    }

    @Test
    fun `opening the channel after the upload clears it`() {
        val videos = listOf(video("v1", "a", timestamp = 200L))

        assertThat(channelsWithNewUploads(videos, mapOf("a" to 300L), since = 100L, startedIds = emptySet())).isEmpty()
        assertThat(channelsWithNewUploads(videos, mapOf("a" to 150L), since = 100L, startedIds = emptySet())).containsExactly("a")
    }

    @Test
    fun `a started video and a premiere that hasn't begun don't count`() {
        val videos = listOf(video("v1", "a", timestamp = 200L), video("v2", "b", timestamp = 200L, isUpcoming = true))

        assertThat(channelsWithNewUploads(videos, emptyMap(), since = 100L, startedIds = setOf("v1"))).isEmpty()
    }

    @Test
    fun `nothing lights before the dots have a start time`() {
        val videos = listOf(video("v1", "a", timestamp = 200L))

        assertThat(channelsWithNewUploads(videos, emptyMap(), since = 0L, startedIds = emptySet())).isEmpty()
    }

    @Test
    fun `marking a channel seen keeps only subscribed channels`() {
        val stored = markChannelSeen("a=10,gone=20", channelId = "b", now = 30L, subscribed = setOf("a", "b"))

        assertThat(parseChannelSeenAt(stored)).containsExactly("a", 10L, "b", 30L)
    }

    @Test
    fun `a malformed entry is skipped`() {
        assertThat(parseChannelSeenAt("a=10,broken,=5,c=x")).containsExactly("a", 10L)
    }
}
