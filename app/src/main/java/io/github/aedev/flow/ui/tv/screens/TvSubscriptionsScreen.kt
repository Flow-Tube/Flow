package io.github.aedev.flow.ui.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.data.local.HomeFeedColumns
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.screens.subscriptions.SubscriptionGroupDialogsHost
import io.github.aedev.flow.ui.screens.subscriptions.SubscriptionsEmptyState
import io.github.aedev.flow.ui.screens.subscriptions.SubscriptionsFeedContent
import io.github.aedev.flow.ui.screens.subscriptions.SubscriptionsFeedEffects
import io.github.aedev.flow.ui.screens.subscriptions.SubscriptionsManageContent
import io.github.aedev.flow.ui.screens.subscriptions.SubscriptionsViewModel
import io.github.aedev.flow.ui.screens.subscriptions.rememberSubscriptionChannels
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens

/** Grid cells sit edge to edge and each card insets itself, so this much of the margin is already there. */
private val CardInset = 16.dp

/**
 * TV Subscriptions: the phone's feed (quick access avatars, groups, refresh status, the video grid),
 * its channel list behind "View all", and its group dialogs. Selecting Subscriptions on the rail again
 * scrolls up and refreshes, as reselecting the tab does on a phone.
 */
@Composable
fun TvSubscriptionsScreen(
    viewModel: SubscriptionsViewModel,
    onVideoClick: (Video) -> Unit,
    onChannelClick: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalTvDimens.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val feedGridState = rememberLazyGridState()
    var isManagingSubs by remember { mutableStateOf(false) }
    var manageShowsMusic by rememberSaveable { mutableStateOf(false) }
    var showGroupsDialog by remember { mutableStateOf(false) }
    val channels = rememberSubscriptionChannels(uiState)

    SubscriptionsFeedEffects(
        viewModel = viewModel,
        feedGridState = feedGridState,
        videos = uiState.recentVideos,
        isManagingSubs = isManagingSubs,
    )
    BackHandler(enabled = isManagingSubs) { isManagingSubs = false }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .padding(top = dimens.overscanVertical, end = dimens.overscanHorizontal - CardInset),
    ) {
        when {
            isManagingSubs -> {
                SubscriptionsManageContent(
                    channels = channels.sorted,
                    searchQuery = "",
                    notificationStates = uiState.notificationStates,
                    excludedShortsChannelIds = uiState.excludedShortsChannelIds,
                    onChannelClick = onChannelClick,
                    onNotificationChange = viewModel::updateNotificationState,
                    onShortsExcludeChange = viewModel::setShortsChannelExcluded,
                    onUnsubscribe = { channel -> viewModel.unsubscribe(channel.id) },
                    showsMusic = manageShowsMusic,
                    onShowsMusicChange = { manageShowsMusic = it },
                )
            }

            uiState.subscribedChannels.isEmpty() -> {
                SubscriptionsEmptyState(modifier = Modifier.fillMaxSize())
            }

            else -> {
                SubscriptionsFeedContent(
                    state = uiState,
                    videos = uiState.recentVideos,
                    topChannels = channels.quickAccess,
                    gridState = feedGridState,
                    columnPreference = HomeFeedColumns.AUTO,
                    onRefresh = viewModel::refreshFeed,
                    onVideoClick = onVideoClick,
                    onShortClick = {},
                    onChannelClick = onChannelClick,
                    onViewAllClick = {
                        manageShowsMusic = false
                        isManagingSubs = true
                    },
                    onMusicSubscriptionsClick = {
                        manageShowsMusic = true
                        isManagingSubs = true
                    },
                    onGroupSelected = viewModel::selectGroup,
                    onManageGroups = { showGroupsDialog = true },
                    onRetryFailedChannels = viewModel::retryFailedChannels,
                    onDismissFailedChannels = viewModel::dismissFailedChannels,
                    maxAutoColumns = dimens.feedMaxColumns,
                    minCellWidth = dimens.feedCardMinWidth,
                    showShorts = false,
                    pinnedCount = channels.pinnedCount,
                    onTogglePin = { viewModel.togglePin(it.id) },
                )
            }
        }
    }

    if (showGroupsDialog) {
        SubscriptionGroupDialogsHost(
            viewModel = viewModel,
            uiState = uiState,
            onDismiss = { showGroupsDialog = false },
        )
    }
}
