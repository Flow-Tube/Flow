package io.github.aedev.flow.data.subscriptions

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import org.junit.Test

/** #1094: what a refresh writes back when some channels could not be read. */
class SubscriptionFeedWriteTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun video(
        id: String,
        channelId: String,
        ageHours: Long = 1L,
        duration: Int = 0,
        isShort: Boolean = false,
    ) = Video(
        id = id,
        title = id,
        channelName = channelId,
        channelId = channelId,
        thumbnailUrl = "",
        duration = duration,
        viewCount = 0L,
        uploadDate = "",
        timestamp = now - ageHours * hour,
        isShort = isShort,
    )

    private fun write(
        channels: List<String>,
        fresh: List<Video>,
        cached: List<Video>,
        failed: Set<String> = emptySet(),
        subscribed: Set<String> = setOf("UCa", "UCb", "UCc"),
        maxPerChannel: Int = 60,
        reelIds: Set<String> = emptySet(),
    ) = subscriptionFeedWrite(
        channelIds = channels,
        freshVideos = fresh,
        cachedSlice = cached,
        failedChannelIds = failed,
        subscribedChannelIds = subscribed,
        now = now,
        windowMs = 60L * 24L * hour,
        maxPerChannel = maxPerChannel,
        reelIds = reelIds,
    )

    private val both = listOf("UCa", "UCb")

    @Test
    fun `a channel that failed keeps its rows`() {
        val result =
            write(
                channels = both,
                fresh = listOf(video("a2", "UCa")),
                cached = listOf(video("a1", "UCa", ageHours = 5), video("b1", "UCb", ageHours = 3)),
                failed = setOf("UCb"),
            )

        assertThat(result.rows.map { it.id }).containsExactly("a2", "b1", "a1").inOrder()
    }

    @Test
    fun `a channel only RSS answered for keeps its fresh rows and its earlier ones (1186)`() {
        val result =
            write(
                channels = both,
                fresh = listOf(video("b2", "UCb")),
                cached = listOf(video("b1", "UCb", ageHours = 30)),
                failed = setOf("UCb"),
            )

        assertThat(result.rows.map { it.id }).containsExactly("b2", "b1").inOrder()
        assertThat(result.fetchedChannelIds).containsExactly("UCa")
    }

    @Test
    fun `a channel that answered keeps the uploads RSS no longer lists (1099)`() {
        val result =
            write(
                channels = both,
                fresh = listOf(video("a2", "UCa")),
                cached = listOf(video("a1", "UCa", ageHours = 24 * 20)),
            )

        assertThat(result.rows.map { it.id }).containsExactly("a2", "a1").inOrder()
    }

    @Test
    fun `rows past the lookback window still age out`() {
        val result =
            write(
                channels = both,
                fresh = listOf(video("a2", "UCa")),
                cached = listOf(video("a1", "UCa", ageHours = 24 * 61)),
            )

        assertThat(result.rows.map { it.id }).containsExactly("a2")
    }

    @Test
    fun `each channel keeps only its newest rows`() {
        val result =
            write(
                channels = both,
                fresh = listOf(video("a3", "UCa", ageHours = 1), video("b1", "UCb", ageHours = 9)),
                cached = listOf(video("a2", "UCa", ageHours = 2), video("a1", "UCa", ageHours = 3)),
                maxPerChannel = 2,
            )

        assertThat(result.rows.map { it.id }).containsExactly("a3", "a2", "b1").inOrder()
    }

    @Test
    fun `a failed channel is not stamped fresh`() {
        val result = write(channels = both, fresh = emptyList(), cached = emptyList(), failed = setOf("UCb"))

        assertThat(result.fetchedChannelIds).containsExactly("UCa")
    }

    @Test
    fun `an incremental refresh keeps the fetched channels' earlier rows`() {
        val result =
            write(
                channels = listOf("UCa"),
                fresh = listOf(video("a2", "UCa")),
                cached = listOf(video("a1", "UCa", ageHours = 5)),
            )

        assertThat(result.rows.map { it.id }).containsExactly("a2", "a1").inOrder()
        assertThat(result.fetchedChannelIds).containsExactly("UCa")
    }

    @Test
    fun `a channel unsubscribed during the refresh is not written back`() {
        val result =
            write(
                channels = both,
                fresh = listOf(video("a2", "UCa"), video("b2", "UCb")),
                cached = emptyList(),
                subscribed = setOf("UCa"),
            )

        assertThat(result.rows.map { it.id }).containsExactly("a2")
    }

    @Test
    fun `an enriched duration survives the refresh that re-reads the row from RSS`() {
        val result =
            write(
                channels = both,
                fresh = listOf(video("a1", "UCa")),
                cached = listOf(video("a1", "UCa", duration = 321)),
            )

        assertThat(result.rows.single().duration).isEqualTo(321)
    }

    @Test
    fun `a cached reel kept as a video is filed as a Short when the pass knows it is a reel (1393)`() {
        val result =
            write(
                channels = listOf("UCa"),
                fresh = listOf(video("newest", "UCa", isShort = true)),
                cached = listOf(video("older", "UCa", ageHours = 5), video("upload", "UCa", ageHours = 6)),
                reelIds = setOf("newest", "older"),
            )

        assertThat(result.rows.associate { it.id to it.isShort })
            .containsExactly("newest", true, "older", true, "upload", false)
    }

    @Test
    fun `a cached Short is never turned back into a video`() {
        val result =
            write(
                channels = listOf("UCa"),
                fresh = emptyList(),
                cached = listOf(video("reel", "UCa", isShort = true)),
            )

        assertThat(result.rows.single().isShort).isTrue()
    }
}
