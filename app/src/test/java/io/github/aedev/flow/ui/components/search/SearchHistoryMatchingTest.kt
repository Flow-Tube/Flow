package io.github.aedev.flow.ui.components.search

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.SearchHistoryItem
import org.junit.Test

class SearchHistoryMatchingTest {
    private fun history(vararg queries: String) = queries.mapIndexed { index, query -> SearchHistoryItem(id = "$index", query = query) }

    @Test
    fun `an empty query returns the newest entries up to the limit`() {
        val all = history("a", "b", "c")
        assertThat(all.matchingTyped("  ", limit = 2).map { it.query }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `prefix matches come before matches inside the query`() {
        val all = history("lofi beats", "chill lofi", "rock", "Lofi girl")
        assertThat(all.matchingTyped("lofi").map { it.query })
            .containsExactly("lofi beats", "Lofi girl", "chill lofi")
            .inOrder()
    }

    @Test
    fun `nothing matches an unrelated query`() {
        assertThat(history("jazz").matchingTyped("metal")).isEmpty()
    }

    @Test
    fun `every kept search is listed while nothing is typed`() {
        val all = history(*Array(SEARCH_HISTORY_LIMIT * 5) { "query $it" })
        assertThat(all.recentOrMatching(" ")).isEqualTo(all)
    }

    @Test
    fun `typing narrows the list to the best matches`() {
        val all = history(*Array(SEARCH_HISTORY_LIMIT * 5) { "query $it" })
        assertThat(all.recentOrMatching("query")).hasSize(SEARCH_HISTORY_LIMIT)
    }
}
