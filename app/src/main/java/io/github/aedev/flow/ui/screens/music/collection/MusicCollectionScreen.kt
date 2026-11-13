package io.github.aedev.flow.ui.screens.music.collection

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.music.model.PlaylistDetails
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBar
import io.github.aedev.flow.ui.components.music.common.rememberMusicCollectionColorScheme
import io.github.aedev.flow.ui.components.music.sheet.LocalMusicMenus
import io.github.aedev.flow.ui.components.music.sheet.musicCollectionShareUrl
import io.github.aedev.flow.ui.components.music.sheet.toCollectionActionItem
import io.github.aedev.flow.ui.components.shared.FlowErrorState
import io.github.aedev.flow.ui.components.shared.FlowLoadingIndicator
import io.github.aedev.flow.ui.components.shared.FlowSelectionToolbar
import io.github.aedev.flow.ui.components.shared.FlowSidePanes
import io.github.aedev.flow.ui.components.shared.FlowSortChip
import io.github.aedev.flow.ui.components.shared.quickactions.sharedQuickActionsViewModel
import io.github.aedev.flow.ui.components.shared.rememberFlowPaneState
import io.github.aedev.flow.ui.components.shared.rememberShareLinksWithoutText
import io.github.aedev.flow.utils.PLAYLIST_FILE_MIME_TYPE
import io.github.aedev.flow.utils.filterBySearch
import io.github.aedev.flow.utils.holdsPendingOrder
import io.github.aedev.flow.utils.movedByKey
import io.github.aedev.flow.utils.shareLink
import io.github.aedev.flow.utils.sharePlaylistFile
import kotlinx.coroutines.launch
import sh.calvin.reorderable.rememberReorderableLazyListState

// The same header column as the video playlist page, so both read as one design.
private val HeaderPaneWidth = 360.dp
private const val PAGE_AHEAD_ITEMS = 4

/** Where a music page sends the viewer: back, the player, an artist, another collection, the queue. */
class MusicCollectionCallbacks(
    val onBackClick: () -> Unit,
    val onTrackClick: (track: MusicTrack, queue: List<MusicTrack>, sourceName: String) -> Unit,
    val onArtistClick: (String) -> Unit,
    val onCollectionClick: (String) -> Unit,
    val onPlayNext: (List<MusicTrack>) -> Unit,
    val onAddToQueue: (List<MusicTrack>) -> Unit,
)

