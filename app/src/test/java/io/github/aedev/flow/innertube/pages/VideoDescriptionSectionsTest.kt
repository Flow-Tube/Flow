package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.data.model.RichTextTarget
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoDescriptionSectionsTest {
    @Test
    fun `reads the songs the Music section credits`() {
        val page = Json.parseToJsonElement(watchResponse(MUSIC_ITEM)).toVideoDescriptionPage(ownVideoId = "abc")

        val music = requireNotNull(page.music)
        assertEquals("Music", music.title)
        val song = music.songs.single()
        assertEquals("Never Gonna Give You Up", song.title)
        assertEquals("Rick Astley", song.artist)
        assertEquals("Whenever You Need Somebody", song.album)
        assertEquals("https://yt3.example/art", song.thumbnailUrl)
        assertNull(song.target)
    }

    @Test
    fun `a song card with its own command opens that destination`() {
        val withTap =
            MUSIC_ITEM.replace(
                "\"title\":\"Never Gonna Give You Up\"",
                "\"title\":\"Never Gonna Give You Up\",\"onTap\":{\"innertubeCommand\":{\"watchEndpoint\":{\"videoId\":\"song1\"}}}",
            )
        val page = Json.parseToJsonElement(watchResponse(withTap)).toVideoDescriptionPage(ownVideoId = "abc")

        assertEquals(
            RichTextTarget.Video("song1"),
            page.music
                ?.songs
                ?.single()
                ?.target,
        )
    }

    @Test
    fun `reads the how this was made disclosure with its help link`() {
        val page = Json.parseToJsonElement(watchResponse(DISCLOSURE_ITEM)).toVideoDescriptionPage(ownVideoId = "abc")

        val disclosure = requireNotNull(page.disclosure)
        assertEquals("How this was made", disclosure.title)
        assertEquals("Auto-dubbed", disclosure.header)
        val body = requireNotNull(disclosure.body)
        assertEquals("Audio tracks were generated. Learn more", body.text)
        assertEquals(
            RichTextTarget.Url("https://support.google.com/youtube/answer/1"),
            body.spans.single().target,
        )
    }

    @Test
    fun `a page with only these sections is not empty`() {
        val page = Json.parseToJsonElement(watchResponse(DISCLOSURE_ITEM)).toVideoDescriptionPage(ownVideoId = "abc")

        assertEquals(false, page.isEmpty)
    }

    private fun watchResponse(item: String): String =
        """
        {"engagementPanels":[{"engagementPanelSectionListRenderer":{
          "panelIdentifier":"engagement-panel-structured-description",
          "content":{"structuredDescriptionContentRenderer":{"items":[$item]}}}}]}
        """.trimIndent()

    private companion object {
        const val MUSIC_ITEM = """
        {"horizontalCardListRenderer":{
          "header":{"richListHeaderRenderer":{"title":{"simpleText":"Music"},"subtitle":{"simpleText":"1 song"}}},
          "cards":[{"videoAttributeViewModel":{
            "image":{"sources":[{"url":"https://yt3.example/art"}]},
            "title":"Never Gonna Give You Up",
            "subtitle":"Rick Astley",
            "secondarySubtitle":{"content":"Whenever You Need Somebody"}}}]}}
        """

        const val DISCLOSURE_ITEM = """
        {"howThisWasMadeSectionViewModel":{
          "sectionTitle":{"content":"How this was made"},
          "bodyHeader":{"content":"Auto-dubbed"},
          "bodyText":{"content":"Audio tracks were generated. Learn more",
            "commandRuns":[{"startIndex":29,"length":10,"onTap":{"innertubeCommand":{
              "urlEndpoint":{"url":"//support.google.com/youtube/answer/1"}}}}]}}}
        """
    }
}
