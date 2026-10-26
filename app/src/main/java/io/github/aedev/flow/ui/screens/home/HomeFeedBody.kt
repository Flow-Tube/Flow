package io.github.aedev.flow.ui.screens.home

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.shorts.queue.ShortsQueueSource
import io.github.aedev.flow.ui.TabScrollEventBus
import io.github.aedev.flow.ui.components.FeedGridLayout
import io.github.aedev.flow.ui.components.shared.FeedGridSkeleton
import io.github.aedev.flow.ui.components.shared.FlowErrorState
import io.github.aedev.flow.ui.screens.home.chips.HomeChipContent
import io.github.aedev.flow.ui.screens.home.chips.HomeChipsState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

private const val IMPRESSION_DEBOUNCE_MS = 500L
private const val SKELETON_CARD_COUNT = 12
private val NewVideosTopPadding = 8.dp
private val NewVideosIconSpacing = 8.dp

/** The main feed's scroll position and one per chip, each kept for the session. */
@Stable
internal class HomeGridStates(
    val feed: LazyGridState,
) {
    private val chipStates = HashMap<String, LazyGridState>()

    fun forChips(chips: HomeChipsState): LazyGridState = if (chips.isAll) feed else chipStates.getOrPut(chips.selected) { LazyGridState() }

    fun existingFor(chips: HomeChipsState): LazyGridState? = if (chips.isAll) feed else chipStates[chips.selected]
}

@Composable
internal fun rememberHomeGridStates(): HomeGridStates {
    val feed = rememberLazyGridState()
    return remember(feed) { HomeGridStates(feed) }
}

/**
 * What keeps the feed fed while Home shows: visibility, the viewport-driven prefetch, dwelt-in-view
 * impressions, and the tab reselect that scrolls up and then refreshes. Shared by the phone and TV Home.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun HomeFeedEffects(
    viewModel: HomeViewModel,
    uiState: HomeUiState,
    chips: HomeChipsState,
    gridStates: HomeGridStates,
    homeFeedEnabled: Boolean,
    refreshHomeOnReselect: Boolean,
) {
    val gridState = gridStates.feed
    val currentChips by rememberUpdatedState(chips)

    LifecycleStartEffect(viewModel, homeFeedEnabled) {
        if (homeFeedEnabled) {
            viewModel.onHomeVisible()
        } else {
            viewModel.onHomeHidden()
        }
        onStopOrDispose { viewModel.onHomeHidden() }
    }

    val videoIndexById =
        remember(uiState.videos) {
            buildMap(uiState.videos.size) {
                uiState.videos.forEachIndexed { index, video -> put(video.id, index) }
            }
        }
    // Pairing the index with the scroll state matters: a user parked on the last item keeps the
    // same index, so an index-only flow can never re-arm a prefetch that came back empty. Each
    // further scroll attempt toggles isScrollInProgress and gives the feed another chance.
    LaunchedEffect(gridState, videoIndexById) {
        snapshotFlow {
            var lastVisibleVideoIndex = -1
            gridState.layoutInfo.visibleItemsInfo.forEach { item ->
                val index = videoIndexById[item.key as? String] ?: return@forEach
                if (index > lastVisibleVideoIndex) lastVisibleVideoIndex = index
            }
            lastVisibleVideoIndex to gridState.isScrollInProgress
        }.distinctUntilChanged()
            .collect { (lastVisibleVideoIndex, _) ->
                viewModel.onHomeViewportChanged(lastVisibleVideoIndex)
            }
    }

    // Viewport impressions: only items dwelt in view are recorded as "shown".
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
            .debounce(IMPRESSION_DEBOUNCE_MS)
            .collect { viewModel.recordImpressions(it) }
    }

    LaunchedEffect(refreshHomeOnReselect) {
        TabScrollEventBus.scrollToTopEvents
            .filter { it == "home" }
            .collectLatest {
                // Down the feed a reselect only scrolls back up; at the top it refreshes (#1171).
                val grid = gridStates.existingFor(currentChips) ?: return@collectLatest
                val atTop = grid.firstVisibleItemIndex == 0 && grid.firstVisibleItemScrollOffset == 0
                if (!atTop) {
                    grid.animateScrollToItem(0)
                } else if (refreshHomeOnReselect) {
                    if (currentChips.isAll) viewModel.refreshFeed() else viewModel.refreshSelectedChip()
                }
            }
    }
}

/**
 * Home's feed below its chips: the selected chip's feed, or the main feed through its loading,
 * error and loaded states. [showShorts] false leaves out the Shorts shelf, as TV does.
 */
