package io.github.aedev.flow.ui.screens.playlists

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.queue.QueuePageSource
import io.github.aedev.flow.ui.components.library.PlaylistDetailTopBar
import io.github.aedev.flow.ui.components.library.PlaylistHeader
import io.github.aedev.flow.ui.components.library.PlaylistHeaderActions
import io.github.aedev.flow.ui.components.library.PlaylistHeaderPane
import io.github.aedev.flow.ui.components.library.PlaylistSearchBar
import io.github.aedev.flow.ui.components.library.PlaylistSortChip
import io.github.aedev.flow.ui.components.library.PlaylistSortOrder
import io.github.aedev.flow.ui.components.shared.FlowErrorState
import io.github.aedev.flow.ui.components.shared.FlowLoadingIndicator
import io.github.aedev.flow.ui.components.shared.FlowSelectionAction
import io.github.aedev.flow.ui.components.shared.FlowSelectionToolbar
import io.github.aedev.flow.ui.components.shared.FlowSidePanes
import io.github.aedev.flow.ui.components.shared.quickactions.sharedQuickActionsViewModel
import io.github.aedev.flow.ui.components.shared.rememberFlowPaneState
import io.github.aedev.flow.ui.components.shared.rememberShareLinksWithoutText
import io.github.aedev.flow.utils.PLAYLIST_FILE_MIME_TYPE
import io.github.aedev.flow.utils.filterBySearch
import io.github.aedev.flow.utils.holdsPendingOrder
import io.github.aedev.flow.utils.movedByKey
import io.github.aedev.flow.utils.movedTo
import io.github.aedev.flow.utils.sharePlaylist
import io.github.aedev.flow.utils.sharePlaylistFile
import kotlinx.coroutines.launch
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PlaylistDetailScreen(
    onNavigateBack: () -> Unit,
    onPlayPlaylist: (videos: List<Video>, startIndex: Int, shuffle: Boolean, title: String, more: QueuePageSource?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sortedVideos by viewModel.sortedVideos.collectAsStateWithLifecycle()
    val downloadBatch by viewModel.downloadBatch.collectAsStateWithLifecycle()
    val downloadedCollection by viewModel.downloadedCollection.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val swipes by viewModel.playlistSwipes.collectAsStateWithLifecycle()
    val newSinceDownload by viewModel.newSinceDownload.collectAsStateWithLifecycle()
    val mergeTargets by viewModel.userCreatedPlaylists.collectAsStateWithLifecycle()
    val quickActions = sharedQuickActionsViewModel()
    val context = LocalContext.current
    val shareLinkOnly by rememberShareLinksWithoutText()

    var dialog by remember { mutableStateOf<PlaylistDialog?>(null) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectionMode by remember { mutableStateOf(false) }
    // Set while "Download all" picks: the videos left out, so pages that arrive later join the pick.
    var leftOut by remember { mutableStateOf<Set<String>?>(null) }
    var displayVideos by remember { mutableStateOf(sortedVideos) }
    var searchQuery by rememberSaveable { mutableStateOf<String?>(null) }
    val isSearching = searchQuery != null
    val shownVideos =
        remember(displayVideos, searchQuery) { displayVideos.filterBySearch(searchQuery.orEmpty()) { "${it.title} ${it.channelName}" } }
    val positions =
        remember(displayVideos, isSearching) {
            if (isSearching) displayVideos.withIndex().associate { (index, video) -> video.id to index + 1 } else emptyMap()
        }
    val scope = rememberCoroutineScope()
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PLAYLIST_FILE_MIME_TYPE)) { target ->
            target?.let(viewModel::exportTo)
        }
    val listState = rememberLazyListState()
    val panes = rememberFlowPaneState()
    val twoPane = panes.showsSidePane

    val isUserCreated = uiState.isLocalPlaylist && !uiState.isSaved
    // A queue in the playlist's own order keeps loading it; a sorted one holds what is shown.
    val play = { startIndex: Int, shuffle: Boolean ->
        val more = if (sortOrder == PlaylistSortOrder.MANUAL) viewModel.queueSource(displayVideos.size) else null
        onPlayPlaylist(displayVideos, startIndex, shuffle, uiState.playlistName, more)
    }
    val canReorder = isUserCreated && sortOrder == PlaylistSortOrder.MANUAL && !isSearching
    // A saved YouTube playlist mirrors the original; its next sync would bring removed videos back.
    val canModify = uiState.isLocalPlaylist && !uiState.isSaved
    val exitSelection = {
        selectionMode = false
        selectedIds = emptySet()
        leftOut = null
    }
    val toggleSelected = { ids: Set<String>, select: Boolean ->
        selectedIds = if (select) selectedIds + ids else selectedIds - ids
        leftOut = leftOut?.let { if (select) it - ids else it + ids }
    }

    val haptics = LocalHapticFeedback.current
    var dragMoved by remember { mutableStateOf(false) }
    var pendingOrder by remember { mutableStateOf<List<String>?>(null) }
    val reorderState =
        rememberReorderableLazyListState(listState) { from, to ->
            displayVideos.movedByKey(from.key, to.key) { it.id }?.let { moved ->
                displayVideos = moved
                dragMoved = true
                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }
        }

    // Held while a drag is in progress and until the saved order comes back, so neither a sync nor
    // the order from before the drop can replace the list under the finger.
    LaunchedEffect(sortedVideos, reorderState.isAnyItemDragging) {
        if (reorderState.isAnyItemDragging) return@LaunchedEffect
        if (dragMoved) {
            dragMoved = false
            val order = displayVideos.map { it.id }
            pendingOrder = order
            viewModel.reorderVideos(order)
            return@LaunchedEffect
        }
        if (sortOrder == PlaylistSortOrder.MANUAL && holdsPendingOrder(sortedVideos.map { it.id }, pendingOrder)) return@LaunchedEffect
        pendingOrder = null
        displayVideos = sortedVideos
        val sortedIds = sortedVideos.mapTo(LinkedHashSet()) { it.id }
        selectedIds = leftOut?.let { sortedIds - it } ?: selectedIds.intersect(sortedIds)
        if (sortedVideos.isEmpty()) exitSelection()
    }

    fun moveVideo(
        video: Video,
        target: Int,
    ) {
        val moved = displayVideos.movedTo(displayVideos.indexOfFirst { it.id == video.id }, target) ?: return
        displayVideos = moved
        val order = moved.map { it.id }
        pendingOrder = order
        viewModel.reorderVideos(order)
    }

    LaunchedEffect(searchQuery) {
        if (isSearching) {
            viewModel.loadAll()
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> quickActions.announce(message.resolve(context), message.undo) }
    }

    BackHandler(enabled = selectionMode) { exitSelection() }
    BackHandler(enabled = isSearching && !selectionMode) { searchQuery = null }

    val showCollapsedTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val headerState =
        rememberPlaylistHeaderState(
            uiState,
            displayVideos,
            downloadBatch,
            isDownloaded =
                downloadedCollection?.isComplete == true,
            newSinceDownload = newSinceDownload,
        )
    val headerActions =
        PlaylistHeaderActions(
            onPlayAll = { if (displayVideos.isNotEmpty()) play(0, false) },
            onShuffle = { if (displayVideos.isNotEmpty()) play(displayVideos.indices.random(), true) },
            onDownloadAll = {
                scope.launch {
                    val left = viewModel.downloadLeftOut() ?: return@launch
                    leftOut = left
                    selectedIds = displayVideos.mapTo(LinkedHashSet()) { it.id } - left
                    selectionMode = true
                }
            },
            onSaveToggle = { if (uiState.isSaved) viewModel.unsaveFromLibrary() else viewModel.saveToLibrary() },
            onAddAll = { dialog = PlaylistDialog.AddAll },
            onShare = {
                if (!isUserCreated) {
                    sharePlaylist(context, viewModel.playlistId, uiState.playlistName, shareLinkOnly)
                } else {
                    scope.launch {
                        val file = viewModel.shareableFile()
                        if (file != null) {
                            sharePlaylistFile(context, file, uiState.playlistName)
                        } else {
                            quickActions.announce(context.getString(R.string.playlist_share_failed))
                        }
                    }
                }
            },
            onExport = { exportLauncher.launch(viewModel.exportFileName) },
            onEdit = { dialog = PlaylistDialog.Edit },
            onDelete = { dialog = PlaylistDialog.Delete },
            onMoveLibrary = viewModel::moveToOtherLibrary,
            onDownloadNew = viewModel::downloadNew,
        )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            PlaylistDetailTopBar(
                title = uiState.playlistName,
                showTitle = (showCollapsedTitle && !twoPane) || selectionMode,
                inSelectionMode = selectionMode,
                selectedCount = selectedIds.size,
                allSelected = shownVideos.isNotEmpty() && shownVideos.all { it.id in selectedIds },
                canSelect = displayVideos.isNotEmpty(),
                search =
                    PlaylistSearchBar(
                        query = searchQuery,
                        onOpen = { searchQuery = "" },
                        onQueryChange = { searchQuery = it },
                        onClose = { searchQuery = null },
                    ),
                onNavigateBack = onNavigateBack,
                onEnterSelection = { selectionMode = true },
                onClearSelection = exitSelection,
                onSelectAll = {
                    val shownIds = shownVideos.mapTo(HashSet()) { it.id }
                    toggleSelected(shownIds, !selectedIds.containsAll(shownIds))
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isLoading -> {
                    FlowLoadingIndicator()
                }

                uiState.errorMessage != null -> {
                    FlowErrorState(error = uiState.errorMessage.orEmpty(), onRetry = viewModel::retry)
                }

                else -> {
                    val sortChip: @Composable () -> Unit = {
                        PlaylistSortChip(
                            options = PlaylistSortOrder.availableFor(uiState.isLocalPlaylist, uiState.isLikes),
                            selected = sortOrder,
                            onSelected = viewModel::setSortOrder,
                            default = PlaylistSortOrder.defaultFor(uiState.isLikes),
                        )
                    }
                    val list: @Composable (header: (@Composable () -> Unit)?) -> Unit = { header ->
                        PlaylistDetailList(
                            videos = shownVideos,
                            mode =
                                PlaylistListMode(
                                    canReorder = canReorder,
                                    canModify = canModify,
                                    selectionMode = selectionMode,
                                    selectedIds = selectedIds,
                                    showAddedDate = sortOrder.showsDateAdded,
                                    isWatchLater = uiState.isWatchLater,
                                    isLikes = uiState.isLikes,
                                    searchQuery = searchQuery.orEmpty(),
                                    swipes = swipes,
                                    swipeEnabled = !selectionMode && !reorderState.isAnyItemDragging,
                                ),
                            hasMorePages = uiState.hasMorePages,
                            listState = listState,
                            reorderState = reorderState,
                            onVideoClick = { index, video ->
                                if (selectionMode) {
                                    toggleSelected(setOf(video.id), video.id !in selectedIds)
                                } else {
                                    play(positions[video.id]?.minus(1) ?: index, false)
                                }
                            },
                            onRemove = { viewModel.removeVideo(it.id) },
                            onMoveVideo = ::moveVideo,
                            onNearEnd = viewModel::loadMore,
                            header = header,
                        )
                    }
                    FlowSidePanes(
                        panes = panes,
                        sidePaneWidth = HeaderPaneWidth,
                        sidePane = {
                            PlaylistHeaderSurface(
                                headerState.thumbnailUrl,
                            ) { PlaylistHeaderPane(state = headerState, actions = headerActions) }
                        },
                        mainPane = {
                            if (twoPane || isSearching) {
                                Column {
                                    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { sortChip() }
                                    list(null)
                                }
                            } else {
                                list { PlaylistHeader(state = headerState, actions = headerActions, sortChip = sortChip) }
                            }
                        },
                    )
                }
            }
            FlowSelectionToolbar(
                visible = selectionMode && selectedIds.isNotEmpty(),
                summary = pluralStringResource(R.plurals.selected_count_template, selectedIds.size, selectedIds.size),
                actions =
                    listOfNotNull(
                        FlowSelectionAction(Icons.Outlined.Download, stringResource(R.string.download)) {
                            leftOut?.let(viewModel::downloadAllExcept) ?: viewModel.downloadVideos(selectedIds)
                            exitSelection()
                        },
                        FlowSelectionAction(Icons.Outlined.Delete, stringResource(R.string.remove)) {
                            dialog = PlaylistDialog.RemoveSelected(selectedIds)
                        }.takeIf { canModify },
                    ),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    PlaylistDetailDialogs(
        dialog = dialog,
        uiState = uiState,
        mergeTargets = mergeTargets,
        viewModel = viewModel,
        onDismiss = { dialog = null },
        onDeleted = onNavigateBack,
        onRemovedSelected = exitSelection,
    )
}

private fun PlaylistUiMessage.resolve(context: Context): String =
    when {
        pluralRes != 0 -> context.resources.getQuantityString(pluralRes, count, *args.toTypedArray())
        args.isEmpty() -> context.getString(stringRes)
        else -> context.getString(stringRes, *args.toTypedArray())
    }
