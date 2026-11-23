package io.github.aedev.flow.innertube.pages

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class WatchPlaylistPanelTest {
    private fun item(
        id: String,
        selected: Boolean = false,
        playable: Boolean = true,
    ) = """
        {"playlistPanelVideoRenderer": {
          "videoId": "$id", "selected": $selected,
          "title": {"simpleText": "Title $id"},
          ${if (playable) "\"lengthText\": {\"simpleText\": \"1:02:03\"}," else "\"unplayableText\": {\"simpleText\": \"[Private video]\"},"}
          "shortBylineText": {"runs": [{"text": "Owner", "navigationEndpoint": {"browseEndpoint": {"browseId": "UCowner"}}}]},
          "thumbnail": {"thumbnails": [{"url": "https://i.ytimg.com/vi/$id/default.jpg", "width": 120, "height": 90}]}
        }}
        """.trimIndent()

    @Test
    fun `reads the window around the selected video`() {
        val json =
            """
            {"contents": {"twoColumnWatchNextResults": {"playlist": {"playlist": {
              "playlistId": "PL1", "title": "Mix", "totalVideos": 6129, "localCurrentIndex": 1,
              "contents": [${item("a")}, ${item("gone", playable = false)}, ${item("b", selected = true)}, ${item("c")}],
              "continuations": [{"nextContinuationData": {"continuation": "NEXT"}}]
            }}}}}
            """.trimIndent()

        val window = Json.parseToJsonElement(json).toWatchPlaylistWindow()!!

        assertThat(window.videos.map { it.id }).containsExactly("a", "b", "c").inOrder()
        assertThat(window.currentIndex).isEqualTo(1)
        assertThat(window.total).isEqualTo(6129)
        assertThat(window.continuation).isEqualTo("NEXT")
        assertThat(window.playlistId).isEqualTo("PL1")
        assertThat(window.videos[0].duration).isEqualTo(3723)
        assertThat(window.videos[0].channelId).isEqualTo("UCowner")
    }

    @Test
    fun `a watch response without a playlist has no window`() {
        assertThat(Json.parseToJsonElement("""{"contents": {"twoColumnWatchNextResults": {}}}""").toWatchPlaylistWindow()).isNull()
    }

    @Test
    fun `reads a panel continuation`() {
        val json =
            """{"continuationContents": {"playlistPanelContinuation": {"contents": [${item("d")}], "continuations": []}}}"""

        val (videos, next) = Json.parseToJsonElement(json).toWatchPlaylistContinuation()!!

        assertThat(videos.map { it.id }).containsExactly("d")
        assertThat(next).isNull()
    }
}
