package io.github.aedev.flow.ui.screens.categories

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.ExploreStartCategory
import io.github.aedev.flow.innertube.pages.explore.ExploreDestination
import org.junit.Test

class CategoriesStartPolicyTest {
    @Test
    fun `last category opens the one visited last`() {
        assertThat(exploreStartDestination(ExploreStartCategory.LAST, "SPORTS")).isEqualTo(ExploreDestination.SPORTS)
    }

    @Test
    fun `nothing visited yet opens Live`() {
        assertThat(exploreStartDestination(ExploreStartCategory.LAST, null)).isEqualTo(ExploreDestination.LIVE)
    }

    @Test
    fun `a removed or unknown name opens Live`() {
        assertThat(exploreStartDestination(ExploreStartCategory.LAST, "TRENDING")).isEqualTo(ExploreDestination.LIVE)
        assertThat(exploreStartDestination(ExploreStartCategory.LAST, "")).isEqualTo(ExploreDestination.LIVE)
    }

    @Test
    fun `a chosen category wins over the last one`() {
        assertThat(exploreStartDestination(ExploreStartCategory.NEWS, "SPORTS")).isEqualTo(ExploreDestination.NEWS)
        assertThat(exploreStartDestination(ExploreStartCategory.LIVE, "MUSIC")).isEqualTo(ExploreDestination.LIVE)
    }

    @Test
    fun `an unknown stored choice reads as last category`() {
        assertThat(ExploreStartCategory.fromString("PODCASTS")).isEqualTo(ExploreStartCategory.LAST)
        assertThat(ExploreStartCategory.fromString(null)).isEqualTo(ExploreStartCategory.LAST)
    }

    @Test
    fun `every fixed choice is a tab Explore shows`() {
        val tabs = CATEGORY_TABS.map { it.destination }
        ExploreStartCategory.entries.mapNotNull { it.destination }.forEach { assertThat(tabs).contains(it) }
        assertThat(ExploreStartCategory.entries.mapNotNull { it.destination }).containsExactlyElementsIn(tabs).inOrder()
    }
}
