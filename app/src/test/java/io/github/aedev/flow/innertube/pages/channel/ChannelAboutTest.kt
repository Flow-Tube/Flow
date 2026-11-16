package io.github.aedev.flow.innertube.pages.channel

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelAboutTest {
    private fun token(raw: String) = Json.parseToJsonElement(raw).channelAboutContinuation()

    private fun header(
        description: String = """{ "descriptionPreviewViewModel": { "description": { "content": "Hi" } } }""",
        attribution: String? = null,
    ) = """
        { "header": { "pageHeaderRenderer": { "content": { "pageHeaderViewModel": {
          "description": $description
          ${attribution?.let { ""","attribution": $it""" }.orEmpty()}
        } } } } }
        """.trimIndent()

    private fun panel(token: String) =
        """
        { "descriptionPreviewViewModel": {
          "description": { "content": "Hi" },
          "commandContext": { "onTap": { "innertubeCommand": { "showEngagementPanelEndpoint": { "engagementPanel": {
            "engagementPanelSectionListRenderer": { "content": { "sectionListRenderer": { "contents": [ { "itemSectionRenderer": {
              "contents": [ { "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "$token" } } } } ]
            } } ] } } }
          } } } } }
        } }
        """.trimIndent()

    private fun links(token: String) =
        """
        { "attributionViewModel": { "suffix": { "commandRuns": [ { "onTap": { "innertubeCommand": {
          "continuationCommand": { "token": "$token" }
        } } } ] } } }
        """.trimIndent()

    @Test
    fun `a one-link channel reaches its About panel through the description (2058)`() {
        assertEquals("from-description", token(header(description = panel("from-description"))))
    }

    @Test
    fun `the more-links token is still preferred when a channel has one`() {
        assertEquals("from-links", token(header(description = panel("from-description"), attribution = links("from-links"))))
    }

    @Test
    fun `no token anywhere means no About request`() {
        assertNull(token(header()))
    }
}
