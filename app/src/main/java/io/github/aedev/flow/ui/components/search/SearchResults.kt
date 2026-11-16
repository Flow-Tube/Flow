package io.github.aedev.flow.ui.components.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.paging.SearchResultItem
import io.github.aedev.flow.data.paging.SearchShelfKind
import io.github.aedev.flow.innertube.pages.renderer.CommunityPost
import io.github.aedev.flow.ui.components.FeedGridLayout
import io.github.aedev.flow.ui.components.PlaylistCard
import io.github.aedev.flow.ui.components.PlaylistCardLayout
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.shared.FeedGridTopPadding
import io.github.aedev.flow.ui.components.shared.FeedPagingFooter
import io.github.aedev.flow.ui.components.shared.MediaShortCard
import io.github.aedev.flow.ui.components.shared.ShortCardDefaults
import io.github.aedev.flow.ui.components.shared.card.MediaVideoCard
import io.github.aedev.flow.ui.components.shared.card.VideoCardLayout
import io.github.aedev.flow.ui.components.shared.dismissKeyboardOnPress
import io.github.aedev.flow.ui.components.shared.rememberFeedGridPlan

/** Every callback the result surfaces need, threaded through one object rather than nine parameters. */
data class SearchResultActions(
    val onVideoClick: (Video) -> Unit,
    val onShortsClick: (shelf: List<Video>, tapped: Video) -> Unit,
    val onChannelClick: (Channel) -> Unit,
    val onPlaylistClick: (Playlist) -> Unit,
    val dismissKeyboard: () -> Unit,
    val isSubscribed: (String) -> Boolean = { false },
    val onSubscribeToggle: (Channel) -> Unit = {},
    val isNotifying: (String) -> Boolean = { false },
    val onNotificationChange: (Channel, Boolean) -> Unit = { _, _ -> },
    val onPostClick: (CommunityPost) -> Unit = {},
)

