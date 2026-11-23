package io.github.aedev.flow.ui.screens.home

import android.os.SystemClock
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.shorts.queue.ShortsQueueSource
import io.github.aedev.flow.ui.TabScrollEventBus
import io.github.aedev.flow.ui.components.FeedGridLayout
import io.github.aedev.flow.ui.components.shared.BackOnlineEffect
import io.github.aedev.flow.ui.components.shared.FeedGridSkeleton
import io.github.aedev.flow.ui.components.shared.LocalNetworkOnline
import io.github.aedev.flow.ui.screens.home.chips.HomeChipContent
import io.github.aedev.flow.ui.screens.home.chips.HomeChipsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val SKELETON_CARD_COUNT = 12

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

    // Viewport impressions, counted per card while Home is resumed (#1565).
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(gridState, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val dwell = ImpressionDwell()
            snapshotFlow { gridState.layoutInfo.halfVisibleKeys() }
                .distinctUntilChanged()
                .collectLatest { visible ->
                    while (true) {
                        val now = SystemClock.uptimeMillis()
                        val due = dwell.update(visible, now)
                        if (due.isNotEmpty()) viewModel.recordImpressions(due)
                        delay(dwell.millisUntilNextDue(now) ?: break)
                    }
                }
        }
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
                TabScrollEventBus.emitScrolledToTop("home")
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
    onOpenDownloads: (() -> Unit)? = null,
    showShorts: Boolean = true,
    topPadding: Dp = 0.dp,
    headerOffsetPx: () -> Float = { 0f },
) {
    val context = LocalContext.current
    val belowHeader = Modifier.padding(top = topPadding)
    val statusBars = WindowInsets.statusBars
    val scope = rememberCoroutineScope()
    val newVideosButtonEnabled by viewModel.newVideosButtonEnabled.collectAsStateWithLifecycle()
    val online by LocalNetworkOnline.current
    when {
        !homeFeedEnabled -> {
            HomeFeedDisabledState(modifier = belowHeader.fillMaxSize())
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
                topPadding = topPadding,
            )
        }

        uiState.isLoading && uiState.videos.isEmpty() -> {
            FeedGridSkeleton(
                layout = feedLayout,
                listMode = isListView,
                placeholderCount = SKELETON_CARD_COUNT,
                modifier = belowHeader,
            )
        }

        // Offline, every source comes back empty and the feed settles with no error at all, which
        // left a blank page; no connection and nothing to show is the error.
        uiState.videos.isEmpty() && (uiState.error != null || !online) -> {
            BackOnlineEffect { viewModel.retry() }
            HomeErrorState(
                error = uiState.error ?: stringResource(R.string.error_no_internet),
                onRetry = { viewModel.retry() },
                onDownloadClick = onVideoClick,
                onOpenDownloads = onOpenDownloads,
                modifier = belowHeader,
            )
        }

        else -> {
            ReportDrawnWhen {
                uiState.videos.isNotEmpty() ||
                    uiState.shorts.isNotEmpty() ||
                    uiState.continueWatchingVideos.isNotEmpty()
            }

            // Cards already on screen stay put when the connection returns; the pill offers the fresh feed.
            var backOnline by remember { mutableStateOf(false) }
            BackOnlineEffect { backOnline = true }
            LaunchedEffect(uiState.isRefreshing, uiState.pendingFeed) {
                if (uiState.isRefreshing || uiState.pendingFeed != null) backOnline = false
            }
            Box(modifier = Modifier.fillMaxSize()) {
                HomeFeedGrid(
                    uiState = uiState,
                    feedLayout = feedLayout,
                    isListView = isListView,
                    gridState = gridStates.feed,
                    onVideoClick = onVideoClick,
                    onEnrichChannelMetadata = null,
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
                    topPadding = topPadding,
                )
                HomeNewVideosButton(
                    visible = uiState.pendingFeed != null && newVideosButtonEnabled,
                    onClick = {
                        viewModel.showNewVideos()
                        scope.launch { gridStates.feed.scrollToItem(0) }
                    },
                    // Rides up with the header as it scrolls away, stopping under the status bar.
                    modifier =
                        Modifier.align(Alignment.TopCenter).offset {
                            val top = topPadding.toPx() + headerOffsetPx()
                            IntOffset(0, top.roundToInt().coerceAtLeast(statusBars.getTop(this)))
                        },
                )
                HomeNewVideosButton(
                    visible = backOnline && uiState.pendingFeed == null,
                    onClick = {
                        backOnline = false
                        viewModel.refreshFeed()
                    },
                    label = stringResource(R.string.home_back_online_refresh),
                    icon = Icons.Rounded.Refresh,
                    modifier =
                        Modifier.align(Alignment.TopCenter).offset {
                            val top = topPadding.toPx() + headerOffsetPx()
                            IntOffset(0, top.roundToInt().coerceAtLeast(statusBars.getTop(this)))
                        },
                )
            }
        }
    }
}
