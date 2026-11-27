package io.github.aedev.flow.ui.screens.categories

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.paging.FeedTabPagingSource
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.shorts.isShort
import io.github.aedev.flow.data.shorts.withoutShorts
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.channel.ChannelTabKind
import io.github.aedev.flow.innertube.pages.explore.ExploreDestination
import io.github.aedev.flow.innertube.pages.explore.ExploreDestinationPage
import io.github.aedev.flow.innertube.pages.explore.ExploreSectionKind
import io.github.aedev.flow.innertube.pages.renderer.FeedItem
import io.github.aedev.flow.innertube.pages.renderer.FeedShelf
import io.github.aedev.flow.utils.LoadErrorKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What one destination is currently showing. Only one of [shelves] and the pager is ever populated:
 * a landing page is shelves, a see-all or the gaming tab is a paged grid, a chart is a fixed list.
 */
data class CategoriesUiState(
    val selected: ExploreDestination = CATEGORY_TABS.first().destination,
    val sectionKind: ExploreSectionKind = ExploreSectionKind.SHELVES,
    val shelves: List<FeedShelf> = emptyList(),
    val chartEntries: List<Video> = emptyList(),
    val chartTitle: String? = null,
    val subTabs: List<CategorySubTab> = emptyList(),
    val selectedSubTab: String? = null,
    val openShelfTitle: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isListView: Boolean = false,
)

