package io.github.aedev.flow.innertube.pages.renderer

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Channel
import kotlinx.serialization.json.Json
import org.junit.Test

/** Counts on channel cards and playlist badges arrive in the request's language. */
class CountsInAnyLanguageTest {
    private fun channel(
        subscriberCountText: String,
        videoCountText: String,
    ): Channel {
        val item =
            Json
                .parseToJsonElement(
                    """
                    {"channelRenderer": {
                      "channelId": "UCcreator", "title": {"simpleText": "Créateur"},
                      "subscriberCountText": {"simpleText": "$subscriberCountText"},
                      "videoCountText": {"simpleText": "$videoCountText"}
                    }}
                    """,
                ).toFeedItem(FeedItemOwner())
        return (item as FeedItem.RelatedChannelItem).channel
    }

    @Test
    fun `a French card without a handle reads its videos and subscribers`() {
        val card = channel("532 abonnés", "1 234 vidéos")

        assertThat(card.videoCount).isEqualTo(1_234)
        assertThat(card.subscriberCount).isEqualTo(532L)
    }

    @Test
    fun `a German card without a handle reads a grouped video count`() {
        val card = channel("532 Abonnenten", "1.234 Videos")

        assertThat(card.videoCount).isEqualTo(1_234)
        assertThat(card.subscriberCount).isEqualTo(532L)
    }

    @Test
    fun `a French card with a handle has subscribers and no video count`() {
        val card = channel("@createur", "16,9 k abonnés")

        assertThat(card.handle).isEqualTo("@createur")
        assertThat(card.subscriberCount).isEqualTo(16_900L)
        assertThat(card.videoCount).isEqualTo(0)
    }

    @Test
    fun `a German card with the handle second still finds the subscribers`() {
        val card = channel("532 Abonnenten", "@ersteller")

        assertThat(card.handle).isEqualTo("@ersteller")
        assertThat(card.subscriberCount).isEqualTo(532L)
        assertThat(card.videoCount).isEqualTo(0)
    }

    @Test
    fun `a label count keeps its decimal and its thousands`() {
        assertThat("1.2K videos".leadingCount()).isEqualTo(1_200)
        assertThat("1,234 videos".leadingCount()).isEqualTo(1_234)
        assertThat("1.234 Videos".leadingCount()).isEqualTo(1_234)
        assertThat("1 234 vidéos".leadingCount()).isEqualTo(1_234)
        assertThat("12 vidéos".leadingCount()).isEqualTo(12)
        assertThat("Mix".leadingCount()).isNull()
    }
}
