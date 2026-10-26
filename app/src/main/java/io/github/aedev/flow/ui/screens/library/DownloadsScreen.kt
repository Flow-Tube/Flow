package io.github.aedev.flow.ui.screens.library

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.dao.DownloadCollectionSummary
import io.github.aedev.flow.data.music.DownloadedTrack
import io.github.aedev.flow.data.video.DownloadedVideo
import io.github.aedev.flow.data.video.storage.StorageAccess
import io.github.aedev.flow.ui.components.layout.LocalFlowSnackbarHostState
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBar
import io.github.aedev.flow.ui.components.library.ActiveDownloadActions
import io.github.aedev.flow.ui.components.library.DownloadCollectionsShelf
import io.github.aedev.flow.ui.components.library.DownloadsRecoveryCard
import io.github.aedev.flow.ui.components.library.DownloadsStorageCard
import io.github.aedev.flow.ui.components.library.LibraryKindHeader
import io.github.aedev.flow.ui.components.library.LibrarySelection
import io.github.aedev.flow.ui.components.library.MusicDownloadsList
import io.github.aedev.flow.ui.components.library.RemoveDownloadCollectionDialog
import io.github.aedev.flow.ui.components.library.VideosDownloadsList
import io.github.aedev.flow.ui.components.library.libraryHeaderIsOneRow
import io.github.aedev.flow.ui.components.shared.FlowAlertDialog
import io.github.aedev.flow.ui.components.shared.FlowSelectionAction
import io.github.aedev.flow.ui.components.shared.FlowSelectionToolbar
import io.github.aedev.flow.ui.components.shared.FlowSortChip
import io.github.aedev.flow.ui.components.shared.MediaKind
import io.github.aedev.flow.ui.components.shared.dismissKeyboardOnPress
import io.github.aedev.flow.ui.components.shared.flowGridColumns
import kotlinx.coroutines.flow.collectLatest