/** A destination's own category tabs — News ships seven; every other destination ships none. */
data class CategorySubTab(
    val title: String,
    val params: String?,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModel
    @Inject
    constructor(
        private val repository: YouTubeRepository,
        private val preferences: PlayerPreferences,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(CategoriesUiState(isLoading = true))
        val uiState: StateFlow<CategoriesUiState> = _uiState.asStateFlow()

        /** The shelves to draw: [CategoriesUiState.shelves] without Shorts while they are switched off (#1507). */
        val visibleShelves: StateFlow<List<FeedShelf>> =
            combine(_uiState.map { it.shelves }.distinctUntilChanged(), preferences.shortsContentEnabled) { shelves, shortsEnabled ->
                if (shortsEnabled) shelves else shelves.withoutShorts()
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), emptyList())

        val trendingRegion: StateFlow<String> =
            preferences.trendingRegion.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), "US")

        private data class GridKey(
            val browseId: String,
            val params: String,
        )

        private data class CacheKey(
            val destination: ExploreDestination,
            val params: String?,
        )

        private data class CachedSection(
            val shelves: List<FeedShelf> = emptyList(),
            val chartEntries: List<Video> = emptyList(),
            val chartTitle: String? = null,
            val subTabs: List<CategorySubTab> = emptyList(),
            val selectedSubTab: String? = null,
            val loadedAtMs: Long = System.currentTimeMillis(),
        ) {
            val isFresh: Boolean get() = System.currentTimeMillis() - loadedAtMs < CACHE_TTL_MS
        }

        /**
         * What each tab last showed. A destination response runs to megabytes, so re-fetching one
         * on every tap back is the most expensive thing this screen can do: inside [CACHE_TTL_MS] a
         * tab repaints from here and skips the call, and past it the stale page is shown while a
         * fresh one loads behind it. A region change clears it; a retry drops the tab's own entry.
         */
        private val cache =
            object : LinkedHashMap<CacheKey, CachedSection>(0, CACHE_LOAD_FACTOR, true) {
                override fun removeEldestEntry(eldest: Map.Entry<CacheKey, CachedSection>): Boolean = size > CACHE_ENTRIES
            }

        private val gridKey = MutableStateFlow<GridKey?>(null)

        /** The paged grid behind a shelf's "see all" and behind the Gaming tab. */
        val gridItems: Flow<PagingData<FeedItem>> =
            gridKey
                .filterNotNull()
                .flatMapLatest { key ->
                    Pager(
                        config =
                            PagingConfig(
                                pageSize = PAGE_SIZE,
                                prefetchDistance = PREFETCH_DISTANCE,
                                enablePlaceholders = false,
                                initialLoadSize = PAGE_SIZE,
                            ),
                        pagingSourceFactory = {
                            FeedTabPagingSource(
                                browseId = key.browseId,
                                params = key.params,
                                kind = ChannelTabKind.Videos,
                            )
                        },
                    ).flow
                }.cachedIn(viewModelScope)
                .combine(preferences.shortsContentEnabled) { items, shortsEnabled ->
                    if (shortsEnabled) items else items.filter { !it.isShort() }
                }

        private var loadJob: Job? = null

        /** Set once anything has been loaded, so a tap during the start read is not overridden. */
        private var started = false

        init {
            viewModelScope.launch {
                _uiState.update { it.copy(isListView = preferences.categoriesIsListView.first()) }
            }
            viewModelScope.launch {
                val start = exploreStartDestination(preferences.exploreStartCategory.first(), preferences.exploreLastCategory.first())
                if (started) return@launch
                _uiState.update { it.copy(selected = start) }
                load(start)
            }
        }

        fun select(destination: ExploreDestination) {
            if (_uiState.value.selected == destination) return
            viewModelScope.launch { preferences.setExploreLastCategory(destination.name) }
            _uiState.update {
                CategoriesUiState(
                    selected = destination,
                    sectionKind = destination.kind,
                    isListView = it.isListView,
                )
            }
            load(destination)
        }

        /** Opens a shelf's "see all" as a paged grid, keeping the destination tab selected. */
        fun openShelf(shelf: FeedShelf) {
            val params = shelf.moreParams ?: return
            _uiState.update {
                it.copy(
                    sectionKind = ExploreSectionKind.GRID,
                    openShelfTitle = shelf.title,
                    error = null,
                )
            }
            gridKey.value = GridKey(_uiState.value.selected.browseId, params)
        }

        /** Back out of a "see all" to the destination's shelves, which are still in state. */
        fun closeShelf() {
            if (_uiState.value.openShelfTitle == null) return
            _uiState.update { it.copy(sectionKind = ExploreSectionKind.SHELVES, openShelfTitle = null) }
        }

        fun selectSubTab(subTab: CategorySubTab) {
            if (_uiState.value.selectedSubTab == subTab.title) return
            _uiState.update { it.copy(selectedSubTab = subTab.title) }
            load(_uiState.value.selected, params = subTab.params)
        }

        fun toggleViewMode() {
            val next = !_uiState.value.isListView
            _uiState.update { it.copy(isListView = next) }
            viewModelScope.launch { preferences.setCategoriesIsListView(next) }
        }

        fun refresh() {
            val state = _uiState.value
            cache.remove(CacheKey(state.selected, state.subTabParams()))
            load(state.selected, params = state.subTabParams())
        }

        fun setRegion(region: String) {
            viewModelScope.launch {
                preferences.setTrendingRegion(region)
                // Every destination honours `gl`, so none of what is held still describes this region.
                cache.clear()
                load(_uiState.value.selected, params = _uiState.value.subTabParams())
            }
        }

        private fun load(
            destination: ExploreDestination,
            params: String? = destination.params,
        ) {
            loadJob?.cancel()
            started = true
            val key = CacheKey(destination, params)
            val cached = cache[key]
            _uiState.update {
                it.copy(
                    sectionKind = destination.kind,
                    isLoading = cached == null,
                    error = null,
                    openShelfTitle = null,
                    shelves = cached?.shelves.orEmpty(),
                    chartEntries = cached?.chartEntries.orEmpty(),
                    chartTitle = cached?.chartTitle,
                    // A sub-tab switch reloads within a destination, whose tab row outlives it.
                    subTabs = cached?.subTabs?.takeIf(List<CategorySubTab>::isNotEmpty) ?: it.subTabs,
                    selectedSubTab = cached?.selectedSubTab ?: it.selectedSubTab,
                )
            }
            if (cached != null && cached.isFresh) return
            loadJob =
                viewModelScope.launch {
                    when (destination.kind) {
                        ExploreSectionKind.CHART -> {
                            loadChart(key)
                        }

                        ExploreSectionKind.GRID -> {
                            loadGrid(destination, params)
                        }

                        ExploreSectionKind.SHELVES -> {
                            loadShelves(destination, params, key, progressive = cached == null)
                        }
                    }
                }
        }

        /**
         * [progressive] is on only for a cold load, where each shelf is worth showing the moment it
         * is mapped. Revalidating behind a cached page instead swaps the lot in once it has settled,
         * so shelves already on screen do not shuffle under the reader.
         */
        private suspend fun loadShelves(
            destination: ExploreDestination,
            params: String?,
            key: CacheKey,
            progressive: Boolean,
        ) {
            var settled: ExploreDestinationPage? = null
            runCatching {
                YouTube.exploreDestination(destination.browseId, params).collect { page ->
                    settled = page
                    if (progressive) applyPage(page)
                }
            }.onFailure { error ->
                // Switching tab cancels this load, and runCatching catches that like any other
                // failure. Rethrowing keeps the abandoned tab from both reporting itself onto the
                // screen the reader moved to and caching the half of the page it had managed.
                if (error is CancellationException) throw error
                if (_uiState.value.shelves.isEmpty()) failed(error)
                return
            }

            val page = settled ?: return
            applyPage(page)
            if (page.shelves.isNotEmpty()) {
                cache[key] =
                    CachedSection(
                        shelves = page.shelves,
                        subTabs = _uiState.value.subTabs,
                        selectedSubTab = _uiState.value.selectedSubTab,
                    )
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = if (page.shelves.isEmpty()) context.getString(R.string.error_no_videos_for_category) else null,
                )
            }
        }

        /** Every emission carries one shelf more than the last, so the page fills as it is mapped. */
        private fun applyPage(page: ExploreDestinationPage) {
            _uiState.update {
                it.copy(
                    shelves = page.shelves,
                    subTabs = page.tabs.map { tab -> CategorySubTab(tab.title, tab.params) },
                    selectedSubTab = it.selectedSubTab ?: page.tabs.firstOrNull { tab -> tab.selected }?.title,
                    isLoading = it.isLoading && page.shelves.isEmpty(),
                )
            }
        }

        private fun loadGrid(
            destination: ExploreDestination,
            params: String?,
        ) {
            gridKey.value = params?.let { GridKey(destination.browseId, it) }
            _uiState.update { it.copy(isLoading = false) }
        }

        private suspend fun loadChart(key: CacheKey) {
            val region = preferences.trendingRegion.first()
            YouTube
                .musicVideoChart(region)
                .onSuccess { chart ->
                    _uiState.update {
                        it.copy(
                            chartEntries = chart.entries,
                            chartTitle = chart.title.takeIf(String::isNotBlank),
                            isLoading = false,
                            error = if (chart.entries.isEmpty()) context.getString(R.string.error_no_videos_for_category) else null,
                        )
                    }
                    if (chart.entries.isNotEmpty()) {
                        cache[key] = CachedSection(chartEntries = chart.entries, chartTitle = chart.title.takeIf(String::isNotBlank))
                    }
                    enrichChartAvatars(chart.entries, key)
                }.onFailure {
                    if (_uiState.value.chartEntries.isEmpty()) failed(it)
                }
        }

        /** A chart entry names its channel but carries no avatar, so the rows fill in after paint. */
        private fun enrichChartAvatars(
            entries: List<Video>,
            key: CacheKey,
        ) {
            if (entries.isEmpty()) return
            viewModelScope.launch {
                val enriched = runCatching { repository.enrichVideosWithAvatars(entries) }.getOrNull() ?: return@launch
                if (enriched === entries) return@launch
                cache[key]?.let { cache[key] = it.copy(chartEntries = enriched) }
                _uiState.update { state ->
                    if (state.chartEntries.map(Video::id) == entries.map(Video::id)) {
                        state.copy(chartEntries = enriched)
                    } else {
                        state
                    }
                }
            }
        }

        private fun failed(error: Throwable) {
            // A Result from a cancelled call carries the cancellation, which is not news to report.
            if (error is CancellationException) return
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = context.getString(LoadErrorKind.of(error).messageRes(R.string.error_failed_to_load_videos)),
                )
            }
        }

        private fun CategoriesUiState.subTabParams(): String? =
            subTabs.firstOrNull { it.title == selectedSubTab }?.params ?: selected.params
    }

private const val PAGE_SIZE = 20
private const val PREFETCH_DISTANCE = 6
private const val SUBSCRIPTION_GRACE_MS = 5_000L
private const val CACHE_TTL_MS = 5 * 60 * 1000L
private const val CACHE_ENTRIES = 7
private const val CACHE_LOAD_FACTOR = 0.75f
