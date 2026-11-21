package io.github.aedev.flow.ui.screens.home

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import org.junit.Test

class HomeChannelEnrichmentTest {
    private fun video(
        id: String,
        channelId: String,
        avatar: String = "",
    ) = Video(
        id = id,
        title = id,
        channelName = "",
        channelId = channelId,
        thumbnailUrl = "",
        duration = 60,
        viewCount = 1,
        uploadDate = "",
        channelThumbnailUrl = avatar,
    )

    @Test
    fun `a page asks once per channel and never for a channel it already has`() {
        val asked = HashSet<String>()
        val page =
            listOf(
                video("a1", "UCa"),
                video("a2", "UCa"),
                video("b1", "UCb", avatar = "https://yt3.ggpht.com/b=s88"),
                video("c1", ""),
            )

        assertThat(channelsToEnrich(page, asked).map { it.id }).containsExactly("a1", "c1")
        assertThat(channelsToEnrich(page + video("a3", "UCa"), asked)).isEmpty()
    }

    @Test
    fun `one lookup fills every card of its channel`() {
        val page = listOf(video("a1", "UCa"), video("a2", "UCa"), video("x1", "UCx"))
        val asked = channelsToEnrich(page, HashSet())
        val answered =
            asked.map {
                if (it.channelId ==
                    "UCa"
                ) {
                    it.copy(channelName = "A", channelThumbnailUrl = "https://yt3.ggpht.com/a=s88")
                } else {
                    it
                }
            }

        val filled = page.withEnrichedChannels(enrichedByChannel(asked, answered))

        assertThat(filled.map { it.channelName }).containsExactly("A", "A", "").inOrder()
        assertThat(filled[1].channelThumbnailUrl).isEqualTo("https://yt3.ggpht.com/a=s88")
    }

    @Test
    fun `a lookup that learned nothing writes nothing`() {
        val asked = listOf(video("a1", "UCa"))

        assertThat(enrichedByChannel(asked, asked)).isEmpty()
    }
}
