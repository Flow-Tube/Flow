package io.github.aedev.flow.ui.components.search

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.SearchHistoryItem
import org.junit.Test

class SearchSuggestionPolicyTest {
    private fun history(vararg queries: String) = queries.mapIndexed { index, query -> SearchHistoryItem(id = "$index", query = query) }

    @Test
    fun `a suggestion a history row already shows is dropped`() {
        val remote = listOf("lofi girl", "lofi girl radio", "lofi girl christmas")

        val kept = remote.withoutShownHistory(history("Lofi  Girl ")) { it }

        assertThat(kept).containsExactly("lofi girl radio", "lofi girl christmas").inOrder()
    }

    @Test
    fun `suggestions stay untouched without shown history`() {
        val remote = listOf("lofi girl")

        assertThat(remote.withoutShownHistory(emptyList()) { it }).isSameInstanceAs(remote)
    }

    @Test
    fun `a partial match is not a duplicate`() {
        assertThat(listOf("lofi girl radio").withoutShownHistory(history("lofi girl")) { it }).containsExactly("lofi girl radio")
    }
}
