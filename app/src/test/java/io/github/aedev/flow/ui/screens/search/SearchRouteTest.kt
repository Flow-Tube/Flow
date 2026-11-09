package io.github.aedev.flow.ui.screens.search

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.ContentType
import io.github.aedev.flow.data.local.Duration
import io.github.aedev.flow.data.local.SearchFeature
import io.github.aedev.flow.data.local.SearchFilter
import io.github.aedev.flow.data.local.SortType
import io.github.aedev.flow.data.local.UploadDate
import io.github.aedev.flow.data.local.WatchState
import org.junit.Test

class SearchRouteTest {
    @Test
    fun `filters survive the trip through a search page's route`() {
        val filters =
            SearchFilter(
                contentType = ContentType.entries.last(),
                duration = Duration.entries.last(),
                uploadDate = UploadDate.entries.last(),
                sortType = SortType.entries.last(),
                features = SearchFeature.entries.take(2).toSet(),
                watchState = WatchState.entries.last(),
            )

        assertThat(searchFilterFromRouteArg(filters.toRouteArg())).isEqualTo(filters)
        assertThat(searchFilterFromRouteArg(SearchFilter.DEFAULT.toRouteArg())).isEqualTo(SearchFilter.DEFAULT)
    }

    @Test
    fun `missing or unreadable filters fall back to the default`() {
        assertThat(searchFilterFromRouteArg(null)).isEqualTo(SearchFilter.DEFAULT)
        assertThat(searchFilterFromRouteArg("")).isEqualTo(SearchFilter.DEFAULT)
        assertThat(searchFilterFromRouteArg("NOPE|NOPE|NOPE|NOPE|NOPE|NOPE")).isEqualTo(SearchFilter.DEFAULT)
    }

    @Test
    fun `a search page's route carries the words, the filters and how deep it is`() {
        val route = searchRoute("daft punk & co", SearchFilter.DEFAULT, depth = 3)

        assertThat(route).startsWith("search?query=daft+punk+%26+co&filters=")
        assertThat(route).endsWith("&depth=3")
    }
}
