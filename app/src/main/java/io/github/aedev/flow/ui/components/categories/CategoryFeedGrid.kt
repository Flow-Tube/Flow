package io.github.aedev.flow.ui.components.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.pages.renderer.FeedItem
import io.github.aedev.flow.ui.components.FeedGridLayout
import io.github.aedev.flow.ui.components.PlaylistCard
import io.github.aedev.flow.ui.components.PlaylistCardLayout
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.shared.FeedGridSkeleton
import io.github.aedev.flow.ui.components.shared.FeedGridTopPadding
import io.github.aedev.flow.ui.components.shared.FeedPagingFooter
import io.github.aedev.flow.ui.components.shared.FlowEmptyState
import io.github.aedev.flow.ui.components.shared.FlowErrorState
import io.github.aedev.flow.ui.components.shared.card.MediaVideoCard
import io.github.aedev.flow.ui.components.shared.card.VideoCardLayout
import io.github.aedev.flow.ui.components.shared.rememberFeedGridPlan

/** A destination shelf's "see all", paged. */
@Composable
internal fun CategoryPagedGrid(
    pagingItems: LazyPagingItems<FeedItem>,
    gridState: LazyGridState,
    feedLayout: FeedGridLayout,
    isListView: Boolean,
    onVideoClick: (Video) -> Unit,
    onPlaylistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The pager owns the first page, so its own refresh is the only thing that knows how this tab
    // is doing — the screen's flag was cleared as soon as the pager was handed its key, and the
    // footer speaks for the append. Without this the tab is a blank screen while it loads, and
    // stays one if the first page fails or comes back empty.
    val refresh = pagingItems.loadState.refresh
    if (pagingItems.itemCount == 0) {
        when {
            refresh is LoadState.Loading -> {
                FeedGridSkeleton(layout = feedLayout, listMode = isListView, modifier = modifier)
            }

            refresh is LoadState.Error -> {
                FlowErrorState(
                    error = stringResource(categoryErrorRes(refresh.error)),
                    onRetry = pagingItems::retry,
                    modifier = modifier,
                )
            }

            refresh.endOfPaginationReached -> {
                FlowEmptyState(
                    title = stringResource(R.string.error_no_videos_for_category),
                    modifier = modifier,
                )
            }
        }
        return
    }

    val plan =
        rememberFeedGridPlan(
            layout = feedLayout,
            listMode = isListView,
            itemCount = pagingItems.itemCount,
            spansOwnRow = { false },
            includeLastRun = pagingItems.loadState.append.endOfPaginationReached,
            itemsKey = pagingItems.itemSnapshotList,
        )

    LazyVerticalGrid(
        columns = plan.cells,
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = plan.contentPadding(top = FeedGridTopPadding, bottom = flowBottomContentPadding()),
        verticalArrangement = Arrangement.spacedBy(plan.rowSpacing),
    ) {
        items(
            count = pagingItems.itemCount,
            key = { index -> pagingItems.peek(index)?.gridKey() ?: "placeholder:$index" },
            contentType = { index -> pagingItems.peek(index)?.gridContentType() ?: "placeholder" },
            span = { index -> plan.span(index, spansOwnRow = false, maxLineSpan = maxLineSpan) },
        ) { index ->
            when (val item = pagingItems[index]) {
                is FeedItem.VideoItem, is FeedItem.ShortItem -> {
                    val video = item.gridVideo()
                    MediaVideoCard(
                        video = video,
                        layout = if (plan.isListCard(index)) VideoCardLayout.Row else VideoCardLayout.Stacked,
                        onClick = { onVideoClick(video) },
                        thumbnailWidth = plan.listThumbnailWidth,
                        density = plan.cardDensity,
                    )
                }

                is FeedItem.PlaylistItem -> {
                    PlaylistCard(
                        playlist = item.playlist,
                        onClick = { onPlaylistClick(item.playlist.id) },
                        layout = if (plan.isListCard(index)) PlaylistCardLayout.LIST else PlaylistCardLayout.SHELF,
                        modifier = if (plan.isListCard(index)) Modifier else Modifier.padding(horizontal = plan.cardInset),
                    )
                }

                else -> {
                    Unit
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            FeedPagingFooter(
                appendState = pagingItems.loadState.append,
                itemCount = pagingItems.itemCount,
                onRetry = pagingItems::retry,
            )
        }
    }
}

/** A chart: 30 ranked entries and no next page, in the same grid the paged surfaces use. */
@Composable
internal fun CategoryChartGrid(
    entries: List<Video>,
    title: String?,
    gridState: LazyGridState,
    feedLayout: FeedGridLayout,
    isListView: Boolean,
    onVideoClick: (Video) -> Unit,
    modifier: Modifier = Modifier,
) {
    val plan =
        rememberFeedGridPlan(
            layout = feedLayout,
            listMode = isListView,
            itemCount = entries.size,
            spansOwnRow = { false },
            includeLastRun = true,
            itemsKey = entries,
        )

    LazyVerticalGrid(
        columns = plan.cells,
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = plan.contentPadding(top = FeedGridTopPadding, bottom = flowBottomContentPadding()),
        verticalArrangement = Arrangement.spacedBy(plan.rowSpacing),
    ) {
        if (title != null) {
            item(key = "chart-title", span = { GridItemSpan(maxLineSpan) }, contentType = "chart-title") {
                CategoryChartHeader(title = title)
            }
        }
        itemsIndexed(
            items = entries,
            key = { _, video -> video.id },
            contentType = { _, _ -> "video" },
            span = { index, _ -> plan.span(index, spansOwnRow = false, maxLineSpan = maxLineSpan) },
        ) { index, video ->
            MediaVideoCard(
                video = video,
                layout = if (plan.isListCard(index)) VideoCardLayout.Row else VideoCardLayout.Stacked,
                onClick = { onVideoClick(video) },
                thumbnailWidth = plan.listThumbnailWidth,
            )
        }
    }
}

@Composable
private fun CategoryChartHeader(title: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ChartHeaderHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(ChartHeaderLineSpacing),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = stringResource(R.string.categories_chart_source),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val ChartHeaderHorizontalPadding = 16.dp
private val ChartHeaderLineSpacing = 2.dp

private fun FeedItem?.gridVideo(): Video =
    when (this) {
        is FeedItem.VideoItem -> video
        is FeedItem.ShortItem -> video
        else -> error("not a video item")
    }

private fun FeedItem.gridKey(): String =
    when (this) {
        is FeedItem.VideoItem -> "v:${video.id}"
        is FeedItem.ShortItem -> "s:${video.id}"
        is FeedItem.PlaylistItem -> "p:${playlist.id}"
        is FeedItem.RelatedChannelItem -> "c:${channel.id}"
        is FeedItem.PostItem -> "b:${post.id}"
    }

private fun FeedItem.gridContentType(): String =
    when (this) {
        is FeedItem.VideoItem, is FeedItem.ShortItem -> "video"
        is FeedItem.PlaylistItem -> "playlist"
        is FeedItem.RelatedChannelItem -> "channel"
        is FeedItem.PostItem -> "post"
    }
