package io.github.aedev.flow.innertube.models.response

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Test

/**
 * The fixture is the chapters panel of a live `/next` response, trimmed to that one panel. The
 * video is the eight-chapter one the schema probe was run against. `watch_next_auto_chapters.json`
 * is hand-written in the shape of a video that has only YouTube's generated chapters.
 */
class VideoChaptersParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String = "watch_next_chapters.json") =
        json.parseToJsonElement(
            javaClass.classLoader!!
                .getResourceAsStream(name)!!
                .bufferedReader()
                .use { it.readText() },
        )

    @Test
    fun `every chapter in the panel is read`() {
        val chapters = VideoChaptersParser.parse(fixture())

        assertThat(chapters).hasSize(8)
        assertThat(chapters.first().title).isEqualTo("Intro")
    }

    @Test
    fun `the start comes from the endpoint's seconds, not the displayed time`() {
        val chapters = VideoChaptersParser.parse(fixture())

        assertThat(chapters.first().startTimeSeconds).isEqualTo(0)
        assertThat(chapters[1].startTimeSeconds).isEqualTo(37)
        assertThat(chapters[1].title).isEqualTo("Pro - Cost per Gig")
    }

    @Test
    fun `chapters run in order and carry a frame each`() {
        val chapters = VideoChaptersParser.parse(fixture())

        assertThat(chapters.map { it.startTimeSeconds }).isInOrder()
        assertThat(chapters.all { it.thumbnailUrl?.isNotBlank() == true }).isTrue()
    }

    @Test
    fun `a watch response with no chapter panel yields nothing`() {
        val noPanel =
            json.parseToJsonElement(
                """{"engagementPanels":[{"engagementPanelSectionListRenderer":
                {"panelIdentifier":"engagement-panel-comments-section","content":{}}}]}""",
            )

        assertThat(VideoChaptersParser.parse(noPanel)).isEmpty()
    }

    @Test
    fun `a malformed payload yields nothing instead of throwing`() {
        assertThat(VideoChaptersParser.parse(null)).isEmpty()
        assertThat(VideoChaptersParser.parse(JsonNull)).isEmpty()
        assertThat(VideoChaptersParser.parse(json.parseToJsonElement("""{"engagementPanels":"nope"}"""))).isEmpty()
        assertThat(VideoChaptersParser.parse(json.parseToJsonElement("""[1,2,3]"""))).isEmpty()
    }

    @Test
    fun `an item missing its title or its start is dropped rather than failing the list`() {
        val partial =
            json.parseToJsonElement(
                """
                {"engagementPanels":[{"engagementPanelSectionListRenderer":{
                "panelIdentifier":"engagement-panel-macro-markers-description-chapters",
                "content":{"macroMarkersListRenderer":{"contents":[
                {"macroMarkersListItemRenderer":{"title":{"simpleText":"Good"},
                 "onTap":{"watchEndpoint":{"startTimeSeconds":0}}}},
                {"macroMarkersListItemRenderer":{"onTap":{"watchEndpoint":{"startTimeSeconds":10}}}},
                {"macroMarkersListItemRenderer":{"title":{"simpleText":"No start"}}},
                {"macroMarkersListItemRenderer":{"title":{"simpleText":"Also good"},
                 "onTap":{"watchEndpoint":{"startTimeSeconds":20}}}}]}}}}]}
                """.trimIndent(),
            )

        val chapters = VideoChaptersParser.parse(partial)

        assertThat(chapters.map { it.title }).containsExactly("Good", "Also good").inOrder()
    }

    @Test
    fun `a title given as runs is joined`() {
        val runs =
            json.parseToJsonElement(
                """
                {"engagementPanels":[{"engagementPanelSectionListRenderer":{
                "panelIdentifier":"engagement-panel-macro-markers-description-chapters",
                "content":{"macroMarkersListRenderer":{"contents":[
                {"macroMarkersListItemRenderer":{"title":{"runs":[{"text":"Part "},{"text":"one"}]},
                 "onTap":{"watchEndpoint":{"startTimeSeconds":5}}}}]}}}}]}
                """.trimIndent(),
            )

        assertThat(VideoChaptersParser.parse(runs).single().title).isEqualTo("Part one")
    }

    @Test
    fun `generated chapters are read when the uploader added none`() {
        val chapters = VideoChaptersParser.parse(fixture("watch_next_auto_chapters.json"))

        assertThat(chapters.map { it.title })
            .containsExactly("Start line", "Along the coast", "Over the bridge", "Last stretch", "Finish")
            .inOrder()
    }

    @Test
    fun `a decimal start is rounded down to its whole second`() {
        val chapters = VideoChaptersParser.parse(fixture("watch_next_auto_chapters.json"))

        assertThat(chapters.map { it.startTimeSeconds }).containsExactly(0, 75, 129, 172, 213).inOrder()
    }

    @Test
    fun `the uploader's chapters win over generated ones`() {
        val both =
            json.parseToJsonElement(
                """
                {"engagementPanels":[
                {"engagementPanelSectionListRenderer":{
                "panelIdentifier":"engagement-panel-macro-markers-auto-chapters",
                "content":{"macroMarkersListRenderer":{"contents":[
                {"macroMarkersListItemRenderer":{"title":{"simpleText":"Generated"},
                 "onTap":{"watchEndpoint":{"startTimeSeconds":0.0}}}}]}}}},
                {"engagementPanelSectionListRenderer":{
                "panelIdentifier":"engagement-panel-macro-markers-description-chapters",
                "content":{"macroMarkersListRenderer":{"contents":[
                {"macroMarkersListItemRenderer":{"title":{"simpleText":"Uploader"},
                 "onTap":{"watchEndpoint":{"startTimeSeconds":0}}}}]}}}}]}
                """.trimIndent(),
            )

        assertThat(VideoChaptersParser.parse(both).single().title).isEqualTo("Uploader")
    }

    @Test
    fun `a negative start is dropped`() {
        val negative =
            json.parseToJsonElement(
                """
                {"engagementPanels":[{"engagementPanelSectionListRenderer":{
                "panelIdentifier":"engagement-panel-macro-markers-auto-chapters",
                "content":{"macroMarkersListRenderer":{"contents":[
                {"macroMarkersListItemRenderer":{"title":{"simpleText":"Before"},
                 "onTap":{"watchEndpoint":{"startTimeSeconds":-0.5}}}},
                {"macroMarkersListItemRenderer":{"title":{"simpleText":"After"},
                 "onTap":{"watchEndpoint":{"startTimeSeconds":"12.5"}}}}]}}}}]}
                """.trimIndent(),
            )

        assertThat(VideoChaptersParser.parse(negative).map { it.startTimeSeconds }).containsExactly(12)
    }
}