@Composable
internal fun HomeFeedBody(
    viewModel: HomeViewModel,
    uiState: HomeUiState,
    chips: HomeChipsState,
    gridStates: HomeGridStates,
    feedLayout: FeedGridLayout,
    isListView: Boolean,
    homeFeedEnabled: Boolean,
    onVideoClick: (Video) -> Unit,
    onPlayMix: (videos: List<Video>, title: String) -> Unit,
    onShortClick: (ShortsQueueSource) -> Unit,
    onNavigateToHistory: () -> Unit,
    onOpenShortsFeed: () -> Unit,
    showShorts: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    when {
        !homeFeedEnabled -> {
            HomeFeedDisabledState(modifier = Modifier.fillMaxSize())
        }

        !chips.isAll -> {
            HomeChipContent(
                state = chips,
                feedLayout = feedLayout,
                isListView = isListView,
                gridState = gridStates.forChips(chips),
                onVideoClick = onVideoClick,
                onPlayMix = { mix -> onPlayMix(mix.videos, context.getString(R.string.home_mix_title, mix.seed.title)) },
                onRefresh = viewModel::refreshSelectedChip,
                onEnrichVideo = viewModel::enrichChipVideo,
            )
        }

        uiState.isLoading && uiState.videos.isEmpty() -> {
            FeedGridSkeleton(
                layout = feedLayout,
                listMode = isListView,
                stackedInset = !feedLayout.cardsBleed,
                placeholderCount = SKELETON_CARD_COUNT,
                compactRowSpacing = if (isListView) 0.dp else feedLayout.cardSpacing,
            )
        }

        uiState.error != null && uiState.videos.isEmpty() -> {
            FlowErrorState(
                error = uiState.error ?: stringResource(R.string.error_occurred),
                onRetry = { viewModel.retry() },
            )
        }

        else -> {
            ReportDrawnWhen {
                uiState.videos.isNotEmpty() ||
                    uiState.shorts.isNotEmpty() ||
                    uiState.continueWatchingVideos.isNotEmpty()
            }

            Box(modifier = Modifier.fillMaxSize()) {
                HomeFeedGrid(
                    uiState = uiState,
                    feedLayout = feedLayout,
                    isListView = isListView,
                    gridState = gridStates.feed,
                    onVideoClick = onVideoClick,
                    onEnrichChannelMetadata = viewModel::enrichChannelMetadataIfMissing,
                    onContinueWatchingClick = { entry -> onVideoClick(entry.toResumeVideo()) },
                    onContinueWatchingRemove = viewModel::removeContinueWatchingEntry,
                    onShortClick = { shelf, tapped ->
                        onShortClick(viewModel.shortsShelfSource(shelf, tapped))
                    },
                    onSeeAllHistory = onNavigateToHistory,
                    onOpenShortsFeed = onOpenShortsFeed,
                    onShortsShown = viewModel::recordShelfImpressions,
                    onRefresh = { viewModel.refreshFeed() },
                    onRetryLoadMore = viewModel::retryLoadMore,
                    // Since v5 the feed can end at a few dozen cards; a list that just stops reads as broken (#1317).
                    footerMinVideos = 0,
                    showShorts = showShorts,
                )
                HomeNewVideosButton(
                    visible = uiState.pendingFeed != null,
                    onClick = {
                        viewModel.showNewVideos()
                        scope.launch { gridStates.feed.scrollToItem(0) }
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

/** Shown while a newer feed waits behind the cached cards of a launch (#1447). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeNewVideosButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) + scaleIn(MaterialTheme.motionScheme.defaultSpatialSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        modifier = modifier.padding(top = NewVideosTopPadding),
    ) {
        SmallExtendedFloatingActionButton(onClick = onClick, shape = CircleShape) {
            Icon(imageVector = Icons.Rounded.ArrowUpward, contentDescription = null)
            Spacer(Modifier.width(NewVideosIconSpacing))
            Text(text = stringResource(R.string.home_new_videos))
        }
    }
}
