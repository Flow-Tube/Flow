package io.github.aedev.flow.innertube.pages.channel

import io.github.aedev.flow.innertube.pages.renderer.FeedItem
import io.github.aedev.flow.innertube.pages.renderer.FeedItemOwner
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid arrives in four shapes — initial browse, sort switch, page append and the older
 * `richGridContinuation` — and the header carries continuation tokens of its own that must not be
 * mistaken for the grid's next page.
 */
class ChannelGridParserTest {
    private fun parse(
        raw: String,
        kind: ChannelTabKind = ChannelTabKind.Videos,
        fallbackOwner: FeedItemOwner = FeedItemOwner(),
    ) = Json.parseToJsonElement(raw).toChannelTabContent(kind, fallbackOwner)

    private fun lockup(id: String) =
        """
        { "richItemRenderer": { "content": { "lockupViewModel": {
          "contentId": "$id",
          "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
          "metadata": { "lockupMetadataViewModel": { "title": { "content": "Video $id" } } }
        } } } }
        """.trimIndent()

    private fun continuationItem(token: String) =
        """{ "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "$token" } } } }"""

    private val metadata =
        """
        "metadata": { "channelMetadataRenderer": {
          "title": "Linus Tech Tips",
          "externalId": "UCXuqSBlHAE6Xw-yeJA0Tunw",
          "avatar": { "thumbnails": [ { "url": "https://yt3.test/a.jpg", "width": 900, "height": 900 } ] }
        } }
        """.trimIndent()

    @Test
    fun `reads an initial browse with its sort bar and continuation`() {
        val content =
            parse(
                """
                {
                  $metadata,
                  "contents": { "twoColumnBrowseResultsRenderer": { "tabs": [ { "tabRenderer": {
                    "selected": true,
                    "content": { "richGridRenderer": {
                      "header": { "chipBarViewModel": { "chips": [
                        { "chipViewModel": { "text": "Latest", "selected": true,
                          "tapCommand": { "innertubeCommand": { "continuationCommand": { "token": "TOK_LATEST" } } } } },
                        { "chipViewModel": { "text": "Popular",
                          "tapCommand": { "innertubeCommand": { "continuationCommand": { "token": "TOK_POPULAR" } } } } }
                      ] } },
                      "contents": [ ${lockup("a")}, ${lockup("b")}, ${continuationItem("TOK_PAGE_2")} ]
                    } }
                  } } ] } }
                }
                """.trimIndent(),
            )

        assertEquals(listOf("a", "b"), content.items.map { (it as FeedItem.VideoItem).video.id })
        assertEquals(listOf("Latest", "Popular"), content.filters.flatMap { group -> group.options.map { it.label } })
        assertEquals("TOK_PAGE_2", content.continuation)
        assertEquals("UCXuqSBlHAE6Xw-yeJA0Tunw", content.owner.id)
        assertEquals("Linus Tech Tips", content.owner.name)
        assertEquals("https://yt3.test/a.jpg", content.owner.avatarUrl)
    }

    @Test
    fun `reads a page append`() {
        val content =
            parse(
                """
                { "onResponseReceivedActions": [ { "appendContinuationItemsAction": {
                  "continuationItems": [ ${lockup("c")}, ${continuationItem("TOK_PAGE_3")} ]
                } } ] }
                """.trimIndent(),
            )

        assertEquals(listOf("c"), content.items.map { (it as FeedItem.VideoItem).video.id })
        assertEquals("TOK_PAGE_3", content.continuation)
    }

    @Test
    fun `reads a sort switch that replaces the grid rather than extending it`() {
        val content =
            parse(
                """
                { "onResponseReceivedActions": [
                  { "reloadContinuationItemsCommand": { "slot": "RELOAD_CONTINUATION_SLOT_HEADER",
                    "continuationItems": [ { "chipBarViewModel": { "chips": [
                      { "chipViewModel": { "text": "Popular", "selected": true,
                        "tapCommand": { "innertubeCommand": { "continuationCommand": { "token": "TOK_POPULAR" } } } } }
                    ] } } ] } },
                  { "reloadContinuationItemsCommand": { "slot": "RELOAD_CONTINUATION_SLOT_BODY",
                    "continuationItems": [ ${lockup("d")}, ${continuationItem("TOK_POPULAR_2")} ] } }
                ] }
                """.trimIndent(),
            )

        assertEquals(listOf("d"), content.items.map { (it as FeedItem.VideoItem).video.id })
        assertEquals("TOK_POPULAR_2", content.continuation)
        assertEquals(listOf("Popular"), content.filters.flatMap { group -> group.options.map { it.label } })
    }

