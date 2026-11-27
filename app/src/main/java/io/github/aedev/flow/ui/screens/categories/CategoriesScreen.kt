package io.github.aedev.flow.ui.screens.categories

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.List
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.HomeFeedColumns
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.pages.explore.ExploreSectionKind
import io.github.aedev.flow.ui.OnTabReselected
import io.github.aedev.flow.ui.components.categories.CategoryPageBody
import io.github.aedev.flow.ui.components.categories.CategorySubTabMenu
import io.github.aedev.flow.ui.components.categories.CategoryTabBar
import io.github.aedev.flow.ui.components.layout.navigation.FlowTab
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBar
import io.github.aedev.flow.ui.components.rememberFeedGridLayout
import io.github.aedev.flow.ui.components.shared.FlowChoice
import io.github.aedev.flow.ui.components.shared.FlowChoiceDialog
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation
import io.github.aedev.flow.utils.RegionCatalog
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Explore: one tab per YouTube destination, each rendering whichever of the three shapes its source
 * actually serves — a page of shelves, a paged grid, or a ranked chart. The destinations sit side by
 * side in a pager, but only the settled one is loaded.
 */
@Composable
fun CategoriesScreen(
    onVideoClick: (Video) -> Unit,
    onShortClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {},
    viewModel: CategoriesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val shelves by viewModel.visibleShelves.collectAsStateWithLifecycle()
    val trendingRegion by viewModel.trendingRegion.collectAsStateWithLifecycle()
    val pagingItems = viewModel.gridItems.collectAsLazyPagingItems()
    var showRegionDialog by rememberSaveable { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember(context) { PlayerPreferences(context) }
    val columnPreference by preferences.homeFeedColumns.collectAsStateWithLifecycle(HomeFeedColumns.AUTO)

    val selectedPage = CATEGORY_TABS.indexOfFirst { it.destination == uiState.selected }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = selectedPage) { CATEGORY_TABS.size }
    val scope = rememberCoroutineScope()
    val shelfStates = rememberPageGridStates()
    val gridStates = rememberPageGridStates()

    // Only a settled page loads, so a swipe across several categories fetches the last one alone. The
    // first value is the page the pager opened on, which the ViewModel picked itself.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.drop(1).collect { viewModel.select(CATEGORY_TABS[it].destination) }
    }
    // The ViewModel reads the start category after the pager exists, so the pager follows it there.
    LaunchedEffect(selectedPage) {
        if (pagerState.settledPage != selectedPage && !pagerState.isScrollInProgress) pagerState.scrollToPage(selectedPage)
    }

    OnTabReselected(FlowTab.Explore.route) {
        val page = pagerState.settledPage
        val isGridSection =
            uiState.sectionKind == ExploreSectionKind.CHART || uiState.sectionKind == ExploreSectionKind.GRID
        if (uiState.openShelfTitle == null && isGridSection) {
            gridStates[page].animateScrollToItem(0)
        } else {
            shelfStates[page].animateScrollToItem(0)
        }
    }

    BackHandler(enabled = uiState.openShelfTitle != null) { viewModel.closeShelf() }

    Scaffold(
        topBar = {
            FlowTopBar(
                title = uiState.openShelfTitle ?: stringResource(R.string.categories_title),
                onBack = uiState.openShelfTitle?.let { { viewModel.closeShelf() } },
                actions = {
                    IconButton(onClick = { showRegionDialog = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Language,
                            contentDescription =
                                stringResource(R.string.categories_region_picker_desc, trendingRegion),
                        )
                    }
                    IconButton(onClick = viewModel::toggleViewMode) {
                        Icon(
                            imageVector = if (uiState.isListView) Icons.Outlined.GridView else Icons.Outlined.List,
                            contentDescription =
                                if (uiState.isListView) {
                                    stringResource(R.string.categories_switch_to_grid)
                                } else {
                                    stringResource(R.string.categories_switch_to_list)
                                },
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (uiState.openShelfTitle == null) {
                CategoryTabBar(
                    selected = CATEGORY_TABS[pagerState.currentPage].destination,
                    onSelect = { destination ->
                        val page = CATEGORY_TABS.indexOfFirst { it.destination == destination }
                        scope.launch { pagerState.animateScrollToPage(page) }
                    },
                    modifier = Modifier.padding(vertical = ChipRowVerticalPadding),
                )
                CategorySubTabMenu(
                    subTabs = uiState.subTabs,
                    selected = uiState.selectedSubTab,
                    onSelect = viewModel::selectSubTab,
                    modifier = Modifier.padding(bottom = ChipRowVerticalPadding),
                )
            }

            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val feedLayout = rememberFeedGridLayout(maxWidth, columnPreference)
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    // A see-all grid belongs to its category, and a remote moves between chips instead.
                    userScrollEnabled = uiState.openShelfTitle == null && !LocalDpadNavigation.current,
                    key = { CATEGORY_TABS[it].destination.name },
                ) { page ->
                    val destination = CATEGORY_TABS[page].destination
                    val isSelected = destination == uiState.selected
                    val neighbour =
                        if (isSelected) {
                            null
                        } else {
                            remember(destination, uiState.selected, uiState.isListView) { viewModel.preview(destination) }
                        }
                    CategoryPageBody(
                        state = neighbour ?: uiState,
                        shelves = neighbour?.shelves ?: shelves,
                        pagingItems = pagingItems,
                        shelfState = shelfStates[page],
                        gridState = gridStates[page],
                        feedLayout = feedLayout,
                        columnPreference = columnPreference,
                        onRetry = viewModel::refresh,
                        onVideoClick = onVideoClick,
                        onShortClick = onShortClick,
                        onPlaylistClick = onPlaylistClick,
                        onShelfOpen = viewModel::openShelf,
                    )
                }
            }
        }
    }

    if (showRegionDialog) {
        val regionOptions = remember { RegionCatalog.sorted().map { (code, name) -> FlowChoice(code, name) } }
        FlowChoiceDialog(
            title = stringResource(R.string.settings_region_dialog_title),
            options = regionOptions,
            selected = trendingRegion,
            onSelect = viewModel::setRegion,
            onDismiss = { showRegionDialog = false },
            listMaxHeight = RegionDialogMaxHeight,
        )
    }
}

/** One scroll position per category, so a swipe back lands where that category was left. */
@Composable
private fun rememberPageGridStates(): List<LazyGridState> =
    CATEGORY_TABS.map { tab ->
        key(tab.destination.name) { rememberLazyGridState() }
    }

private val ChipRowVerticalPadding = 4.dp
private val RegionDialogMaxHeight = 260.dp
