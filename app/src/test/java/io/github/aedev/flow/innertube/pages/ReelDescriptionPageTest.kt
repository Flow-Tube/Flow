package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.data.model.RichTextTarget
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReelDescriptionPageTest {
    private val reel =
        Json.parseToJsonElement(
            """
            {"engagementPanels": [
              {"engagementPanelSectionListRenderer": {"targetId": "engagement-panel-comments-section"}},
              {"engagementPanelSectionListRenderer": {
                "targetId": "engagement-panel-structured-description",
                "content": {"structuredDescriptionContentRenderer": {"items": [
                  {"videoDescriptionHeaderRenderer": {
                    "views": {"simpleText": "496,093 views"},
                    "publishDate": {"simpleText": "Sep 18, 2026"},
                    "factoid": [
                      {"factoidRenderer": {"value": {"simpleText": "8.7K"}, "label": {"simpleText": "Likes"},
                        "accessibilityText": "8.7 thousand likes"}},
                      {"viewCountFactoidRenderer": {"factoid": {"factoidRenderer": {
                        "value": {"simpleText": "496,093"}, "label": {"simpleText": "Views"}}}}}
                    ]
                  }},
                  {"expandableVideoDescriptionBodyRenderer": {
                    "descriptionBodyText": {"runs": [{"text": "A build at 0:12 "}, {"text": "#pc"}]}
                  }},
                  {"howThisWasMadeSectionViewModel": {
                    "sectionTitle": {"content": "How this was made"},
                    "bodyHeader": {"content": "Auto-dubbed"},
                    "bodyText": {"content": "Audio tracks were automatically generated."}
                  }}
                ]}}
              }}
            ]}
            """.trimIndent(),
        )

    @Test
    fun `reads the exact figures and the date from the reel's description header`() {
        val page = requireNotNull(reel.toReelDescriptionPage(ownVideoId = "reel"))

        assertEquals("496,093 views", page.viewCountText)
        assertEquals(496_093L, page.viewCount)
        assertEquals("Sep 18, 2026", page.publishedDateText)
        assertEquals(listOf("Likes", "Views"), page.factoids.map { it.label })
        assertEquals(listOf("8.7K", "496,093"), page.factoids.map { it.value })
    }

    @Test
    fun `marks timestamps and hashtags in a body sent as plain runs`() {
        val description = requireNotNull(reel.toReelDescriptionPage(ownVideoId = "reel")?.description)

        assertEquals("A build at 0:12 #pc", description.text)
        assertEquals(12L, (description.spans.first { it.target is RichTextTarget.Timestamp }.target as RichTextTarget.Timestamp).seconds)
        assertEquals("pc", (description.spans.first { it.target is RichTextTarget.Hashtag }.target as RichTextTarget.Hashtag).tag)
    }

    @Test
    fun `reads how this was made`() {
        val disclosure = requireNotNull(reel.toReelDescriptionPage(ownVideoId = "reel")?.disclosure)

        assertEquals("How this was made", disclosure.title)
        assertEquals("Auto-dubbed", disclosure.header)
        assertEquals("Audio tracks were automatically generated.", disclosure.body?.text)
    }

    @Test
    fun `a reel without a description panel has no page`() {
        assertNull(Json.parseToJsonElement("""{"engagementPanels": []}""").toReelDescriptionPage(ownVideoId = "reel"))
    }
}