    @Test
    fun `reads the older rich grid continuation`() {
        val content =
            parse(
                """
                { "continuationContents": { "richGridContinuation": {
                  "contents": [ ${lockup("e")}, ${continuationItem("TOK_OLD")} ]
                } } }
                """.trimIndent(),
            )

        assertEquals(listOf("e"), content.items.map { (it as FeedItem.VideoItem).video.id })
        assertEquals("TOK_OLD", content.continuation)
    }

    @Test
    fun `does not mistake the header's own continuation for the grid's next page`() {
        val content =
            parse(
                """
                {
                  "header": { "pageHeaderRenderer": { "content": { "pageHeaderViewModel": {
                    "description": { "descriptionPreviewViewModel": {
                      "rendererContext": { "commandContext": ${continuationItem("TOK_HEADER_DESCRIPTION")} }
                    } }
                  } } } },
                  "contents": { "twoColumnBrowseResultsRenderer": { "tabs": [ { "tabRenderer": {
                    "content": { "richGridRenderer": { "contents": [ ${lockup("f")} ] } }
                  } } ] } }
                }
                """.trimIndent(),
            )

        assertEquals(listOf("f"), content.items.map { (it as FeedItem.VideoItem).video.id })
        assertEquals(null, content.continuation)
    }

    @Test
    fun `a continuation keeps the owner the caller threaded in`() {
        val fallback = FeedItemOwner(id = "UCcarried", name = "Carried", avatarUrl = "https://yt3.test/carried.jpg")
        val content =
            parse(
                """
                { "onResponseReceivedActions": [ { "appendContinuationItemsAction": {
                  "continuationItems": [ ${lockup("g")} ]
                } } ] }
                """.trimIndent(),
                fallbackOwner = fallback,
            )

        assertEquals(fallback, content.owner)
        assertEquals("Carried", (content.items.single() as FeedItem.VideoItem).video.channelName)
    }

    @Test
    fun `an empty tab parses to no items rather than failing`() {
        val content =
            parse(
                """
                { "contents": { "twoColumnBrowseResultsRenderer": { "tabs": [ { "tabRenderer": {
                  "content": { "richGridRenderer": { "contents": [] } }
                } } ] } } }
                """.trimIndent(),
            )

        assertTrue(content.items.isEmpty())
        assertTrue(content.filters.isEmpty())
        assertEquals(null, content.continuation)
    }

    @Test
    fun `duplicate items across grid shapes are collapsed`() {
        val content =
            parse(
                """
                {
                  "contents": { "twoColumnBrowseResultsRenderer": { "tabs": [ { "tabRenderer": {
                    "content": { "richGridRenderer": { "contents": [ ${lockup("dup")} ] } }
                  } } ] } },
                  "onResponseReceivedActions": [ { "appendContinuationItemsAction": {
                    "continuationItems": [ ${lockup("dup")} ]
                  } } ]
                }
                """.trimIndent(),
            )

        assertEquals(1, content.items.size)
    }

    @Test
    fun `reads a topic channel Home whose shelves sit in a rich grid beside an empty section list`() {
        val content =
            parse(
                """
                {
                  $metadata,
                  "contents": { "twoColumnBrowseResultsRenderer": { "tabs": [ { "tabRenderer": {
                    "selected": true,
                    "content": {
                      "sectionListRenderer": { "disablePullToRefresh": true },
                      "richGridRenderer": { "contents": [
                        { "richSectionRenderer": { "content": { "richShelfRenderer": {
                          "title": { "simpleText": "Today's Biggest Hits" },
                          "contents": [
                            { "richItemRenderer": { "content": { "lockupViewModel": {
                              "contentId": "RDCLAK5uy_hits",
                              "contentType": "LOCKUP_CONTENT_TYPE_STATION",
                              "metadata": { "lockupMetadataViewModel": { "title": { "content": "The Hit List" } } }
                            } } } }
                          ]
                        } } } },
                        { "richSectionRenderer": { "content": { "richShelfRenderer": {
                          "title": { "simpleText": "New This Week" },
                          "contents": [ ${lockup("a")} ]
                        } } } }
                      ] }
                    }
                  } } ] } }
                }
                """.trimIndent(),
                kind = ChannelTabKind.Home,
            )

        assertEquals(listOf("Today's Biggest Hits", "New This Week"), content.sections.map { it.title })
        val station =
            content.sections
                .first()
                .items
                .single() as FeedItem.PlaylistItem
        assertEquals("RDCLAK5uy_hits", station.playlist.id)
        assertEquals("The Hit List", station.playlist.name)
        assertTrue(content.sections[1].items.single() is FeedItem.VideoItem)
    }
}
