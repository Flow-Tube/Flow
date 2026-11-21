package io.github.aedev.flow.data.engagement

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.engagement.FeedInvalidationBus.Event
import io.github.aedev.flow.data.local.VideoHistoryEntry
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.paging.SearchResultItem
import io.github.aedev.flow.data.paging.isHiddenBy
import io.github.aedev.flow.data.paging.withoutHidden
import io.github.aedev.flow.innertube.pages.renderer.FeedItem
import io.github.aedev.flow.ui.screens.history.withoutHidden
import org.junit.Test

class HiddenContentTest {
    private fun video(
        id: String,
        channelId: String = "UCother",
    ) = Video(
        id = id,
        title = id,
        channelName = "",
        channelId = channelId,
        thumbnailUrl = "",
        duration = 60,
        viewCount = 1,
        uploadDate = "",
    )

    private fun hiddenAfter(vararg events: Event) = events.fold(HiddenContent(), HiddenContent::after)

    @Test
    fun `not interested hides that video only`() {
        val hidden = hiddenAfter(Event.NotInterested(videoId = "v1", channelId = "UCa"))

        assertThat(hidden.hides(video("v1", "UCa"))).isTrue()
        assertThat(hidden.hides(video("v2", "UCa"))).isFalse()
    }

    @Test
    fun `a blocked channel hides all of its videos`() {
        val hidden = hiddenAfter(Event.ChannelBlocked(channelId = "UCa", videoId = "v1"))

        assertThat(hidden.hides(video("v9", "UCa"))).isTrue()
        assertThat(hidden.hides(video("v9", "UCb"))).isFalse()
    }

    @Test
    fun `watched marks and unsubscribes hide nothing`() {
        val hidden = hiddenAfter(Event.MarkedWatched("v1"), Event.ChannelUnsubscribed("UCa"))

        assertThat(hidden.isEmpty).isTrue()
    }

    @Test
    fun `a blank id never hides every channel-less video`() {
        val hidden = hiddenAfter(Event.ChannelBlocked(channelId = "", videoId = ""))

        assertThat(hidden.hides(video("v1", ""))).isFalse()
    }

    @Test
    fun `search drops hidden videos and trims shelves by the load's own rules`() {
        val hidden = hiddenAfter(Event.NotInterested(videoId = "v1", channelId = "UCa"))
        val results = listOf(SearchResultItem.VideoResult(video("v1")), SearchResultItem.VideoResult(video("v2")))

        assertThat(results.withoutHidden(hidden.asExclusions()).map { (it as SearchResultItem.VideoResult).video.id })
            .containsExactly("v2")
    }

    @Test
    fun `a channel tab drops videos marked not interested but keeps the rest`() {
        val hidden = hiddenAfter(Event.NotInterested(videoId = "v1", channelId = "UCa"))

        assertThat(FeedItem.VideoItem(video("v1", "UCa")).isHiddenBy(hidden)).isTrue()
        assertThat(FeedItem.ShortItem(video("v1", "UCa")).isHiddenBy(hidden)).isTrue()
        assertThat(FeedItem.VideoItem(video("v2", "UCa")).isHiddenBy(hidden)).isFalse()
    }

    @Test
    fun `history drops entries of hidden videos and blocked channels`() {
        val hidden =
            hiddenAfter(
                Event.NotInterested(videoId = "v1", channelId = "UCa"),
                Event.ChannelBlocked(channelId = "UCb", videoId = "v2"),
            )
        val entries =
            listOf("v1" to "UCa", "v2" to "UCb", "v3" to "UCb", "v4" to "UCc").map { (id, channel) ->
                VideoHistoryEntry(id, 0, 0, 0, id, "", channelId = channel)
            }

        assertThat(entries.withoutHidden(hidden).map { it.videoId }).containsExactly("v4")
    }
}
