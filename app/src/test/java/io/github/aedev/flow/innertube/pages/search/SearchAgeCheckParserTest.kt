package io.github.aedev.flow.innertube.pages.search

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** Hand-written first pages shaped like a signed-out WEB search on 2026-11-27. */
class SearchAgeCheckParserTest {
    private fun firstPage(entry: String): JsonObject =
        Json
            .parseToJsonElement(
                """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[""" +
                    """{"itemSectionRenderer":{"contents":[$entry]}}]}}}}}""",
            ).jsonObject

    private fun promo(button: String) =
        """{"backgroundPromoRenderer":{"title":{"runs":[{"text":"Title"}]},"icon":{"iconType":"EMPTY_SEARCH"}$button}}"""

    @Test
    fun `the age check panel marks the page and adds no result`() {
        val page =
            firstPage(
                promo(
                    ""","ctaButton":{"buttonRenderer":{"text":{"runs":[{"text":"Sign in"}]},""" +
                        """"navigationEndpoint":{"signInEndpoint":{"nextEndpoint":{"browseEndpoint":{"browseId":"FEwhat_to_watch"}}}}}}""",
                ),
            ).toSearchResultsPage()

        assertThat(page.ageGated).isTrue()
        assertThat(page.sections).isEmpty()
    }

    @Test
    fun `a no results panel without a sign-in button is not the age check`() {
        assertThat(firstPage(promo("")).toSearchResultsPage().ageGated).isFalse()
    }

    @Test
    fun `an ordinary page is not age checked`() {
        assertThat(firstPage("""{"continuationItemRenderer":{}}""").toSearchResultsPage().ageGated).isFalse()
    }
}
