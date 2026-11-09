package io.github.aedev.flow.ui.screens.search

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.ContentType
import io.github.aedev.flow.data.local.Duration
import io.github.aedev.flow.data.local.PrivacyGate
import io.github.aedev.flow.data.local.SearchFilter
import io.github.aedev.flow.data.local.SearchHistoryRepository
import io.github.aedev.flow.data.local.SortType
import io.github.aedev.flow.data.local.UploadDate
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.search.SearchSuggestionsRepository
import io.github.aedev.flow.data.shorts.ShortsContentFilter
import io.github.aedev.flow.data.shorts.queue.ShortsQueueHandoff
import io.github.aedev.flow.innertube.pages.search.SearchSuggestion
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val suggestions: SearchSuggestionsRepository = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val history: SearchHistoryRepository = mockk(relaxed = true)

    private var deepFlow = false
    private val privacyGate =
        PrivacyGate(
            deepFlowActive = { deepFlow },
            watchHistoryPaused = { false },
            scrobbleDuringDeepFlow = { false },
        )

    private fun viewModel(savedState: SavedStateHandle = SavedStateHandle()) =
        SearchViewModel(
            context,
            suggestions,
            ShortsContentFilter(flowOf(true)),
            ShortsQueueHandoff(),
            history,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            privacyGate,
            savedState,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // A real engine built on the mocked context reads DataStore on its own scope and fails there,
        // which surfaces as an uncaught exception in whichever runTest comes next.
        mockkObject(FlowNeuroEngine.Companion)
        coEvery { FlowNeuroEngine.onSearchQuery(any(), any()) } just runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `initial ui state has an empty query and no narrowing`() {
        val viewModel = viewModel()

        assertThat(viewModel.uiState.value.query).isEmpty()
        assertThat(viewModel.uiState.value.filters).isEqualTo(SearchFilter.DEFAULT)
        assertThat(viewModel.uiState.value.filters.isDefault).isTrue()
    }

    @Test
    fun `a submitted search is kept in history with its filters`() =
        runTest(testDispatcher) {
            val filters = SearchFilter(duration = Duration.OVER_20_MINUTES, uploadDate = UploadDate.TODAY)
            viewModel().submit("world news", filters)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { history.saveSearchQuery("world news", filters = filters) }
        }

    @Test
    fun `Deep Flow keeps a submitted search and its filter changes out of history`() =
        runTest(testDispatcher) {
            deepFlow = true
            val viewModel = viewModel()
            viewModel.submit("world news", SearchFilter.DEFAULT)
            viewModel.updateFilters(SearchFilter(uploadDate = UploadDate.TODAY))
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 0) { history.saveSearchQuery(any(), any(), any(), any()) }
        }

    @Test
    fun `changing filters after a search updates its history entry`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            viewModel.submit("world news", SearchFilter.DEFAULT)
            val narrowed = SearchFilter(uploadDate = UploadDate.TODAY)
            viewModel.updateFilters(narrowed)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { history.saveSearchQuery("world news", filters = narrowed) }
        }

    @Test
    fun `a search typed on TV is not written to history`() =
        runTest(testDispatcher) {
            viewModel().search("world n")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 0) { history.saveSearchQuery(any(), any(), any(), any()) }
        }

    @Test
    fun `search with valid query updates uiState`() {
        val viewModel = viewModel()
        viewModel.search("Kotlin Compose")

        assertThat(viewModel.uiState.value.query).isEqualTo("Kotlin Compose")
    }

    @Test
    fun `search with empty query resets uiState`() {
        val viewModel = viewModel()
        viewModel.search("Kotlin")
        viewModel.search("")

        assertThat(viewModel.uiState.value.query).isEmpty()
        assertThat(viewModel.uiState.value.filters).isEqualTo(SearchFilter.DEFAULT)
    }

    @Test
    fun `updateFilters updates filters in uiState when query is active`() {
        val viewModel = viewModel()
        viewModel.search("Music")

        val filter = SearchFilter(contentType = ContentType.VIDEOS)
        viewModel.updateFilters(filter)

        assertThat(viewModel.uiState.value.filters).isEqualTo(filter)
    }

    @Test
    fun `clearSearch resets search query and filters`() {
        val viewModel = viewModel()
        viewModel.search("Android", SearchFilter(contentType = ContentType.PLAYLISTS))

        viewModel.clearSearch()

        assertThat(viewModel.uiState.value.query).isEmpty()
        assertThat(viewModel.uiState.value.filters).isEqualTo(SearchFilter.DEFAULT)
    }

    @Test
    fun `a filter change keeps the query it was applied to`() {
        val viewModel = viewModel()
        viewModel.search("bodybuilding")

        viewModel.updateFilters(SearchFilter(sortType = SortType.VIEW_COUNT))

        assertThat(viewModel.uiState.value.query).isEqualTo("bodybuilding")
        assertThat(viewModel.uiState.value.filters.sortType).isEqualTo(SortType.VIEW_COUNT)
    }

    @Test
    fun `counts the active narrowing choices for the filter button`() {
        val filter =
            SearchFilter(
                contentType = ContentType.VIDEOS,
                sortType = SortType.VIEW_COUNT,
                features = setOf(io.github.aedev.flow.data.local.SearchFeature.FOUR_K),
            )

        assertThat(filter.activeCount).isEqualTo(3)
        assertThat(filter.isDefault).isFalse()
    }

    @Test
    fun `suggestions come from the one suggestions repository`() =
        runTest {
            val expected = listOf(SearchSuggestion("kotlin tutorial"), SearchSuggestion("kotlin android"))
            coEvery { suggestions.suggestions("kotlin") } returns expected

            val result = viewModel().getSearchSuggestions("kotlin")

            assertThat(result).isEqualTo(expected)
            coVerify(exactly = 1) { suggestions.suggestions("kotlin") }
        }

    @Test
    fun `a suggestions failure leaves the field usable`() =
        runTest {
            coEvery { suggestions.suggestions(any()) } throws IllegalStateException("offline")

            assertThat(viewModel().getSearchSuggestions("kotlin")).isEmpty()
        }

    @Test
    fun `a search page opened for a query runs it once with its filters`() =
        runTest(testDispatcher) {
            val filters = SearchFilter(uploadDate = UploadDate.TODAY)
            val savedState = SavedStateHandle(mapOf(SEARCH_QUERY_ARG to "world news", SEARCH_FILTERS_ARG to filters.toRouteArg()))
            val viewModel = viewModel(savedState)
            testDispatcher.scheduler.advanceUntilIdle()

            assertThat(viewModel.uiState.value.query).isEqualTo("world news")
            assertThat(viewModel.uiState.value.filters).isEqualTo(filters)

            // A recreated process shows the same search again without keeping it twice.
            viewModel(savedState)
            testDispatcher.scheduler.advanceUntilIdle()
            coVerify(exactly = 1) { history.saveSearchQuery("world news", filters = filters) }
        }
}
