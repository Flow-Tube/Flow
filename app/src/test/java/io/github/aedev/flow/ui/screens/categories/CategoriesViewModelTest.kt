package io.github.aedev.flow.ui.screens.categories

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.ExploreStartCategory
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.explore.ExploreDestination
import io.github.aedev.flow.innertube.pages.explore.ExploreDestinationPage
import io.github.aedev.flow.innertube.pages.explore.ExploreSectionKind
import io.github.aedev.flow.innertube.pages.explore.MusicVideoChart
import io.github.aedev.flow.innertube.pages.renderer.FeedShelf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val context: Context = mockk(relaxed = true)
    private val preferences: PlayerPreferences =
        mockk(relaxed = true) {
            every { categoriesIsListView } returns flowOf(false)
            every { trendingRegion } returns flowOf("US")
            every { exploreStartCategory } returns flowOf(ExploreStartCategory.LAST)
            every { exploreLastCategory } returns flowOf(null)
        }

    private val repository: YouTubeRepository = mockk(relaxed = true)

    private fun viewModel() = CategoriesViewModel(repository, preferences, context)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // The loads are not what these assert, and a real one would reach the network.
        mockkObject(YouTube)
        every { YouTube.exploreDestination(any(), any()) } returns flowOf(ExploreDestinationPage())
        coEvery { YouTube.musicVideoChart(any()) } returns Result.success(MusicVideoChart(title = ""))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    /**
     * The first tab is already the default in state, so routing the first load through `select`
     * short-circuited on its own guard and the screen stayed empty until a tab was switched.
     */
    @Test
    fun `the first tab starts loading without waiting for a tab switch`() =
        runTest(testDispatcher) {
            val state = viewModel().uiState.value

            assertThat(state.selected).isEqualTo(CATEGORY_TABS.first().destination)
            assertThat(state.isLoading).isTrue()
        }

    @Test
    fun `a cold start fetches only the category visited last`() =
        runTest(testDispatcher) {
            every { preferences.exploreLastCategory } returns flowOf(ExploreDestination.SPORTS.name)

            val viewModel = viewModel()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.selected).isEqualTo(ExploreDestination.SPORTS)
            verify(exactly = 1) { YouTube.exploreDestination(ExploreDestination.SPORTS.browseId, any()) }
            verify(exactly = 0) { YouTube.exploreDestination(ExploreDestination.LIVE.browseId, any()) }
        }

    @Test
    fun `a chosen start category wins over the last one`() =
        runTest(testDispatcher) {
            every { preferences.exploreStartCategory } returns flowOf(ExploreStartCategory.NEWS)
            every { preferences.exploreLastCategory } returns flowOf(ExploreDestination.SPORTS.name)

            val viewModel = viewModel()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.selected).isEqualTo(ExploreDestination.NEWS)
        }

    @Test
    fun `a tab tapped before the start category is read is kept`() =
        runTest(testDispatcher) {
            every { preferences.exploreLastCategory } returns flowOf(ExploreDestination.SPORTS.name)
            val viewModel = viewModel()

            viewModel.select(ExploreDestination.MUSIC)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.selected).isEqualTo(ExploreDestination.MUSIC)
            verify(exactly = 0) { YouTube.exploreDestination(ExploreDestination.SPORTS.browseId, any()) }
        }

    @Test
    fun `selecting a tab remembers it as the last category`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.select(ExploreDestination.GAMING)
            advanceUntilIdle()

            coVerify { preferences.setExploreLastCategory(ExploreDestination.GAMING.name) }
        }

    @Test
    fun `re-tapping the selected tab does not reload it`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            viewModel.select(ExploreDestination.GAMING)
            val afterSwitch = viewModel.uiState.value

            viewModel.select(ExploreDestination.GAMING)

            assertThat(viewModel.uiState.value).isEqualTo(afterSwitch)
        }

    @Test
    fun `switching tab adopts that destination's section kind and clears the last one's content`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()

            viewModel.select(ExploreDestination.MUSIC)

            val state = viewModel.uiState.value
            assertThat(state.selected).isEqualTo(ExploreDestination.MUSIC)
            assertThat(state.sectionKind).isEqualTo(ExploreSectionKind.CHART)
            assertThat(state.shelves).isEmpty()
            assertThat(state.subTabs).isEmpty()
        }

    @Test
    fun `a shelf with no see-all cannot be opened`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()

            viewModel.openShelf(shelf(moreParams = null))

            assertThat(viewModel.uiState.value.openShelfTitle).isNull()
        }

    @Test
    fun `opening a shelf switches to the paged grid and titles it`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()

            viewModel.openShelf(shelf(moreParams = "EgdsaXZldGFikgEDCKEK"))

            val state = viewModel.uiState.value
            assertThat(state.openShelfTitle).isEqualTo("Live Now")
            assertThat(state.sectionKind).isEqualTo(ExploreSectionKind.GRID)
        }

    @Test
    fun `backing out of a shelf returns to the destination's shelves`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            viewModel.openShelf(shelf(moreParams = "EgdsaXZldGFikgEDCKEK"))

            viewModel.closeShelf()

            val state = viewModel.uiState.value
            assertThat(state.openShelfTitle).isNull()
            assertThat(state.sectionKind).isEqualTo(ExploreSectionKind.SHELVES)
        }

    @Test
    fun `toggling the view mode flips it and persists it`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            val before = viewModel.uiState.value.isListView

            viewModel.toggleViewMode()

            assertThat(viewModel.uiState.value.isListView).isEqualTo(!before)
        }

    private fun shelf(moreParams: String?) = FeedShelf(id = "shelf:0:Live Now", title = "Live Now", moreParams = moreParams)

    private fun CategoriesViewModel.shelfTitles() = uiState.value.shelves.mapNotNull(FeedShelf::title)

    private fun pageOf(vararg titles: String) =
        ExploreDestinationPage(
            shelves = titles.mapIndexed { index, title -> FeedShelf(id = "shelf:$index:$title", title = title) },
        )

    /**
     * The destination arrives as one response but is mapped shelf by shelf, so a shelf must reach
     * the screen while the rest of the page is still being read — not once the whole thing settles.
     */
    @Test
    fun `a shelf is shown while the rest of the page is still being mapped`() =
        runTest(testDispatcher) {
            val rest = CompletableDeferred<Unit>()
            every { YouTube.exploreDestination(any(), any()) } returns
                flow {
                    emit(ExploreDestinationPage())
                    emit(pageOf("Live now"))
                    rest.await()
                    emit(pageOf("Live now", "Upcoming"))
                }

            val viewModel = viewModel()
            advanceUntilIdle()

            assertThat(viewModel.shelfTitles()).containsExactly("Live now")
            assertThat(viewModel.uiState.value.isLoading).isFalse()

            rest.complete(Unit)
            advanceUntilIdle()

            assertThat(viewModel.shelfTitles()).containsExactly("Live now", "Upcoming").inOrder()
        }

    /**
     * Switching tab cancels the load in flight. `runCatching` catches the `CancellationException`
     * that raises like any other failure, so without a guard the abandoned tab writes its own
     * cancellation onto the screen the user just moved to.
     */
    @Test
    fun `switching away from a loading tab does not surface its cancellation as an error`() =
        runTest(testDispatcher) {
            val stalled = CompletableDeferred<Unit>()
            every { YouTube.exploreDestination(any(), any()) } returns
                flow {
                    emit(ExploreDestinationPage())
                    stalled.await()
                }

            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.select(ExploreDestination.GAMING)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.error).isNull()
        }

    /** A destination response runs to megabytes; tapping back to a tab must not pay for it twice. */
    @Test
    fun `a tab returned to inside the cache window is not fetched again`() =
        runTest(testDispatcher) {
            every { YouTube.exploreDestination(any(), any()) } returns flowOf(pageOf("Live now"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.select(ExploreDestination.GAMING)
            advanceUntilIdle()
            viewModel.select(ExploreDestination.LIVE)
            advanceUntilIdle()

            verify(exactly = 1) { YouTube.exploreDestination(ExploreDestination.LIVE.browseId, any()) }
            assertThat(viewModel.uiState.value.shelves).hasSize(1)
            assertThat(viewModel.uiState.value.isLoading).isFalse()
        }

    @Test
    fun `a region change drops what the tabs had cached`() =
        runTest(testDispatcher) {
            every { YouTube.exploreDestination(any(), any()) } returns flowOf(pageOf("Live now"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.setRegion("FR")
            advanceUntilIdle()

            verify(exactly = 2) { YouTube.exploreDestination(ExploreDestination.LIVE.browseId, any()) }
        }
}
