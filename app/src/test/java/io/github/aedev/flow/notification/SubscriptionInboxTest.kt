package io.github.aedev.flow.notification

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import org.junit.Test

class SubscriptionInboxTest {
    private val now = 1_800_000_000_000L
    private val hour = 60L * 60L * 1000L
    private val followed = mapOf("UCa" to 0L, "UCb" to 0L)

    private fun video(
        id: String,
        hoursAgo: Long,
        channelId: String = "UCa",
        isShort: Boolean = false,
        isUpcoming: Boolean = false,
    ) = Video(
        id = id,
        title = id,
        channelName = channelId,
        channelId = channelId,
        thumbnailUrl = "",
        duration = 0,
        viewCount = 0,
        uploadDate = "",
        timestamp = now - hoursAgo * hour,
        isShort = isShort,
        isUpcoming = isUpcoming,
    )

    private fun select(
        written: List<Video>,
        cachedBefore: List<Video> = listOf(video("old", 100)),
        followedAt: Map<String, Long> = followed,
        sinceMs: Long = 0L,
        showShorts: Boolean = true,
        notifyShorts: Boolean = true,
    ) = selectInboxUploads(written, cachedBefore, followedAt, sinceMs, showShorts, notifyShorts, now).map { it.id }

    @Test
    fun `only uploads the cache did not hold before reach the inbox`() {
        val cached = listOf(video("old", 100), video("seen", 3))

        assertThat(select(cached + video("new", 2), cachedBefore = cached)).containsExactly("new")
    }

    @Test
    fun `nothing older than the inbox start or the follow date`() {
        assertThat(select(listOf(video("a", 5), video("b", 1)), sinceMs = now - 3 * hour)).containsExactly("b")
        assertThat(select(listOf(video("a", 5), video("b", 1)), followedAt = mapOf("UCa" to now - 3 * hour))).containsExactly("b")
    }

    @Test
    fun `a week is the furthest back`() {
        assertThat(select(listOf(video("a", 7 * 24 + 1), video("b", 6 * 24)))).containsExactly("b")
    }

    @Test
    fun `a channel the cache knew nothing of adds only its last day`() {
        val written = listOf(video("a", 30, channelId = "UCb"), video("b", 20, channelId = "UCb"))

        assertThat(select(written)).containsExactly("b")
    }

    @Test
    fun `unfollowed channels and premieres never qualify`() {
        val written = listOf(video("gone", 1, channelId = "UCx"), video("soon", -2, isUpcoming = true), video("ok", 1))

        assertThat(select(written)).containsExactly("ok")
    }

    @Test
    fun `Shorts follow the Shorts notification rule`() {
        val written = listOf(video("reel", 1, isShort = true), video("upload", 1))

        assertThat(select(written)).containsExactly("reel", "upload")
        assertThat(select(written, notifyShorts = false)).containsExactly("upload")
        assertThat(select(written, showShorts = false)).containsExactly("upload")
    }
}
