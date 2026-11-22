package io.github.aedev.flow.innertube.pages.search

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** Minimal responses shaped like the first page of three live searches on 2026-11-22. */
class SearchCorrectionParserTest {
    private fun firstPage(entry: String): JsonObject =
        Json
            .parseToJsonElement(
                """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[""" +
                    """{"itemSectionRenderer":{"contents":[$entry]}}]}}}}}""",
            ).jsonObject

    @Test
    fun `a corrected search names the correction and the way back to the typed text`() {
        val page =
            firstPage(
                """{"showingResultsForRenderer":{"showingResultsFor":{"runs":[{"text":"Showing results for"}]},""" +
                    """"correctedQuery":{"runs":[{"text":"minecraft","italics":true},{"text":" speedrun"}]},""" +
                    """"correctedQueryEndpoint":{"searchEndpoint":{"query":"minecraft speedrun"}},""" +
                    """"originalQuery":{"simpleText":"minecarft speedrun"},""" +
                    """"originalQueryEndpoint":{"searchEndpoint":{"query":"minecarft speedrun","params":"QgIIAQ%3D%3D"}}}}""",
            ).toSearchResultsPage()

        val correction = page.header.correction!!
        assertThat(correction.kind).isEqualTo(SearchCorrection.Kind.SHOWING_RESULTS_FOR)
        assertThat(correction.correctedQuery).isEqualTo("minecraft speedrun")
        assertThat(correction.originalQuery).isEqualTo("minecarft speedrun")
        assertThat(correction.correctedRuns)
            .containsExactly(SearchCorrectionRun("minecraft", changed = true), SearchCorrectionRun(" speedrun", changed = false))
            .inOrder()
        assertThat(page.sections).isEmpty()
    }

    @Test
    fun `a did-you-mean offers the suggestion and has no way back`() {
        val correction =
            firstPage(
                """{"didYouMeanRenderer":{"didYouMean":{"runs":[{"text":"Did you mean:"}]},""" +
                    """"correctedQuery":{"runs":[{"text":"how to "},{"text":"tie","italics":true},{"text":" a tie"}]},""" +
                    """"correctedQueryEndpoint":{"searchEndpoint":{"query":"how to tie a tie"}}}}""",
            ).toSearchResultsPage().header.correction!!

        assertThat(correction.kind).isEqualTo(SearchCorrection.Kind.DID_YOU_MEAN)
        assertThat(correction.correctedQuery).isEqualTo("how to tie a tie")
        assertThat(correction.originalQuery).isNull()
        assertThat(correction.correctedRuns.filter { it.changed }.map { it.text }).containsExactly("tie")
    }

    @Test
    fun `a corrected search without a way back is left out`() {
        val page =
            firstPage(
                """{"showingResultsForRenderer":{"correctedQuery":{"runs":[{"text":"linus","italics":true}]},""" +
                    """"correctedQueryEndpoint":{"searchEndpoint":{"query":"linus tech tips"}}}}""",
            ).toSearchResultsPage()

        assertThat(page.header.correction).isNull()
    }

    @Test
    fun `a search YouTube did not correct has no correction`() {
        assertThat(firstPage("""{"continuationItemRenderer":{}}""").toSearchResultsPage().header.correction).isNull()
    }
}
