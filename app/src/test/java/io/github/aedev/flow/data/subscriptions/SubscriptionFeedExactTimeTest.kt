package io.github.aedev.flow.data.subscriptions

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import org.junit.Test

/** The exact-time flag travels with the timestamp it describes, and only with that one. */
class SubscriptionFeedExactTimeTest {
    private val now = 1_791_331_200_000L
    private val dayMs = 86_400_000L

    private fun video(
        timestamp: Long,
        uploadDate: String,
        exact: Boolean,
    ) = Video(
        id = "v1",
        title = "t",
        channelName = "c",
        channelId = "UCc",
        thumbnailUrl = "",
        duration = 0,
        viewCount = 0,
        uploadDate = uploadDate,
        timestamp = timestamp,
        timestampIsExact = exact,
    )

    @Test
    fun `an RSS publish time stays exact through the merge`() {
        val rss = video(now - 400 * dayMs, "1 year ago", exact = true)
        val tab = video(now - 365 * dayMs, "1 year ago", exact = false)

        val merged = SubscriptionFeedMerger.mergeDuplicates(listOf(rss, tab), now)

        assertThat(merged.timestamp).isEqualTo(rss.timestamp)
        assertThat(merged.timestampIsExact).isTrue()
    }

    @Test
    fun `a premiere keeps its start time when RSS reports its creation time`() {
        val scheduled = video(now + 7 * 3_600_000L, "Premieres in 7 hours", exact = false).copy(isUpcoming = true)
        val rss = video(now - 14 * 3_600_000L, "14 hours ago", exact = true)

        val merged = SubscriptionFeedMerger.mergeDuplicates(listOf(rss, scheduled), now)

        assertThat(merged.isUpcoming).isTrue()
        assertThat(merged.timestamp).isEqualTo(scheduled.timestamp)
        assertThat(merged.uploadDate).isEqualTo("Premieres in 7 hours")
    }

    @Test
    fun `a placeholder replaced by the relative text is no longer exact`() {
        val placeholder = video(now, "1 day ago", exact = true)

        val merged = SubscriptionFeedMerger.mergeDuplicates(listOf(placeholder), now)

        assertThat(merged.timestamp).isEqualTo(now - dayMs)
        assertThat(merged.timestampIsExact).isFalse()
    }
}
