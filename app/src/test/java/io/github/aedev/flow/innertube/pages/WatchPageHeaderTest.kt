package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.data.model.RichTextTarget
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchPageHeaderTest {
    @Test
    fun `reads the hashtags above the title as one line with a link per hashtag`() {
        val header = Json.parseToJsonElement(watchResponse(primary = PRIMARY)).toWatchPageHeader(ownVideoId = "abc")

        val line = requireNotNull(header.superTitle)
        assertEquals("#OfficialMusicVideo #NeverGonnaGiveYouUp Paris", line.text)
        assertEquals(
            listOf("officialmusicvideo", "nevergonnagiveyouup"),
            line.spans.map { (it.target as RichTextTarget.Hashtag).tag },
        )
        assertEquals("#NeverGonnaGiveYouUp", line.text.substring(line.spans[1].start, line.spans[1].end))
    }

    @Test
    fun `leaves a place without a link, since Flow has no page for one`() {
        val header = Json.parseToJsonElement(watchResponse(primary = PRIMARY)).toWatchPageHeader(ownVideoId = "abc")

        val line = requireNotNull(header.superTitle)
        val place = line.text.indexOf("Paris")
        assertEquals(emptyList<Int>(), line.spans.filter { it.end > place }.map { it.start })
    }

    @Test
    fun `has no line when the response carries none`() {
        val header = Json.parseToJsonElement(watchResponse(primary = """{"title":{"runs":[{"text":"T"}]}}""")).toWatchPageHeader("abc")

        assertNull(header.superTitle)
    }

    @Test
    fun `reads the endpoint the server wants counts refreshed through`() {
        val primary = """{"updatedMetadataEndpoint":{"updatedMetadataEndpoint":{"videoId":"abc","initialDelayMs":300000}}}"""
        val header = Json.parseToJsonElement(watchResponse(primary = primary)).toWatchPageHeader("abc")

        assertEquals(UpdatedMetadataEndpoint(videoId = "abc", initialDelayMs = 300_000L), header.updatedMetadata)
        assertNull(Json.parseToJsonElement(watchResponse()).toWatchPageHeader("abc").updatedMetadata)
    }

    @Test
    fun `an artist channel reads as an artist even beside a verified badge`() {
        val header = Json.parseToJsonElement(watchResponse(secondary = owner("VERIFIED", "VERIFIED_ARTIST"))).toWatchPageHeader("abc")

        assertEquals(ChannelBadge.ARTIST, header.ownerBadge)
    }

    @Test
    fun `reads a verified channel, and no badge for one without`() {
        val verified = Json.parseToJsonElement(watchResponse(secondary = owner("VERIFIED"))).toWatchPageHeader("abc")
        val plain = Json.parseToJsonElement(watchResponse(secondary = owner())).toWatchPageHeader("abc")

        assertEquals(ChannelBadge.VERIFIED, verified.ownerBadge)
        assertNull(plain.ownerBadge)
    }

    private fun owner(vararg styles: String): String =
        """
        {"owner":{"videoOwnerRenderer":{"badges":[
          ${styles.joinToString(",") { """{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_$it"}}""" }}
        ]}}}
        """

    private fun watchResponse(
        primary: String = "{}",
        secondary: String = "{}",
    ): String =
        """
        {"contents":{"twoColumnWatchNextResults":{"results":{"results":{"contents":[
          {"videoPrimaryInfoRenderer":$primary},
          {"videoSecondaryInfoRenderer":$secondary}
        ]}}}}}
        """.trimIndent()

    private companion object {
        const val PRIMARY = """
        {"superTitleLink":{"runs":[
          {"text":"#OfficialMusicVideo","navigationEndpoint":{
            "commandMetadata":{"webCommandMetadata":{"url":"/hashtag/officialmusicvideo"}},
            "browseEndpoint":{"browseId":"FEhashtag","params":"x"}}},
          {"text":" "},
          {"text":"#NeverGonnaGiveYouUp","navigationEndpoint":{
            "commandMetadata":{"webCommandMetadata":{"url":"/hashtag/nevergonnagiveyouup"}},
            "browseEndpoint":{"browseId":"FEhashtag","params":"y"}}},
          {"text":" "},
          {"text":"Paris","navigationEndpoint":{
            "commandMetadata":{"webCommandMetadata":{"url":"/results?search_query=Paris"}},
            "searchEndpoint":{"query":"Paris"}}}
        ]}}
        """
    }
}
