package io.github.aedev.flow.innertube.pages

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerAlertTest {
    @Test
    fun `a missing playlist fails with YouTube's sentence`() {
        val error =
            assertThrows(YouTubeAlertException::class.java) {
                Json
                    .parseToJsonElement(
                        """
                        {
                          "responseContext": {},
                          "alerts": [{"alertRenderer": {"type": "ERROR", "text": {"runs": [{"text": "The playlist does not exist."}]}}}],
                          "microformat": {"microformatDataRenderer": {"noindex": true}}
                        }
                        """,
                    ).toVideoPlaylistPage()
            }

        assertThat(error.alert).isEqualTo("The playlist does not exist.")
    }

    @Test
    fun `a missing channel fails even with a stub header`() {
        val response =
            Json.parseToJsonElement(
                """
                {
                  "header": {"c4TabbedHeaderRenderer": {"channelId": "UCmissing"}},
                  "alerts": [{"alertRenderer": {"type": "ERROR", "text": {"simpleText": "This channel does not exist."}}}]
                }
                """,
            )

        val error = assertThrows(YouTubeAlertException::class.java) { response.throwIfRefused() }

        assertThat(error.alert).isEqualTo("This channel does not exist.")
    }

    @Test
    fun `an error alert without text fails with a blank reason`() {
        val response = Json.parseToJsonElement("""{"alerts": [{"alertRenderer": {"type": "ERROR"}}]}""")

        val error = assertThrows(YouTubeAlertException::class.java) { response.throwIfRefused() }

        assertThat(error.alert).isEmpty()
    }

    @Test
    fun `an info alert beside a list keeps the list`() {
        val page =
            Json
                .parseToJsonElement(
                    """
                    {
                      "alerts": [{"alertWithButtonRenderer": {"type": "INFO", "text": {"simpleText": "Unavailable videos are hidden"}}}],
                      "contents": {"twoColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {
                        "sectionListRenderer": {"contents": [{"itemSectionRenderer": {"contents": [
                          {"lockupViewModel": {"contentId": "vid1", "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                            "metadata": {"lockupMetadataViewModel": {"title": {"content": "Kept"}}}}}
                        ]}}]}
                      }}}]}}
                    }
                    """,
                ).toVideoPlaylistPage()

        assertThat(page.videos.map { it.id }).containsExactly("vid1")
    }

    @Test
    fun `an error alert beside real contents does not refuse the page`() {
        val response =
            Json.parseToJsonElement(
                """
                {
                  "alerts": [{"alertRenderer": {"type": "ERROR", "text": {"simpleText": "Something went wrong"}}}],
                  "contents": {"twoColumnBrowseResultsRenderer": {"tabs": []}}
                }
                """,
            )

        response.throwIfRefused()
    }

    @Test
    fun `a continuation with nothing to append is not a refusal`() {
        Json.parseToJsonElement("""{"onResponseReceivedActions": []}""").throwIfRefused()
    }
}
