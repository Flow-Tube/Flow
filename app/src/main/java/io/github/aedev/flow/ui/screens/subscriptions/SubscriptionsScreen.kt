package io.github.aedev.flow.ui.screens.subscriptions

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.HomeFeedColumns
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.shorts.queue.ShortsQueueSource
import io.github.aedev.flow.ui.components.layout.LocalFlowBottomInsets
import io.github.aedev.flow.ui.components.layout.floatAboveBottomChrome
import io.github.aedev.flow.ui.components.layout.topbar.FlowSearchTopBar
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBar
import kotlinx.coroutines.launch

@Composable
fun SubscriptionsScreen(
    onVideoClick: (Video) -> Unit,
    onShortClick: (ShortsQueueSource) -> Unit = {},
    onChannelClick: (Channel) -> Unit = {},
    modifier: Modifier = Modifier,
    openMusicSubscriptions: Boolean = false,
    onMusicSubscriptionsOpened: () -> Unit = {},
    viewModel: SubscriptionsViewModel = sharedSubscriptionsViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val columnPreference by remember(context) { PlayerPreferences(context) }
        .homeFeedColumns
        .collectAsStateWithLifecycle(HomeFeedColumns.AUTO)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val feedGridState = rememberLazyGridState()
    val postsListState = rememberLazyListState()
    var showPosts by rememberSaveable { mutableStateOf(false) }

    var isManagingSubs by remember { mutableStateOf(false) }
    var manageShowsMusic by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openMusicSubscriptions) {
        if (!openMusicSubscriptions) return@LaunchedEffect
        manageShowsMusic = true
        isManagingSubs = true
        onMusicSubscriptionsOpened()
    }
    var searchQuery by remember { mutableStateOf("") }
    var showSortMenu by remember { mutableStateOf(false) }
    var showGroupsDialog by remember { mutableStateOf(false) }
    val openManage = { showsMusic: Boolean ->
        manageShowsMusic = showsMusic
        isManagingSubs = true
    }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                viewModel.importNewPipeBackup(it)
                scope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.importing_from_backup))
                }
            }
        }

    LaunchedEffect(viewModel) { viewModel.importMessages.collect { snackbarHostState.showSnackbar(it) } }

    val channels = rememberSubscriptionChannels(uiState)
    val sortedChannels = channels.sorted
    val topChannels = channels.quickAccess
    val videos = uiState.recentVideos
    SubscriptionsFeedEffects(
        viewModel = viewModel,
        feedGridState = feedGridState,
        videos = videos,
        isManagingSubs = isManagingSubs,
    )

    Scaffold(
        topBar = {
            if (isManagingSubs) {
                FlowSearchTopBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onClose = {
                        isManagingSubs = false
                        searchQuery = ""
                    },
                    placeholder = stringResource(R.string.subscriptions_search_placeholder),
                    // Manage mode opens a browsable channel list; popping the keyboard would cover it.
                    autoFocus = false,
                    actions = {
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = stringResource(R.string.subscriptions_sort_label),
                                )
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                Text(
                                    text = stringResource(R.string.subscriptions_sort_label),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                SubscriptionSortMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(mode.labelRes())) },
                                        onClick = {
                                            viewModel.setSortMode(mode)
                                            showSortMenu = false
                                        },
                                        trailingIcon = {
                                            if (uiState.sortMode == mode) {
                                                Icon(Icons.Default.Check, contentDescription = null)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { importLauncher.launch("application/json") }) {
                            Icon(
                                imageVector = Icons.Default.Upload,
                                contentDescription = stringResource(R.string.import_newpipe_backup),
                            )
                        }
                    },
                )
            } else {
                FlowTopBar(
                    title = stringResource(R.string.top_bar_subscriptions_title),
                    actions = {
                        IconButton(onClick = { viewModel.toggleViewMode() }) {
                            Icon(
                                imageVector =
                                    if (uiState.isFullWidthView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                contentDescription = stringResource(R.string.toggle_view_mode),
                            )
                        }
                        IconButton(onClick = { openManage(false) }) {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = stringResource(R.string.search_subscriptions),
                            )
                        }
                    },
                )
            }
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState, Modifier.floatAboveBottomChrome(LocalFlowBottomInsets.current))
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { padding ->
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            AnimatedContent(targetState = isManagingSubs, label = "subscriptionsMode") { manageMode ->
                if (manageMode) {
                    SubscriptionsManageContent(
                        channels = sortedChannels,
                        searchQuery = searchQuery,
                        notificationStates = uiState.notificationStates,
                        excludedShortsChannelIds = uiState.excludedShortsChannelIds,
                        onChannelClick = onChannelClick,
                        onNotificationChange = viewModel::updateNotificationState,
                        onShortsExcludeChange = viewModel::setShortsChannelExcluded,
                        showsMusic = manageShowsMusic,
                        onShowsMusicChange = { manageShowsMusic = it },
                        onUnsubscribe = { channel ->
                            scope.launch {
                                unsubscribeWithUndo(
                                    viewModel = viewModel,
                                    channel = channel,
                                    snackbarHostState = snackbarHostState,
                                    unsubscribedMessage =
                                        context.getString(R.string.unsubscribed_from_template, channel.name),
                                    undoLabel = context.getString(R.string.undo),
                                )
                            }
                        },
                    )
                } else if (uiState.subscribedChannels.isEmpty()) {
                    SubscriptionsEmptyState(modifier = Modifier.fillMaxSize())
                } else if (showPosts) {
                    SubscriptionPostsContent(
                        channelIds = uiState.selectedGroupScope(),
                        listState = postsListState,
                        onChannelClick = onChannelClick,
                        onVideoClick = onVideoClick,
                        header = {
                            SubscriptionsFeedHeader(
                                state = uiState,
                                topChannels = topChannels,
                                onChannelClick = onChannelClick,
                                onViewAllClick = { openManage(false) },
                                onMusicSubscriptionsClick = { openManage(true) },
                                onGroupSelected = viewModel::selectGroup,
                                onManageGroups = { showGroupsDialog = true },
                                postsSelected = true,
                                onPostsClick = { showPosts = false },
                            )
                        },
                    )
                } else {
                    SubscriptionsFeedContent(
                        state = uiState,
                        videos = videos,
                        topChannels = topChannels,
                        gridState = feedGridState,
                        columnPreference = columnPreference,
                        onRefresh = viewModel::refreshFeed,
                        onVideoClick = onVideoClick,
                        onShortClick = onShortClick,
                        onChannelClick = onChannelClick,
                        onViewAllClick = { openManage(false) },
                        onMusicSubscriptionsClick = { openManage(true) },
                        onGroupSelected = viewModel::selectGroup,
                        onManageGroups = { showGroupsDialog = true },
                        onRetryFailedChannels = viewModel::retryFailedChannels,
                        onDismissFailedChannels = viewModel::dismissFailedChannels,
                        onPostsClick = { showPosts = true },
                    )
                }
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

private suspend fun unsubscribeWithUndo(
    viewModel: SubscriptionsViewModel,
    channel: Channel,
    snackbarHostState: SnackbarHostState,
    unsubscribedMessage: String,
    undoLabel: String,
) {
    val subscription = viewModel.getSubscriptionOnce(channel.id)
    viewModel.unsubscribe(channel.id)
    val result =
        snackbarHostState.showSnackbar(
            message = unsubscribedMessage,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Short,
        )
    if (result == SnackbarResult.ActionPerformed) {
        subscription?.let { viewModel.subscribeChannel(it) }
    }
}
