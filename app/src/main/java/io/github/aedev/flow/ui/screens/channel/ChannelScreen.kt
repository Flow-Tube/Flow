package io.github.aedev.flow.ui.screens.channel

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.SubscriptionGroup
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.pages.channel.ChannelHeader
import io.github.aedev.flow.innertube.pages.channel.ChannelTabKind
import io.github.aedev.flow.ui.components.channel.ChannelBanner
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBar
import io.github.aedev.flow.ui.components.shared.ChannelAvatarImage
import io.github.aedev.flow.ui.components.shared.CollectionEditDialog
import io.github.aedev.flow.ui.components.shared.CollectionSheetEntry
import io.github.aedev.flow.ui.components.shared.FlowEmptyState
import io.github.aedev.flow.ui.components.shared.FlowErrorState
import io.github.aedev.flow.ui.components.shared.FlowLoadingIndicator
import io.github.aedev.flow.ui.components.shared.FlowNoteEditorDialog
import io.github.aedev.flow.ui.components.shared.FlowSubscribeButton
import io.github.aedev.flow.ui.components.shared.FullSizeImageDialog
import io.github.aedev.flow.ui.components.shared.MediaPostCommentsSheet
import io.github.aedev.flow.ui.components.shared.SaveToCollectionSheet
import io.github.aedev.flow.ui.components.shared.rememberPostShareAction
import io.github.aedev.flow.ui.theme.extendedColors
import io.github.aedev.flow.ui.youtubeChannelUrl
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    channelUrl: String,
    onVideoClick: (Video) -> Unit,
    onChannelClick: (String) -> Unit,
    onShortClick: (videoId: String, sortIndex: Int) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    showShorts: Boolean = true,
    viewModel: ChannelViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val preferences =
        remember(context) {
            io.github.aedev.flow.data.local
                .PlayerPreferences(context)
        }
    val isGridView by preferences.channelIsGridView.collectAsState(initial = false)
    val uiState by viewModel.uiState.collectAsState()
    val communityUiState by viewModel.communityUiState.collectAsState()
    val tabStates by viewModel.tabStates.collectAsStateWithLifecycle()
    val subscribedChannelIds by viewModel.subscribedChannelIds.collectAsStateWithLifecycle()
    val notifyingChannelIds by viewModel.notifyingChannelIds.collectAsStateWithLifecycle()
    val channelNote by viewModel.channelNote.collectAsStateWithLifecycle()
    val notesEnabled by viewModel.notesEnabled.collectAsStateWithLifecycle()
    val showShortsTab by viewModel.showShortsTab.collectAsStateWithLifecycle()
    var showNoteEditor by rememberSaveable { mutableStateOf(false) }
    val subscriptionGroups by viewModel.subscriptionGroups.collectAsStateWithLifecycle()
    var showGroupSheet by rememberSaveable { mutableStateOf(false) }
    var showCreateGroupDialog by rememberSaveable { mutableStateOf(false) }

    // Shelves and posts have their own layout; only the item grids can switch.
    val showLayoutToggle =
        uiState.selectedTab != null &&
            uiState.selectedTab !in setOf(ChannelTabKind.Home, ChannelTabKind.Posts)

    LaunchedEffect(channelUrl) { viewModel.loadChannel(channelUrl) }

    var showCollapsedChannelTitle by remember(channelUrl) { mutableStateOf(false) }
    val collapsedChannelTitle = uiState.header?.title.orEmpty()
    val sharePost = rememberPostShareAction()

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
        ) {
            FlowTopBar(
                title = {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showCollapsedChannelTitle && collapsedChannelTitle.isNotBlank(),
                    ) {
                        Text(
                            text = collapsedChannelTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                onBack = onBackClick,
                actions = {
                    if (showLayoutToggle) {
                        IconButton(onClick = { coroutineScope.launch { preferences.setChannelIsGridView(!isGridView) } }) {
                            Icon(
                                imageVector = if (isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                                contentDescription =
                                    if (isGridView) {
                                        stringResource(R.string.ui_list_view)
                                    } else {
                                        stringResource(R.string.ui_grid_view)
                                    },
                            )
                        }
                    }
                    IconButton(onClick = {
                        // channelUrl may already be a full URL, so it must be normalized rather than
                        // pasted behind /channel/ — that produced a nested, unopenable share link.
                        val shareUrl = youtubeChannelUrl(uiState.header?.id ?: channelUrl) ?: channelUrl
                        val shareIntent =
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareUrl)
                            }
                        context.startActivity(Intent.createChooser(shareIntent, null))
                    }) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(R.string.share),
                        )
                    }
                },
            )

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.isLoading -> {
                        FlowLoadingIndicator()
                    }

                    uiState.error != null -> {
                        FlowErrorState(
                            error = uiState.error ?: stringResource(R.string.failed_to_load_channel),
                            onRetry = { viewModel.loadChannel(channelUrl) },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    uiState.header != null -> {
                        ChannelContent(
                            uiState = uiState,
                            communityUiState = communityUiState,
                            tabStates = tabStates,
                            showShortsTab = showShorts && showShortsTab,
                            onFilterSelected = viewModel::selectTabFilter,
                            subscribedChannelIds = subscribedChannelIds,
                            notifyingChannelIds = notifyingChannelIds,
                            onChannelNotificationChange = viewModel::setChannelNotifications,
                            channelNote = channelNote.takeIf { notesEnabled },
                            onEditNote = { showNoteEditor = true }.takeIf { notesEnabled },
                            onSubscribeChannel = viewModel::setChannelSubscription,
                            onVideoClick = onVideoClick,
                            onChannelClick = onChannelClick,
                            onShortClick = { videoId ->
                                onShortClick(videoId, tabStates[ChannelTabKind.Shorts]?.selected?.firstOrNull() ?: 0)
                            },
                            onPlaylistClick = onPlaylistClick,
                            onSubscribeClick = { viewModel.toggleSubscription() },
                            onUnsubscribeClick = { viewModel.unsubscribe() },
                            onNotificationChange = { viewModel.setNotificationState(it) },
                            onManageGroups = { showGroupSheet = true },
                            onTabSelected = { viewModel.selectTab(it) },
                            onSearchToggle = { viewModel.setSearchActive(!uiState.searchActive) },
                            onSearchQueryChange = { viewModel.searchInChannel(it) },
                            onCommunityPostComments = viewModel.postComments::open,
                            onCommunityPostShare = sharePost,
                            onLoadMoreCommunityPosts = viewModel::loadMoreCommunityPosts,
                            onRetryCommunityPosts = viewModel::retryCommunityPosts,
                            initialScrollIndex = viewModel.listScrollIndex,
                            initialScrollOffset = viewModel.listScrollOffset,
                            onScrollChanged = { idx, off -> viewModel.saveScrollPosition(idx, off) },
                            onCollapsedTitleVisibilityChange = { showCollapsedChannelTitle = it },
                        )
                    }
                }
            }
        }

        MediaPostCommentsSheet(loader = viewModel.postComments, onAuthorClick = onChannelClick)
    }

    val channelId = uiState.header?.id.orEmpty()
    if (showGroupSheet && channelId.isNotBlank()) {
        ChannelGroupSheet(
            groups = subscriptionGroups,
            channelId = channelId,
            onToggle = { groupName, inGroup -> viewModel.setChannelInGroup(groupName, channelId, inGroup) },
            onCreateNew = {
                showGroupSheet = false
                showCreateGroupDialog = true
            },
            onDismiss = { showGroupSheet = false },
        )
    }

    if (showNoteEditor && notesEnabled) {
        FlowNoteEditorDialog(
            initialText = channelNote.orEmpty(),
            title = stringResource(R.string.note_channel_title),
            onSave = viewModel::saveChannelNote,
            onDismiss = { showNoteEditor = false },
        )
    }

    if (showCreateGroupDialog && channelId.isNotBlank()) {
        CollectionEditDialog(
            title = stringResource(R.string.new_group),
            confirmLabel = stringResource(R.string.save),
            onDismiss = { showCreateGroupDialog = false },
            onConfirm = { name, _ ->
                viewModel.createGroupWithChannel(name, channelId)
                showCreateGroupDialog = false
            },
            icon = Icons.Rounded.Folder,
            showDescription = false,
        )
    }
}
