package io.github.aedev.flow.ui.screens.home.chips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.FeedGridLayout
import io.github.aedev.flow.ui.components.PlaylistCard
import io.github.aedev.flow.ui.components.PlaylistCardLayout
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.shared.FeedGridSkeleton
import io.github.aedev.flow.ui.components.shared.FlowEmptyState
import io.github.aedev.flow.ui.components.shared.FlowErrorState
import io.github.aedev.flow.ui.components.shared.dpadFocusGroup
import io.github.aedev.flow.ui.screens.home.HomeFeedGrid
import io.github.aedev.flow.ui.screens.home.HomeUiState

private const val SKELETON_CARDS = 6
private val MixGridTopPadding = 4.dp

/** What the selected chip shows, in the same grid the All feed uses. */
@Composable
internal fun HomeChipContent(
    state: HomeChipsState,
    feedLayout: FeedGridLayout,
    isListView: Boolean,
    gridState: LazyGridState,
    onVideoClick: (Video) -> Unit,
    onPlayMix: (HomeMix) -> Unit,
    onRefresh: () -> Unit,
    onEnrichVideo: (Video) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val feed = state.feed) {
        null, ChipFeed.Loading -> {
            FeedGridSkeleton(
                layout = feedLayout,
                listMode = isListView,
                stackedInset = !feedLayout.cardsBleed,
                placeholderCount = SKELETON_CARDS,
                compactRowSpacing = if (isListView) 0.dp else feedLayout.cardSpacing,
                modifier = modifier,
            )
        }

        is ChipFeed.Videos -> {
            HomeFeedGrid(
                uiState = HomeUiState(videos = feed.videos, hasMorePages = false, isFlowFeed = true),
                feedLayout = feedLayout,
                isListView = isListView,
                gridState = gridState,
                onVideoClick = onVideoClick,
                onEnrichChannelMetadata = onEnrichVideo,
                onContinueWatchingClick = {},
                onContinueWatchingRemove = {},
                onShortClick = { _, _ -> },
                onSeeAllHistory = {},
                onOpenShortsFeed = {},
                onShortsShown = {},
                onRefresh = onRefresh,
                modifier = modifier,
            )
        }

        is ChipFeed.Mixes -> {
            HomeMixGrid(feed.mixes, feedLayout, isListView, gridState, onPlayMix, modifier)
        }

        ChipFeed.Empty -> {
            FlowEmptyState(
                title = stringResource(R.string.home_chip_empty_title),
                subtitle = stringResource(R.string.home_chip_empty_body),
                icon = Icons.Outlined.VideoLibrary,
                modifier = modifier.fillMaxSize(),
            )
        }

        ChipFeed.Failed -> {
            FlowErrorState(
                error = stringResource(R.string.home_chip_failed),
                onRetry = onRefresh,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun HomeMixGrid(
    mixes: List<HomeMix>,
    feedLayout: FeedGridLayout,
    isListView: Boolean,
    gridState: LazyGridState,
    onPlayMix: (HomeMix) -> Unit,
    modifier: Modifier = Modifier,
) {
    val horizontalPadding = if (isListView) feedLayout.listContentPadding else feedLayout.contentPadding
    LazyVerticalGrid(
        columns = feedLayout.cells,
        state = gridState,
        modifier = modifier.fillMaxSize().dpadFocusGroup(),
        contentPadding =
            PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = MixGridTopPadding,
                bottom = flowBottomContentPadding(),
            ),
        verticalArrangement = Arrangement.spacedBy(feedLayout.cardSpacing),
    ) {
        items(mixes, key = { it.seed.id }) { mix ->
            PlaylistCard(
                playlist =
                    PlaylistInfo(
                        id = mix.seed.id,
                        name = stringResource(R.string.home_mix_title, mix.seed.title),
                        description = mix.seed.channelName,
                        videoCount = mix.videos.size,
                        thumbnailUrl = mix.seed.thumbnailUrl,
                        isPrivate = false,
                        createdAt = 0L,
                    ),
                onClick = { onPlayMix(mix) },
                layout = if (isListView) PlaylistCardLayout.LIST else PlaylistCardLayout.SHELF,
            )
        }
    }
}