@Composable
fun DownloadsScreen(
    onBackClick: (() -> Unit)?,
    onVideoClick: (videos: List<DownloadedVideo>, startIndex: Int) -> Unit,
    onMusicClick: (List<DownloadedTrack>, Int) -> Unit,
    onOpenCollection: (DownloadCollectionSummary) -> Unit,
    onHomeClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val showStorageCard by viewModel.showStorageCard.collectAsStateWithLifecycle()
    val confirmDelete by viewModel.confirmDelete.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shellSnackbar = LocalFlowSnackbarHostState.current
    val snackbarHostState = shellSnackbar ?: remember { SnackbarHostState() }
    var access by remember { mutableStateOf(StorageAccess.read(context)) }
    var recoveryDismissed by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        access = StorageAccess.read(context)
        viewModel.scanIfMoreIsReadable()
        onPauseOrDispose { }
    }
    val accessRequest =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            access = StorageAccess.read(context)
            when {
                access.video || access.audio -> viewModel.findEarlierDownloads()
                (context as? Activity)?.canStillAsk() == false -> context.openAppPermissionSettings()
            }
        }
    val recoveredMessage = { count: Int ->
        if (count > 0) {
            context.resources.getQuantityString(R.plurals.downloads_recovered_count, count, count)
        } else {
            context.getString(R.string.downloads_recovered_none)
        }
    }
    LaunchedEffect(viewModel) { viewModel.recovered.collect { snackbarHostState.showSnackbar(recoveredMessage(it)) } }
    DownloadsUndoEffect(viewModel, snackbarHostState)
    val deletePrompt = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
    LaunchedEffect(viewModel) { viewModel.deleteConsent.collect { deletePrompt.launch(IntentSenderRequest.Builder(it).build()) } }
    var selectedKind by rememberSaveable { mutableStateOf(MediaKind.Videos) }
    var pendingDeletion by remember { mutableStateOf<PendingDeletion?>(null) }
    var removeIncompleteOf by remember { mutableStateOf<MediaKind?>(null) }
    var removingCollection by remember { mutableStateOf<DownloadCollectionSummary?>(null) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(emptySet<String>()) }
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val exitSelection = {
        selectionMode = false
        selectedIds = emptySet()
    }
    val delete = { deletion: PendingDeletion ->
        if (confirmDelete) {
            pendingDeletion = deletion
        } else {
            viewModel.deleteDownloads(deletion.ids)
            exitSelection()
        }
    }
    val swipeDelete = { id: String ->
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        viewModel.deleteDownloads(setOf(id))
    }
    val shownIds =
        if (selectedKind ==
            MediaKind.Videos
        ) {
            uiState.downloadedVideos.map { it.video.id }
        } else {
            uiState.downloadedMusic.map { it.track.videoId }
        }

    BackHandler(enabled = selectionMode) { exitSelection() }

    val selection =
        LibrarySelection(selectionMode, selectedIds) { id ->
            selectedIds =
                if (id in selectedIds) selectedIds - id else selectedIds + id
        }
    val activeActions =
        ActiveDownloadActions(
            onPause = viewModel::pauseVideoDownload,
            onResume = viewModel::resumeVideoDownload,
            onRetry = viewModel::retryVideoDownload,
            onCancel = { id, title -> pendingDeletion = PendingDeletion(setOf(id), title, undoable = false) },
            onCancelAll = { removeIncompleteOf = selectedKind },
        )
    val sortChip: @Composable () -> Unit = {
        FlowSortChip(
            options = DownloadSort.entries,
            selected = uiState.sort,
            default = DownloadSort.NEWEST,
            label = { stringResource(it.labelRes) },
            onSelected = viewModel::setSort,
        )
    }
    val onRemoveCollection: (DownloadCollectionSummary) -> Unit = { removingCollection = it }
    val sortInHeader = libraryHeaderIsOneRow()
    val listHeader: @Composable () -> Unit = {
        Column {
            if (showStorageCard) {
                DownloadsStorageCard(uiState.storage.videoBytes, uiState.storage.musicBytes, uiState.storage.freeBytes)
            }
            if (!sortInHeader) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    sortChip()
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            FlowTopBar(
                title =
                    if (selectionMode) {
                        pluralStringResource(R.plurals.selected_count_template, selectedIds.size, selectedIds.size)
                    } else {
                        stringResource(R.string.downloads_title)
                    },
                onBack = if (selectionMode) exitSelection else onBackClick,
                actions = {
                    if (selectionMode) {
                        IconButton(onClick = { selectedIds = if (selectedIds.size == shownIds.size) emptySet() else shownIds.toSet() }) {
                            Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.select_all))
                        }
                    } else if (shownIds.isNotEmpty()) {
                        IconButton(onClick = { selectionMode = true }) {
                            Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.select_videos))
                        }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { if (shellSnackbar == null) SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                LibraryKindHeader(
                    query = uiState.query,
                    onQueryChange = viewModel::setQuery,
                    placeholder = stringResource(R.string.downloads_search_hint),
                    selectedKind = selectedKind,
                    onKindSelected = {
                        exitSelection()
                        selectedKind = it
                    },
                    trailing = if (sortInHeader) sortChip else null,
                )
                val hasNothing =
                    uiState.totalVideoCount == 0 &&
                        uiState.totalMusicCount == 0 &&
                        uiState.incompleteVideoDownloads.isEmpty() &&
                        uiState.incompleteMusicDownloads.isEmpty()
                if (hasNothing && !access.seesAllDownloads && !recoveryDismissed) {
                    DownloadsRecoveryCard(
                        onAllow = { accessRequest.launch(downloadReadPermissions()) },
                        onDismiss = { recoveryDismissed = true },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                val musicColumns = flowGridColumns(compact = 1, medium = 1, expanded = 2)
                Crossfade(
                    targetState = selectedKind,
                    animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                    label = "downloads_kind",
                    modifier = Modifier.weight(1f).dismissKeyboardOnPress { focusManager.clearFocus() },
                ) { kind ->
                    when (kind) {
                        MediaKind.Videos -> {
                            VideosDownloadsList(
                                header = listHeader,
                                shelf = { inset ->
                                    DownloadCollectionsShelf(uiState.videoCollections, onOpenCollection, onRemoveCollection, inset)
                                },
                                videos = uiState.downloadedVideos,
                                totalCount = uiState.totalVideoCount,
                                incomplete = uiState.incompleteVideoDownloads,
                                progress = uiState.progress,
                                mergingIds = uiState.mergingVideoIds,
                                query = uiState.query,
                                selection = selection,
                                activeActions = activeActions,
                                isRefreshing = uiState.isScanning,
                                onRefresh = viewModel::rescan,
                                onVideoClick = onVideoClick,
                                onDelete = { id, title -> delete(PendingDeletion(setOf(id), title)) },
                                onSwipeDelete = swipeDelete,
                                onHomeClick = onHomeClick,
                            )
                        }

                        MediaKind.Music -> {
                            MusicDownloadsList(
                                header = listHeader,
                                shelf = { inset ->
                                    DownloadCollectionsShelf(uiState.musicCollections, onOpenCollection, onRemoveCollection, inset)
                                },
                                tracks = uiState.downloadedMusic,
                                totalCount = uiState.totalMusicCount,
                                incomplete = uiState.incompleteMusicDownloads,
                                progress = uiState.progress,
                                columns = musicColumns,
                                query = uiState.query,
                                selection = selection,
                                activeActions = activeActions,
                                isRefreshing = uiState.isScanning,
                                onRefresh = viewModel::rescan,
                                onMusicClick = onMusicClick,
                                onSwipeDelete = swipeDelete,
                                onHomeClick = onHomeClick,
                            )
                        }
                    }
                }
            }
            FlowSelectionToolbar(
                visible = selectionMode && selectedIds.isNotEmpty(),
                summary = pluralStringResource(R.plurals.selected_count_template, selectedIds.size, selectedIds.size),
                actions =
                    listOf(
                        FlowSelectionAction(Icons.Outlined.Delete, stringResource(R.string.action_delete)) {
                            delete(PendingDeletion(selectedIds, title = null))
                        },
                    ),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    pendingDeletion?.let { deletion ->
        DeleteDownloadsDialog(
            deletion = deletion,
            onDismiss = { pendingDeletion = null },
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                if (deletion.undoable) viewModel.deleteDownloads(deletion.ids) else viewModel.cancelDownloads(deletion.ids)
                pendingDeletion = null
                exitSelection()
            },
        )
    }

    removingCollection?.let { summary ->
        val remove = { deleteFiles: Boolean ->
            viewModel.removeCollection(summary.collection.id, deleteFiles)
            removingCollection = null
        }
        RemoveDownloadCollectionDialog(
            title = summary.collection.title,
            onDeleteFiles = { remove(true) },
            onKeepFiles = { remove(false) },
            onDismiss = { removingCollection = null },
        )
    }

    removeIncompleteOf?.let { kind ->
        val count = if (kind == MediaKind.Videos) uiState.incompleteVideoDownloads.size else uiState.incompleteMusicDownloads.size
        FlowAlertDialog(
            onDismissRequest = { removeIncompleteOf = null },
            title = { Text(stringResource(R.string.remove_incomplete_downloads)) },
            text = { Text(pluralStringResource(R.plurals.remove_incomplete_downloads_message, count, count)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeIncompleteDownloads(audioOnly = kind == MediaKind.Music)
                        removeIncompleteOf = null
                    },
                ) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { removeIncompleteOf = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private val SelectionSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })
