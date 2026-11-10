package io.github.aedev.flow.ui.screens.subscriptions

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.TabScrollEventBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter

private const val QUICK_ACCESS_CHANNEL_LIMIT = 15
private const val STALE_REFRESH_INTERVAL_MS = 3 * 60 * 1000L

/** The channels in the user's sort order, and the pinned ones then the first of the rest for the quick access row. */
internal class SubscriptionChannels(
    val sorted: List<Channel>,
    private val quickAccessRow: QuickAccessChannels,
) {
    val quickAccess: List<Channel> get() = quickAccessRow.channels
    val pinnedCount: Int get() = quickAccessRow.pinnedCount
}

@Composable
internal fun rememberSubscriptionChannels(uiState: SubscriptionsUiState): SubscriptionChannels {
    val sorted =
        remember(uiState.subscribedChannels, uiState.sortMode, uiState.recentVideos, uiState.channelWatchCounts) {
            sortSubscriptions(uiState.subscribedChannels, uiState.sortMode, uiState.recentVideos, uiState.channelWatchCounts)
        }
    return remember(sorted, uiState.pinnedChannelIds) {
        SubscriptionChannels(sorted, quickAccessChannels(sorted, uiState.pinnedChannelIds, QUICK_ACCESS_CHANNEL_LIMIT))
    }
}

/**
 * What keeps the feed current while it shows: the first start, the stale check while resumed, the
 * tab reselect that scrolls up and refreshes, and the visible-video report the enrichment uses.
 * Shared by the phone and TV Subscriptions.
 */
@Composable
internal fun SubscriptionsFeedEffects(
    viewModel: SubscriptionsViewModel,
    feedGridState: LazyGridState,
    videos: List<Video>,
    isManagingSubs: Boolean,
) {
    LaunchedEffect(viewModel) { viewModel.ensureStarted() }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refreshIfStaleOrMissedUploads()
            while (true) {
                delay(STALE_REFRESH_INTERVAL_MS)
                viewModel.refreshIfStaleOrMissedUploads()
            }
        }
    }

    LaunchedEffect(Unit) {
        TabScrollEventBus.scrollToTopEvents
            .filter { it == "subscriptions" }
            .collectLatest {
                feedGridState.animateScrollToItem(0)
                TabScrollEventBus.emitScrolledToTop("subscriptions")
                viewModel.refreshFeed()
            }
    }

    LaunchedEffect(feedGridState, videos, isManagingSubs) {
        if (isManagingSubs) {
            viewModel.updateVisibleVideoIds(emptySet())
            return@LaunchedEffect
        }

        val feedVideoIds = videos.mapTo(HashSet(videos.size)) { it.id }
        snapshotFlow {
            feedGridState.layoutInfo.visibleItemsInfo
                .mapNotNull { item -> item.key as? String }
                .toSet()
        }.collectLatest { visibleKeys ->
            viewModel.updateVisibleVideoIds(visibleKeys.filterTo(HashSet()) { it in feedVideoIds })
        }
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.updateVisibleVideoIds(emptySet()) }
    }
}
