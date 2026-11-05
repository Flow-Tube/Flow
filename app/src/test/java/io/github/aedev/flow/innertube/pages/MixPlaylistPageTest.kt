package io.github.aedev.flow.innertube.pages

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class MixPlaylistPageTest {
    private fun panelVideo(
        id: String,
        title: String,
        extra: String = "",
    ) = """
        {"playlistPanelVideoRenderer": {
          "videoId": "$id",
          "title": {"simpleText": "$title"},
          "lengthText": {"simpleText": "3:39"},
          "shortBylineText": {"runs": [{"text": "Channel $id",
            "navigationEndpoint": {"browseEndpoint": {"browseId": "UC$id"}}}]}
          $extra
        }}
        """

    private fun response(vararg items: String) =
        Json.parseToJsonElement(
            """
            {"contents": {"twoColumnWatchNextResults": {"playlist": {"playlist": {
              "title": "Mix - Song",
              "ownerName": {"simpleText": "YouTube"},
              "isInfinite": true,
              "contents": [${items.joinToString()}]
            }}}}}
            """,
        )

    @Test
    fun `reads the title, owner and videos of the queue`() {
        val page = response(panelVideo("a1", "First"), panelVideo("b2", "Second")).toMixPlaylistPage()

        assertThat(page.title).isEqualTo("Mix - Song")
        assertThat(page.ownerName).isEqualTo("YouTube")
        assertThat(page.continuation).isNull()
        assertThat(page.videos.map { it.id }).containsExactly("a1", "b2").inOrder()
        val first = page.videos.first()
        assertThat(first.title).isEqualTo("First")
        assertThat(first.channelName).isEqualTo("Channel a1")
        assertThat(first.channelId).isEqualTo("UCa1")
        assertThat(first.duration).isEqualTo(219)
        assertThat(page.thumbnailUrl).isEqualTo(first.thumbnailUrl)
    }

    @Test
    fun `drops repeats and videos that cannot play`() {
        val page =
            response(
                panelVideo("a1", "First"),
                panelVideo("a1", "First again"),
                panelVideo("c3", "Blocked", """, "unplayableText": {"simpleText": "Unavailable"}"""),
            ).toMixPlaylistPage()

        assertThat(page.videos.map { it.title }).containsExactly("First")
    }

    @Test
    fun `a response without a queue is an empty page`() {
        val page = Json.parseToJsonElement("""{"contents": {}}""").toMixPlaylistPage()

        assertThat(page.title).isNull()
        assertThat(page.videos).isEmpty()
    }

    @Test
    fun `a plain mix names its seed video`() {
        assertThat(mixSeedVideoId("RDHCjNJDNzw8Y")).isEqualTo("HCjNJDNzw8Y")
        assertThat(mixSeedVideoId("RDMMHCjNJDNzw8Y")).isNull()
    }

    @Test
    fun `only video mixes count as mixes`() {
        assertThat(isMix("RDHCjNJDNzw8Y")).isTrue()
        assertThat(isMix("RDCLAK5uy_abc")).isFalse()
        assertThat(isMix("PLabc")).isFalse()
    }
}