/** An album or playlist page: loading, the page itself, or an error with Retry and a way back. */
@Composable
fun MusicCollectionScreen(
    callbacks: MusicCollectionCallbacks,
    viewModel: MusicCollectionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val quickActions = sharedQuickActionsViewModel()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { quickActions.announce(it.resolve(context), it.undo) }
    }
    val details = state.details
    LaunchedEffect(state.isDeleted) { if (state.isDeleted) callbacks.onBackClick() }
    when {
        state.isDeleted -> {
            Unit
        }

        details != null -> {
            CollectionContent(state, details, viewModel, callbacks)
        }

        state.isLoading -> {
            FlowLoadingIndicator()
        }

        else -> {
            Scaffold(
                topBar = { FlowTopBar(title = "", onBack = callbacks.onBackClick) },
                contentWindowInsets = WindowInsets(0.dp),
                containerColor = MaterialTheme.colorScheme.background,
            ) { padding ->
                FlowErrorState(
                    error = stringResource(R.string.error_failed_to_load_playlist),
                    onRetry = viewModel::retry,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun CollectionContent(
    state: MusicCollectionUiState,
    details: PlaylistDetails,
    viewModel: MusicCollectionViewModel,
    callbacks: MusicCollectionCallbacks,
) {
    val downloadProgress by viewModel.downloads.progress.collectAsStateWithLifecycle()
    val downloadedCollection by viewModel.downloads.downloaded.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val suggestionState by viewModel.suggestions.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val quickActions = sharedQuickActionsViewModel()
    val scope = rememberCoroutineScope()
    val shareFailed = stringResource(R.string.playlist_share_failed)
    val shareLinkOnly by rememberShareLinksWithoutText()
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PLAYLIST_FILE_MIME_TYPE)) { target ->
            target?.let(viewModel::exportTo)
        }
    val musicMenus = LocalMusicMenus.current
    var sheet by remember { mutableStateOf<CollectionSheet?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedKeys by rememberSaveable { mutableStateOf<Set<String>?>(null) }
    val isSearching = searchQuery != null
    val listState = rememberLazyListState()
    val panes = rememberFlowPaneState()
    val twoPane = panes.showsSidePane

    val sorted =
        remember(details.tracks, sortOrder, state.addedAt) { details.tracks.sortedForCollection(sortOrder, state.addedAt).withStableKeys() }
    var ordered by remember { mutableStateOf(sorted) }
    val canReorder = state.isOwn && sortOrder == MusicSortOrder.COLLECTION && !isSearching && selectedKeys == null
    val haptics = LocalHapticFeedback.current
    var dragMoved by remember { mutableStateOf(false) }
    var pendingOrder by remember { mutableStateOf<List<String>?>(null) }
    val reorderState =
        rememberReorderableLazyListState(listState) { from, to ->
            ordered.movedByKey(from.key, to.key) { it.first }?.let { moved ->
                ordered = moved
                dragMoved = true
                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }
        }
    // Held while a drag is in progress and until the saved order comes back, so neither an update
    // from the database nor the order from before the drop can replace the list under the finger.
    LaunchedEffect(sorted, reorderState.isAnyItemDragging) {
        if (reorderState.isAnyItemDragging) return@LaunchedEffect
        if (dragMoved) {
            dragMoved = false
            pendingOrder = ordered.map { it.first }
            viewModel.reorder(ordered.map { it.second.videoId })
            return@LaunchedEffect
        }
        if (sortOrder == MusicSortOrder.COLLECTION && holdsPendingOrder(sorted.map { it.first }, pendingOrder)) return@LaunchedEffect
        pendingOrder = null
        ordered = sorted
    }
    val tracks = remember(ordered) { ordered.map { it.second } }
    val shown =
        remember(ordered, searchQuery) { ordered.filterBySearch(searchQuery.orEmpty()) { (_, t) -> "${t.title} ${t.artist} ${t.album}" } }
    val positions =
        remember(ordered, isSearching) {
            if (isSearching) {
                ordered.withIndex().associate { (i, e) ->
                    e.first to i + 1
                }
            } else {
                emptyMap()
            }
        }
    val selected = selectedKeys
    val selectedTracks =
        remember(ordered, selected) {
            selected
                ?.let { keys ->
                    ordered.filter { it.first in keys }.map { it.second }
                }.orEmpty()
        }
    val exitSelection = { selectedKeys = null }

    val nearEnd by remember {
        derivedStateOf {
            (
                listState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: 0
            ) >=
                listState.layoutInfo.totalItemsCount - PAGE_AHEAD_ITEMS
        }
    }
    LaunchedEffect(nearEnd, details.tracks.size, state.isLoadingMore) {
        if (nearEnd && details.continuation != null && !state.isLoadingMore && !state.moreFailed) viewModel.loadMore()
    }
    LaunchedEffect(searchQuery) { if (isSearching) listState.scrollToItem(0) }
    LaunchedEffect(sorted) { selectedKeys = selectedKeys?.intersect(sorted.mapTo(HashSet()) { it.first }) }
    BackHandler(enabled = selected != null) { exitSelection() }
    BackHandler(enabled = isSearching && selected == null) { searchQuery = null }

    val play: (Int, List<MusicTrack>) -> Unit = { index, queue ->
        queue.getOrNull(index)?.let {
            callbacks.onTrackClick(it, queue, details.title)
        }
    }
    val headerState =
        rememberCollectionHeaderState(state, tracks, downloadProgress, isDownloaded = downloadedCollection?.isComplete == true)
    val headerActions =
        CollectionHeaderActions(
            onPlay = { play(0, tracks) },
            onShuffle = { play(0, tracks.shuffled()) },
            onSaveToggle = viewModel::toggleSaved,
            onDownload = { viewModel.download() },
            onShare = {
                if (state.sharesAsFile) {
                    scope.launch {
                        val file = viewModel.shareableFile()
                        if (file != null) sharePlaylistFile(context, file, details.title) else quickActions.announce(shareFailed)
                    }
                } else {
                    shareLink(
                        context,
                        musicCollectionShareUrl(details.id, state.kind == MusicCollectionKind.ALBUM),
                        details.title,
                        shareLinkOnly,
                    )
                }
            },
            onAuthorClick = callbacks.onArtistClick,
            menu =
                collectionMenu(
                    state,
                    onAddSongs = { sheet = CollectionSheet.AddSongs },
                    onAddAll = { sheet = CollectionSheet.AddTo(null) },
                    onEdit = { sheet = CollectionSheet.Edit },
                    onDelete = { sheet = CollectionSheet.Delete },
                    onExport = { exportLauncher.launch(viewModel.exportFileName) },
                    onSaveAsPlaylist = viewModel::saveAsPlaylist,
                    onMoveToVideos = viewModel::moveToVideos,
                ),
        )
    val sortChip: @Composable () -> Unit = {
        FlowSortChip(
            options = MusicSortOrder.availableFor(state.kind, state.addedAt.isNotEmpty(), details.tracks.any { it.album.isNotBlank() }),
            selected = sortOrder,
            default = MusicSortOrder.COLLECTION,
            label = { sortLabel(it, state.kind) },
            onSelected = viewModel::setSortOrder,
        )
    }
    val showTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    MaterialTheme(colorScheme = rememberMusicCollectionColorScheme(headerState.artworkUrl)) {
        Scaffold(
            topBar = {
                MusicCollectionTopBar(
                    state =
                        CollectionTopBarState(
                            title = if (showTitle && !twoPane) details.title else "",
                            searchQuery = searchQuery,
                            selectedCount = selected?.size,
                            allSelected = shown.isNotEmpty() && selected?.containsAll(shown.map { it.first }) == true,
                            hasSongs = details.tracks.isNotEmpty(),
                        ),
                    actions =
                        CollectionTopBarActions(
                            onBack = callbacks.onBackClick,
                            onOpenSearch = { searchQuery = "" },
                            onQueryChange = { searchQuery = it },
                            onCloseSearch = { searchQuery = null },
                            onSelect = { selectedKeys = emptySet() },
                            onClearSelection = exitSelection,
                            onSelectAll = {
                                val keys = shown.mapTo(HashSet()) { it.first }
                                selectedKeys =
                                    if (selected.orEmpty().containsAll(keys)) selected.orEmpty() - keys else selected.orEmpty() + keys
                            },
                        ),
                )
            },
            contentWindowInsets = WindowInsets(0.dp),
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val toggle: (String) -> Unit = { key -> selectedKeys = selected.orEmpty().let { if (key in it) it - key else it + key } }
                val list: @Composable (header: (@Composable () -> Unit)?) -> Unit = { header ->
                    MusicCollectionList(
                        tracks = shown,
                        mode = CollectionListMode(state.kind, canReorder, selected, searchQuery.orEmpty(), positions),
                        footer =
                            CollectionFooter(
                                songsSummary(tracks.size, details.durationText),
                                state.isLoadingMore,
                                state.moreFailed,
                                viewModel::loadMore,
                            ),
                        otherVersions = details.otherVersions.takeUnless { isSearching }.orEmpty(),
                        listState = listState,
                        reorderState = reorderState,
                        onTrackClick = { key ->
                            if (selected !=
                                null
                            ) {
                                toggle(key)
                            } else {
                                play(ordered.indexOfFirst { it.first == key }, tracks)
                            }
                        },
                        onTrackMenu = musicMenus::openSong,
                        onRemove = { viewModel.removeTracks(setOf(it.videoId)) },
                        onCollectionClick = { callbacks.onCollectionClick(it.id) },
                        onCollectionMenu = { musicMenus.openCollection(it.toCollectionActionItem(isAlbum = true)) },
                        header = header,
                        suggestions =
                            CollectionSuggestions(
                                state = suggestionState,
                                onRefresh = viewModel::refreshSuggestions,
                                onPlay = { callbacks.onTrackClick(it, suggestionState.tracks, details.title) },
                                onMenu = musicMenus::openSong,
                                onAdd = if (state.isOwn) viewModel::addTrack else null,
                            ).takeIf { state.showsSuggestions && !isSearching && selected == null },
                        onReachedEnd = { if (state.showsSuggestions && !isSearching) viewModel.requestSuggestions() },
                    )
                }
                FlowSidePanes(
                    panes = panes,
                    sidePaneWidth = HeaderPaneWidth,
                    sidePane = { MusicCollectionHeaderPane(headerState, headerActions) },
                    mainPane = {
                        when {
                            twoPane -> {
                                Column {
                                    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { sortChip() }
                                    list(null)
                                }
                            }

                            isSearching -> {
                                list(null)
                            }

                            else -> {
                                list { MusicCollectionHero(headerState, headerActions, sortChip = sortChip) }
                            }
                        }
                    },
                )
                FlowSelectionToolbar(
                    visible = selected != null && selected.isNotEmpty(),
                    summary = pluralStringResource(R.plurals.selected_count_template, selected?.size ?: 0, selected?.size ?: 0),
                    actions =
                        selectionActions(
                            state = state,
                            onPlayNext = { callbacks.onPlayNext(selectedTracks).also { exitSelection() } },
                            onAddToQueue = { callbacks.onAddToQueue(selectedTracks).also { exitSelection() } },
                            onAddTo = { sheet = CollectionSheet.AddTo(selectedTracks) },
                            onDownload = { viewModel.download(selectedTracks).also { exitSelection() } },
                            onRemove = { viewModel.removeTracks(selectedTracks.mapTo(HashSet()) { it.videoId }).also { exitSelection() } },
                        ),
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        CollectionSheets(
            sheet = sheet,
            details = details,
            viewModel = viewModel,
            onPreview = { play(0, listOf(it)) },
            onDismiss = {
                if (sheet is CollectionSheet.AddTo) exitSelection()
                sheet = null
            },
        )
    }
}

/** Pairs each song with a key that survives reordering: its id, told apart for duplicates. */
private fun List<MusicTrack>.withStableKeys(): List<Pair<String, MusicTrack>> {
    val seen = HashMap<String, Int>()
    return map { track ->
        val occurrence = (seen[track.videoId] ?: 0) + 1
        seen[track.videoId] = occurrence
        "${track.videoId}#$occurrence" to track
    }
}
