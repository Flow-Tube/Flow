package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.innertube.pages.reel.toReelItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReelCommentsPanelTest {
    private fun reel(content: String) =
        Json.parseToJsonElement(
            """
            {"engagementPanels": [
              {"engagementPanelSectionListRenderer": {"targetId": "engagement-panel-structured-description"}},
              {"engagementPanelSectionListRenderer": {
                "targetId": "engagement-panel-comments-section",
                "continuationService": "ENGAGEMENT_PANEL_CONTINUATION_SERVICE_BROWSE",
                "header": {"engagementPanelTitleHeaderRenderer": {
                  "contextualInfo": {"runs": [{"text": "145"}]},
                  "menu": {"sortFilterSubMenuRenderer": {"subMenuItems": [
                    {"title": "Top", "selected": true, "serviceEndpoint": {"continuationCommand": {"token": "top"}}},
                    {"title": "Newest", "selected": false, "serviceEndpoint": {"continuationCommand": {"token": "newest"}}}
                  ]}}
                }},
                "content": {"sectionListRenderer": {"contents": [
                  {"itemSectionRenderer": {"sectionIdentifier": "comment-item-section", "contents": [$content]}}
                ]}}
              }}
            ]}
            """.trimIndent(),
        )

    @Test
    fun `reads the first page token, the sort menu and the count from a reel's comment panel`() {
        val panel =
            reel("""{"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": "first"}}}}""")
                .reelCommentsPanel()

        assertEquals(VideoCommentsSection.Open("first"), panel?.section)
        assertEquals(listOf("top", "newest"), panel?.header?.sortOptions?.map { it.token })
        assertEquals("145", panel?.header?.totalText)
        assertEquals(145L, panel?.header?.totalCount)
    }

    @Test
    fun `a reel with comments turned off says so`() {
        val panel = reel("""{"messageRenderer": {"text": {"runs": [{"text": "Comments are turned off."}]}}}""").reelCommentsPanel()

        assertEquals(VideoCommentsSection.TurnedOff, panel?.section)
    }

    @Test
    fun `a reel without a comment panel has none`() {
        assertNull(Json.parseToJsonElement("""{"engagementPanels": []}""").reelCommentsPanel())
        assertNull(reel("").reelCommentsPanel())
    }

    @Test
    fun `a reel item keeps its comment panel when the overlay is unreadable`() {
        val item =
            reel("""{"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": "first"}}}}""")
                .jsonObject
                .toReelItem("reel")

        assertNull(item?.overlay)
        assertEquals(VideoCommentsSection.Open("first"), item?.comments?.section)
    }
}
