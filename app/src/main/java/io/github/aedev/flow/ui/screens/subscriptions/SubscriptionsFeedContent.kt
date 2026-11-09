package io.github.aedev.flow.ui.screens.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.local.HomeFeedColumns
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.shorts.queue.ShortsQueueSource
import io.github.aedev.flow.ui.components.FEED_MAX_AUTO_COLUMNS
import io.github.aedev.flow.ui.components.FeedCardMinWidth
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.rememberFeedGridLayout
import io.github.aedev.flow.ui.components.shared.FlowPullToRefreshBox
import io.github.aedev.flow.ui.components.shared.MediaShortsShelf
import io.github.aedev.flow.ui.components.shared.card.MediaVideoCard
import io.github.aedev.flow.ui.components.shared.card.VideoCardLayout
import io.github.aedev.flow.ui.components.shared.dpadFocusGroup
import io.github.aedev.flow.ui.components.shared.rememberFeedGridPlan

private val ShelfSpacing = 8.dp
private val ErrorCardPadding = 4.dp
private val FeedTopPadding = 4.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubscriptionsFeedContent(
    state: SubscriptionsUiState,
    videos: List<Video>,
    topChannels: List<Channel>,
    gridState: LazyGridState,
    columnPreference: HomeFeedColumns,
    onRefresh: () -> Unit,
    onVideoClick: (Video) -> Unit,
    onShortClick: (ShortsQueueSource) -> Unit,
    onChannelClick: (Channel) -> Unit,
    onViewAllClick: () -> Unit,
    onMusicSubscriptionsClick: () -> Unit,
    onGroupSelected: (String?) -> Unit,
    onManageGroups: () -> Unit,
    onRetryFailedChannels: () -> Unit,
    onDismissFailedChannels: () -> Unit,
    modifier: Modifier = Modifier,
    maxAutoColumns: Int = FEED_MAX_AUTO_COLUMNS,
    minCellWidth: Dp = FeedCardMinWidth,
    showShorts: Boolean = true,
    onPostsClick: (() -> Unit)? = null,
) {
    val pullRefreshState = rememberPullToRefreshState()

    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) {
            pullRefreshState.animateToHidden()
        }
    }

    FlowPullToRefreshBox(
        isRefreshing = state.isLoading,
        onRefresh = onRefresh,
        state = pullRefreshState,
        modifier = modifier.fillMaxSize(),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val feedLayout = rememberFeedGridLayout(maxWidth, columnPreference, maxAutoColumns, minCellWidth)
            val listMode = !state.isFullWidthView
            val plan =
                rememberFeedGridPlan(
                    layout = feedLayout,
                    listMode = listMode,
                    itemCount = videos.size,
                    spansOwnRow = { false },
                    includeLastRun = true,
                    itemsKey = videos,
                    compactRowSpacing = if (listMode) 0.dp else feedLayout.cardSpacing,
                )
            LazyVerticalGrid(
                columns = plan.cells,
                state = gridState,
                modifier = Modifier.fillMaxSize().dpadFocusGroup(),
                contentPadding = plan.contentPadding(top = FeedTopPadding, bottom = flowBottomContentPadding()),
                verticalArrangement = Arrangement.spacedBy(plan.rowSpacing),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        SubscriptionsFeedHeader(
                            state = state,
                            topChannels = topChannels,
                            onChannelClick = onChannelClick,
                            onViewAllClick = onViewAllClick,
                            onMusicSubscriptionsClick = onMusicSubscriptionsClick,
                            onGroupSelected = onGroupSelected,
                            onManageGroups = onManageGroups,
                            onPostsClick = onPostsClick,
                        )

                        SubscriptionFeedErrorCard(
                            failedChannelNames = state.failedChannelNames,
                            failedChannelIds = state.failedChannelIds,
                            failedChannelReasons = state.failedChannelReasons,
                            onRetry = onRetryFailedChannels,
                            onDismiss = onDismissFailedChannels,
                            modifier =
                                Modifier.padding(
                                    horizontal = GroupRowHorizontalPadding,
                                    vertical = ErrorCardPadding,
                                ),
                        )

                        SubscriptionsRefreshStatus(
                            stage = state.refreshStage,
                            processedChannels = state.refreshProcessedChannels,
                            totalChannels = state.refreshTotalChannels,
                            lastRefreshText = state.lastRefreshText,
                            lastRefreshVideoCount = state.lastRefreshVideoCount,
                            showLastRefreshVideoCount = state.showLastRefreshVideoCount,
                        )
                    }
                }

                if (showShorts && state.isShortsShelfEnabled && state.shorts.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            MediaShortsShelf(
                                shorts = state.shorts,
                                // The shelf shows one reel per channel; the queue behind it is
                                // every subscription reel in date order (#823).
                                onShortClick = { _, tapped ->
                                    onShortClick(ShortsQueueSource.Subscriptions(tapped.id))
                                },
                            )
                            Spacer(modifier = Modifier.height(ShelfSpacing))
                            HorizontalDivider()
                        }
                    }
                }

                itemsIndexed(
                    items = videos,
                    key = { _, video -> video.id },
                    contentType = { _, _ -> "video" },
                    span = { index, _ -> plan.span(index, spansOwnRow = false, maxLineSpan = maxLineSpan) },
                ) { index, video ->
                    MediaVideoCard(
                        video = video,
                        layout = if (plan.isListCard(index)) VideoCardLayout.Row else VideoCardLayout.Stacked,
                        onClick = { onVideoClick(video) },
                        useInternalPadding = !feedLayout.cardsBleed,
                        thumbnailWidth = plan.listThumbnailWidth,
                        density = plan.cardDensity,
                    )
                }
            }
        }
    }
}
