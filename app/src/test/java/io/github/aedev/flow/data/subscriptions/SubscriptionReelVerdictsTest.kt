package io.github.aedev.flow.data.subscriptions

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.entity.SubscriptionFeedEntity
import io.github.aedev.flow.data.model.Video
import org.junit.Test

/** A reel verdict is reused only when a pass that read its channel in full stored it. */
class SubscriptionReelVerdictsTest {
    private fun row(
        id: String,
        channelId: String,
        cachedAt: Long,
        isShort: Boolean = false,
    ) = SubscriptionFeedEntity(
        videoId = id,
        title = id,
        channelName = channelId,
        channelId = channelId,
        thumbnailUrl = "",
        duration = 0,
        viewCount = 0L,
        uploadDate = "",
        timestamp = 0L,
        channelThumbnailUrl = "",
        isShort = isShort,
        cachedAt = cachedAt,
    )

    @Test
    fun `rows written by the pass that stamped their channel are trusted`() {
        val verdicts =
            trustedReelVerdicts(
                rows = listOf(row("reel", "UCa", cachedAt = 100L, isShort = true), row("video", "UCa", cachedAt = 100L)),
                lastFeedFetchAt = mapOf("UCa" to 100L),
            )

        assertThat(verdicts).containsExactly("reel", true, "video", false)
    }

    @Test
    fun `rows cached after the channel's last full read are asked about again`() {
        val verdicts =
            trustedReelVerdicts(
                rows = listOf(row("unclassified", "UCa", cachedAt = 200L), row("older", "UCa", cachedAt = 50L)),
                lastFeedFetchAt = mapOf("UCa" to 100L),
            )

        assertThat(verdicts).containsExactly("older", false)
    }

    @Test
    fun `a channel never read in full has no trusted verdicts`() {
        assertThat(trustedReelVerdicts(listOf(row("seeded", "UCb", cachedAt = 10L)), emptyMap())).isEmpty()
    }

    private fun video(id: String) =
        Video(
            id = id,
            title = id,
            channelName = "UCa",
            channelId = "UCa",
            thumbnailUrl = "",
            duration = 0,
            viewCount = 0L,
            uploadDate = "",
        )

    @Test
    fun `link verdicts classify the slice without a lookup`() {
        val classified =
            classifiedFromVerdicts(
                videos = listOf(video("reel"), video("video")),
                linkVerdicts = mapOf("reel" to true, "video" to false),
                storedVerdicts = emptyMap(),
            )

        assertThat(classified?.map { it.id to it.isShort }).containsExactly("reel" to true, "video" to false).inOrder()
    }

    @Test
    fun `a link verdict repairs a stored one`() {
        val classified =
            classifiedFromVerdicts(
                videos = listOf(video("reel")),
                linkVerdicts = mapOf("reel" to true),
                storedVerdicts = mapOf("reel" to false),
            )

        assertThat(classified?.single()?.isShort).isTrue()
    }

    @Test
    fun `a stored verdict fills an entry the link leaves open`() {
        val classified =
            classifiedFromVerdicts(
                videos = listOf(video("reel"), video("old")),
                linkVerdicts = mapOf("reel" to true),
                storedVerdicts = mapOf("old" to false),
            )

        assertThat(classified?.map { it.isShort }).containsExactly(true, false).inOrder()
    }

    @Test
    fun `an entry with no verdict at all leaves the slice for a lookup`() {
        val classified =
            classifiedFromVerdicts(
                videos = listOf(video("reel"), video("unknown")),
                linkVerdicts = mapOf("reel" to true),
                storedVerdicts = emptyMap(),
            )

        assertThat(classified).isNull()
    }
}
