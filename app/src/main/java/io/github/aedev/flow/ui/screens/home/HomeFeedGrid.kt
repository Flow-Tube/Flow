package io.github.aedev.flow.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.VideoHistoryEntry
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.FeedGridLayout
import io.github.aedev.flow.ui.components.home.ContinueWatchingShelf
import io.github.aedev.flow.ui.components.home.ContinueWatchingShelfDefaults
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.shared.FlowFeedProgress
import io.github.aedev.flow.ui.components.shared.MediaShortsShelf
import io.github.aedev.flow.ui.components.shared.card.MediaVideoCard
import io.github.aedev.flow.ui.components.shared.card.VideoCardLayout
import io.github.aedev.flow.ui.components.shared.dpadFocusGroup
import io.github.aedev.flow.ui.components.shared.feedStripCardWidth
import io.github.aedev.flow.ui.components.shared.rememberFeedGridPlan

private const val FEED_FOOTER_MIN_VIDEOS = 100
private val FeedTopPadding = 4.dp

@Composable
internal fun HomeFeedGrid(
    uiState: HomeUiState,
    feedLayout: FeedGridLayout,
    isListView: Boolean,
    gridState: LazyGridState,
    onVideoClick: (Video) -> Unit,
    onEnrichChannelMetadata: (Video) -> Unit,
    onContinueWatchingClick: (VideoHistoryEntry) -> Unit,
    onContinueWatchingRemove: (String) -> Unit,
    onShortClick: (List<Video>, Video) -> Unit,
    onSeeAllHistory: () -> Unit,
    onOpenShortsFeed: () -> Unit,
    onShortsShown: (ids: List<String>) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryLoadMore: () -> Unit = {},
    footerMinVideos: Int = FEED_FOOTER_MIN_VIDEOS,
    showShorts: Boolean = true,
) {
    val videos = uiState.videos
    val channelMemoryReason = stringResource(R.string.home_reason_channel_memory)
    val rows =
        remember(videos, feedLayout.columns, uiState.continueWatchingVideos.isNotEmpty(), showShorts && uiState.shorts.isNotEmpty()) {
            homeFeedRows(
                videos = videos,
                columns = feedLayout.columns,
                showContinueWatching = uiState.continueWatchingVideos.isNotEmpty(),
                showShorts = showShorts && uiState.shorts.isNotEmpty(),
            )
        }
    val plan =
        rememberFeedGridPlan(
            layout = feedLayout,
            listMode = isListView,
            itemCount = rows.size,
            spansOwnRow = { rows[it].spansRow },
            includeLastRun = !uiState.hasMorePages,
            itemsKey = rows,
            compactRowSpacing = if (isListView) 0.dp else feedLayout.cardSpacing,
        )
    val stripCardWidth = feedStripCardWidth(feedLayout.rowWidth, ContinueWatchingShelfDefaults.CardWidth)

    LazyVerticalGrid(
        columns = plan.cells,
        modifier =
            modifier
                .fillMaxSize()
                .dpadFocusGroup()
                .testTag("home_feed"),
        state = gridState,
        contentPadding = plan.contentPadding(top = FeedTopPadding, bottom = flowBottomContentPadding()),
        verticalArrangement = Arrangement.spacedBy(plan.rowSpacing),
    ) {
        itemsIndexed(
            items = rows,
            key = { _, row -> row.key },
            contentType = { _, row -> row::class },
            span = { index, row -> plan.span(index, row.spansRow, maxLineSpan) },
        ) { index, row ->
            when (row) {
                is HomeFeedRow.VideoCard -> {
                    LaunchedEffect(row.video.id, row.video.channelId, row.video.channelThumbnailUrl) {
                        onEnrichChannelMetadata(row.video)
                    }
                    MediaVideoCard(
                        video = row.video,
                        layout = if (plan.isListCard(index)) VideoCardLayout.Row else VideoCardLayout.Stacked,
                        onClick = { onVideoClick(row.video) },
                        useInternalPadding = !feedLayout.cardsBleed,
                        thumbnailWidth = plan.listThumbnailWidth,
                        density = plan.cardDensity,
                        reason = channelMemoryReason.takeIf { uiState.origins[row.video.id]?.kind == HomeCardOrigin.Kind.CHANNEL_MEMORY },
                        whyShown = uiState.origins[row.video.id]?.label(),
                        modifier = Modifier.testTag("home_video_card"),
                    )
                }

                HomeFeedRow.ContinueWatching -> {
                    ContinueWatchingShelf(
                        entries = uiState.continueWatchingVideos,
                        onVideoClick = { videoId ->
                            uiState.continueWatchingVideos
                                .find { it.videoId == videoId }
                                ?.let(onContinueWatchingClick)
                        },
                        onRemove = onContinueWatchingRemove,
                        onSeeAllClick = onSeeAllHistory,
                        cardWidth = stripCardWidth,
                        modifier = Modifier.testTag("home_continue_watching_shelf"),
                    )
                }

                HomeFeedRow.Shorts -> {
                    MediaShortsShelf(
                        shorts = uiState.shorts,
                        onShortClick = onShortClick,
                        onSeeAllClick = onOpenShortsFeed,
                        onShortsShown = onShortsShown,
                        modifier = Modifier.testTag("home_shorts_shelf"),
                    )
                }
            }
        }

        if (uiState.isLoadingMore) {
            item(
                key = "loading_indicator",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                FlowFeedProgress()
            }
        }

        if (uiState.loadMoreFailed && !uiState.isLoadingMore) {
            item(
                key = "load_more_failed",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                HomeLoadMoreFailed(onRetry = onRetryLoadMore)
            }
        }

        if (!uiState.hasMorePages && videos.size > footerMinVideos && !uiState.isLoadingMore) {
            item(
                key = "feed_footer",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                FlowFeedFooter(
                    videoCount = videos.size,
                    onRefresh = onRefresh,
                )
            }
        }
    }
}