@Composable
fun SearchResults(
    pagingItems: LazyPagingItems<SearchResultItem>,
    gridState: LazyGridState,
    feedLayout: FeedGridLayout,
    isGridMode: Boolean,
    actions: SearchResultActions,
    modifier: Modifier = Modifier,
) {
    // The toggle's stored flag is named for its icon: set means the thumbnail-left rows, which are a
    // single full-width column at every size.
    val plan =
        rememberFeedGridPlan(
            layout = feedLayout,
            listMode = isGridMode,
            itemCount = pagingItems.itemCount,
            spansOwnRow = { index -> pagingItems.peek(index).spansRow() },
            includeLastRun = pagingItems.loadState.append.endOfPaginationReached,
            itemsKey = pagingItems.itemSnapshotList,
        )

    LazyVerticalGrid(
        columns = plan.cells,
        state = gridState,
        modifier = modifier.fillMaxSize().dismissKeyboardOnPress(actions.dismissKeyboard),
        contentPadding = plan.contentPadding(top = FeedGridTopPadding, bottom = flowBottomContentPadding()),
        verticalArrangement = Arrangement.spacedBy(plan.rowSpacing),
    ) {
        items(
            count = pagingItems.itemCount,
            key = { index -> pagingItems.peek(index).itemKey(index) },
            contentType = { index -> pagingItems.peek(index).contentType() },
            span = { index -> plan.span(index, pagingItems.peek(index).spansRow(), maxLineSpan) },
        ) { index ->
            when (val item = pagingItems[index]) {
                is SearchResultItem.VideoResult -> {
                    MediaVideoCard(
                        video = item.video,
                        layout = if (plan.isListCard(index)) VideoCardLayout.Row else VideoCardLayout.Stacked,
                        onClick = { actions.onVideoClick(item.video) },
                        thumbnailWidth = plan.listThumbnailWidth,
                        density = plan.cardDensity,
                    )
                }

                is SearchResultItem.ChannelResult -> {
                    SearchChannelHeroCard(
                        channel = item.channel,
                        isSubscribed = actions.isSubscribed(item.channel.id),
                        onSubscribeToggle = { actions.onSubscribeToggle(item.channel) },
                        isNotificationsEnabled = actions.isNotifying(item.channel.id),
                        onNotificationChange = { actions.onNotificationChange(item.channel, it) },
                        onClick = { actions.onChannelClick(item.channel) },
                        latestTitle = item.latestTitle,
                        latestVideos = item.latestVideos,
                        onVideoClick = actions.onVideoClick,
                    )
                }

                is SearchResultItem.PlaylistResult -> {
                    PlaylistCard(
                        playlist = item.playlist,
                        onClick = { actions.onPlaylistClick(item.playlist) },
                        layout = if (plan.isListCard(index)) PlaylistCardLayout.LIST else PlaylistCardLayout.SHELF,
                        modifier = if (plan.isListCard(index)) Modifier else Modifier.padding(horizontal = plan.cardInset),
                    )
                }

                is SearchResultItem.ShelfResult -> {
                    SearchShelf(
                        shelf = item,
                        asThumbnailRows = isGridMode || !feedLayout.isCompact,
                        thumbnailWidth = plan.listThumbnailWidth,
                        onVideoClick = actions.onVideoClick,
                        onShortsClick = actions.onShortsClick,
                        onChannelClick = { actions.onChannelClick(Channel(it, "", "", 0)) },
                        onPostClick = actions.onPostClick,
                    )
                }

                null -> {
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

/** The Shorts tab is a portrait grid of its own, never mixed with long-form cards. */
@Composable
fun SearchShortsGrid(
    pagingItems: LazyPagingItems<SearchResultItem>,
    gridState: LazyGridState,
    feedLayout: FeedGridLayout,
    actions: SearchResultActions,
    modifier: Modifier = Modifier,
) {
    val sidePadding = ShortCardDefaults.gridPadding(feedLayout)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(ShortCardDefaults.MinWidth),
        state = gridState,
        modifier = modifier.fillMaxSize().dismissKeyboardOnPress(actions.dismissKeyboard),
        contentPadding =
            PaddingValues(
                start = sidePadding,
                end = sidePadding,
                top = FeedGridTopPadding,
                bottom = flowBottomContentPadding(),
            ),
        horizontalArrangement = Arrangement.spacedBy(ShortCardDefaults.Spacing),
        verticalArrangement = Arrangement.spacedBy(ShortCardDefaults.Spacing),
    ) {
        items(
            count = pagingItems.itemCount,
            key = { index -> pagingItems.peek(index).itemKey(index) },
            contentType = { index -> pagingItems.peek(index).contentType() },
        ) { index ->
            (pagingItems[index] as? SearchResultItem.VideoResult)?.let { result ->
                MediaShortCard(
                    video = result.video,
                    onClick = { actions.onShortsClick(pagingItems.loadedShorts(), result.video) },
                    modifier = Modifier.fillMaxSize(),
                )
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

private fun LazyPagingItems<SearchResultItem>.loadedShorts(): List<Video> =
    (0 until itemCount).mapNotNull { (peek(it) as? SearchResultItem.VideoResult)?.video }

/** The hero card and every strip own their row; only results share one. */
private fun SearchResultItem?.spansRow(): Boolean =
    when (this) {
        is SearchResultItem.ShelfResult, is SearchResultItem.ChannelResult -> true
        is SearchResultItem.VideoResult, is SearchResultItem.PlaylistResult, null -> false
    }

private fun SearchResultItem?.itemKey(index: Int): Any =
    when (this) {
        is SearchResultItem.VideoResult -> "video:${video.id}"
        is SearchResultItem.ChannelResult -> "channel:${channel.id}"
        is SearchResultItem.PlaylistResult -> "playlist:${playlist.id}"
        is SearchResultItem.ShelfResult -> "shelf:$id"
        null -> "placeholder:$index"
    }

/** Lets the grid reuse a composition when a slot is filled by another item of the same kind. */
private fun SearchResultItem?.contentType(): Any =
    when (this) {
        is SearchResultItem.VideoResult -> "video"
        is SearchResultItem.ChannelResult -> "channel"
        is SearchResultItem.PlaylistResult -> "playlist"
        is SearchResultItem.ShelfResult -> "shelf:${kind.name}"
        null -> "placeholder"
    }
