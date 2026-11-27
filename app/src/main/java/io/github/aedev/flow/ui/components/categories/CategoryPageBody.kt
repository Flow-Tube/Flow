package io.github.aedev.flow.ui.components.categories

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.paging.compose.LazyPagingItems
import io.github.aedev.flow.data.local.HomeFeedColumns
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.pages.explore.ExploreSectionKind
import io.github.aedev.flow.innertube.pages.renderer.FeedItem
import io.github.aedev.flow.innertube.pages.renderer.FeedShelf
import io.github.aedev.flow.ui.components.FeedGridLayout
import io.github.aedev.flow.ui.components.shared.FeedGridSkeleton
import io.github.aedev.flow.ui.components.shared.FlowErrorState
import io.github.aedev.flow.ui.screens.categories.CategoriesUiState

/** One category page in whichever of its three shapes [state] holds, or its skeleton or error. */
@Composable
internal fun CategoryPageBody(
    state: CategoriesUiState,
    shelves: List<FeedShelf>,
    pagingItems: LazyPagingItems<FeedItem>,
    shelfState: LazyGridState,
    gridState: LazyGridState,
    feedLayout: FeedGridLayout,
    columnPreference: HomeFeedColumns,
    onRetry: () -> Unit,
    onVideoClick: (Video) -> Unit,
    onShortClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onShelfOpen: (FeedShelf) -> Unit,
) {
    val error = state.error
    when {
        state.isLoading -> {
            FeedGridSkeleton(layout = feedLayout, listMode = state.isListView)
        }

        error != null -> {
            FlowErrorState(error = error, onRetry = onRetry)
        }

        state.sectionKind == ExploreSectionKind.CHART -> {
            CategoryChartGrid(
                entries = state.chartEntries,
                title = state.chartTitle,
                gridState = gridState,
                feedLayout = feedLayout,
                isListView = state.isListView,
                onVideoClick = onVideoClick,
            )
        }

        state.sectionKind == ExploreSectionKind.GRID -> {
            CategoryPagedGrid(
                pagingItems = pagingItems,
                gridState = gridState,
                feedLayout = feedLayout,
                isListView = state.isListView,
                onVideoClick = onVideoClick,
                onPlaylistClick = onPlaylistClick,
            )
        }

        else -> {
            CategoryShelfPage(
                shelves = shelves,
                isLoading = state.isLoading,
                listState = shelfState,
                columnPreference = columnPreference,
                onVideoClick = onVideoClick,
                onShortClick = onShortClick,
                onPlaylistClick = onPlaylistClick,
                onShelfOpen = onShelfOpen,
            )
        }
    }
}
