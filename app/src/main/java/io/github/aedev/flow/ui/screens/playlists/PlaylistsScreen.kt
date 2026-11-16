package io.github.aedev.flow.ui.screens.playlists

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.backup.ExportKind
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.playlist.PlaylistImport
import io.github.aedev.flow.data.playlist.PlaylistListOrder
import io.github.aedev.flow.ui.components.layout.LocalFlowBottomInsets
import io.github.aedev.flow.ui.components.layout.floatAboveBottomChrome
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBar
import io.github.aedev.flow.ui.components.library.PlaylistCreationFabMenu
import io.github.aedev.flow.ui.components.library.PlaylistCreationTarget
import io.github.aedev.flow.ui.components.library.PlaylistLibraryFilterRow
import io.github.aedev.flow.ui.components.library.PlaylistOwnershipFilter
import io.github.aedev.flow.ui.components.library.message
import io.github.aedev.flow.ui.components.shared.CollectionEditDialog
import io.github.aedev.flow.ui.components.shared.DeleteCollectionDialog
import io.github.aedev.flow.ui.components.shared.MediaKind
import io.github.aedev.flow.ui.components.shared.quickactions.sharedQuickActionsViewModel
import io.github.aedev.flow.ui.screens.music.MusicPlaylistsViewModel
import io.github.aedev.flow.utils.PLAYLIST_FILE_MIME_TYPE
import kotlinx.coroutines.launch

private val FabMenuPadding = 16.dp

// Some file pickers label a .json file as plain text or a generic binary.
private val PlaylistFileTypes = arrayOf(PLAYLIST_FILE_MIME_TYPE, ExportKind.PLAYLISTS.mimeType, "text/plain", "application/octet-stream")

/** [fixedKind] opens the page on one kind only, for a Library that lists the two separately. */
@Composable
fun PlaylistsScreen(
    onBackClick: () -> Unit,
    onVideoPlaylistClick: (PlaylistInfo) -> Unit,
    onMusicPlaylistClick: (PlaylistInfo) -> Unit,
    modifier: Modifier = Modifier,
    fixedKind: MediaKind? = null,
    viewModel: PlaylistsViewModel = hiltViewModel(),
    musicViewModel: MusicPlaylistsViewModel = hiltViewModel(),
) {
    val videoState by viewModel.uiState.collectAsStateWithLifecycle()
    val musicState by musicViewModel.uiState.collectAsStateWithLifecycle()
    val listOrder by viewModel.listOrder.collectAsStateWithLifecycle()
    val compact by viewModel.compactLayout.collectAsStateWithLifecycle()
    val rememberedShowMusic by viewModel.showMusic.collectAsStateWithLifecycle()
    val contentKind: MediaKind? =
        fixedKind ?: rememberedShowMusic?.let { showMusic -> if (showMusic) MediaKind.Music else MediaKind.Videos }
    var ownershipFilter by rememberSaveable { mutableStateOf(PlaylistOwnershipFilter.All) }
    var reordering by rememberSaveable { mutableStateOf(false) }
    var creationTarget by remember { mutableStateOf<PlaylistCreationTarget?>(null) }
    var videoToDelete by remember { mutableStateOf<PlaylistInfo?>(null) }
    var musicToRename by remember { mutableStateOf<PlaylistInfo?>(null) }
    var musicToDelete by remember { mutableStateOf<PlaylistInfo?>(null) }

    val ownedPlaylists = if (contentKind == MediaKind.Music) musicState.playlists else videoState.playlists
    val savedPlaylists = if (contentKind == MediaKind.Music) musicState.savedPlaylists else videoState.savedPlaylists
    val visiblePlaylists =
        remember(ownedPlaylists, savedPlaylists, ownershipFilter, listOrder) {
            orderedPlaylists(ownedPlaylists, savedPlaylists, ownershipFilter, listOrder)
        }
    val ownedMusicPlaylistIds =
        remember(musicState.playlists) {
            musicState.playlists.mapTo(HashSet(), PlaylistInfo::id)
        }
    val isLoading =
        when (contentKind) {
            MediaKind.Videos -> videoState.isLoading
            MediaKind.Music -> musicState.isLoading
            null -> true
        }
    val isReordering = reordering && listOrder == PlaylistListOrder.CUSTOM

    fun selectKind(kind: MediaKind) {
        viewModel.setShowMusic(kind == MediaKind.Music)
    }

    val context = LocalContext.current
    val quickActions = sharedQuickActionsViewModel()
    val scope = rememberCoroutineScope()
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            scope.launch {
                val result = viewModel.importPlaylists(uris, context.getString(R.string.imported_playlist_default_name))
                quickActions.announce(result.message(context))
                if (result is PlaylistImport.Imported) {
                    val info =
                        PlaylistInfo(
                            id = result.playlistId,
                            name = result.name,
                            description = "",
                            videoCount = result.videoCount,
                            thumbnailUrl = "",
                            isPrivate = true,
                            createdAt = System.currentTimeMillis(),
                        )
                    if (fixedKind == null) selectKind(if (result.isMusic) MediaKind.Music else MediaKind.Videos)
                    if (result.isMusic) onMusicPlaylistClick(info) else onVideoPlaylistClick(info)
                }
            }
        }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportKind.PLAYLISTS.mimeType)) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val count = viewModel.exportAll(uri)
                quickActions.announce(
                    if (count == null) {
                        context.getString(R.string.playlists_export_failed)
                    } else {
                        context.resources.getQuantityString(R.plurals.playlists_exported, count, count)
                    },
                )
            }
        }

    LaunchedEffect(contentKind) {
        if (contentKind == MediaKind.Music) {
            musicViewModel.enrichMusicPlaylistStubs()
        }
    }

    BackHandler(enabled = isReordering) { reordering = false }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            FlowTopBar(
                title =
                    stringResource(
                        when {
                            isReordering -> R.string.playlists_reorder
                            fixedKind == MediaKind.Videos -> R.string.library_video_playlists
                            fixedKind == MediaKind.Music -> R.string.library_music_playlists
                            else -> R.string.library_playlists_label
                        },
                    ),
                onBack = if (isReordering) ({ reordering = false }) else onBackClick,
                actions = {
                    if (isReordering) {
                        TextButton(onClick = { reordering = false }) { Text(stringResource(R.string.done)) }
                    } else {
                        if (listOrder == PlaylistListOrder.CUSTOM) {
                            IconButton(onClick = { reordering = true }) {
                                Icon(Icons.Rounded.SwapVert, contentDescription = stringResource(R.string.playlists_reorder))
                            }
                        }
                        IconButton(onClick = { viewModel.setCompactLayout(!compact) }) {
                            Icon(
                                imageVector = if (compact) Icons.Rounded.GridView else Icons.Rounded.ViewAgenda,
                                contentDescription =
                                    stringResource(if (compact) R.string.playlists_show_grid else R.string.playlists_show_list),
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(paddingValues),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (!isReordering && contentKind != null) {
                    PlaylistLibraryFilterRow(
                        selectedKind = contentKind,
                        onKindSelected = ::selectKind,
                        showKinds = fixedKind == null,
                        selectedOwnership = ownershipFilter,
                        onOwnershipSelected = { ownershipFilter = it },
                        selectedOrder = listOrder,
                        onOrderSelected = viewModel::setListOrder,
                    )
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        isLoading || contentKind == null -> {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }

                        isReordering -> {
                            PlaylistsReorderList(
                                playlists =
                                    remember(ownedPlaylists, savedPlaylists) {
                                        orderedPlaylists(
                                            ownedPlaylists,
                                            savedPlaylists,
                                            PlaylistOwnershipFilter.All,
                                            PlaylistListOrder.CUSTOM,
                                        )
                                    },
                                isMusic = contentKind == MediaKind.Music,
                                onOrderChanged = viewModel::reorderPlaylists,
                            )
                        }

                        else -> {
                            PlaylistsGrid(
                                kind = contentKind,
                                playlists = visiblePlaylists,
                                ownedMusicIds = ownedMusicPlaylistIds,
                                compact = compact,
                                actions =
                                    remember(onVideoPlaylistClick, onMusicPlaylistClick) {
                                        PlaylistsGridActions(
                                            onVideoClick = onVideoPlaylistClick,
                                            onMusicClick = onMusicPlaylistClick,
                                            onVideoDelete = { videoToDelete = it },
                                            onMusicDownload = musicViewModel::downloadPlaylist,
                                            onMusicRename = { musicToRename = it },
                                            onMusicDelete = { musicToDelete = it },
                                        )
                                    },
                            )
                        }
                    }
                }
            }

            if (!isReordering) {
                PlaylistCreationFabMenu(
                    onCreateVideo = { creationTarget = PlaylistCreationTarget.Video },
                    onCreateMusic = { creationTarget = PlaylistCreationTarget.Music },
                    onImport = { importLauncher.launch(PlaylistFileTypes) },
                    onExportAll = { exportLauncher.launch(ExportKind.PLAYLISTS.fileName()) },
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .floatAboveBottomChrome(LocalFlowBottomInsets.current)
                            .padding(FabMenuPadding),
                )
            }
        }
    }

    creationTarget?.let { target ->
        val isVideo = target == PlaylistCreationTarget.Video
        CollectionEditDialog(
            title =
                stringResource(
                    if (isVideo) R.string.create_new_playlist else R.string.new_playlist_button,
                ),
            confirmLabel = stringResource(R.string.create),
            icon = if (isVideo) Icons.Default.VideoLibrary else Icons.Default.MusicNote,
            onDismiss = { creationTarget = null },
            onConfirm = { name, description ->
                if (isVideo) {
                    viewModel.createPlaylist(name, description)
                } else {
                    musicViewModel.createPlaylist(name, description)
                }
                creationTarget = null
            },
        )
    }

    videoToDelete?.let { playlist ->
        DeleteCollectionDialog(
            collectionName = playlist.name,
            onDismiss = { videoToDelete = null },
            onConfirm = {
                viewModel.deletePlaylist(playlist.id)
                videoToDelete = null
            },
        )
    }

    musicToDelete?.let { playlist ->
        DeleteCollectionDialog(
            collectionName = playlist.name,
            onDismiss = { musicToDelete = null },
            onConfirm = {
                musicViewModel.deletePlaylist(playlist.id)
                musicToDelete = null
            },
        )
    }

    musicToRename?.let { playlist ->
        CollectionEditDialog(
            title = stringResource(R.string.rename_playlist_title),
            confirmLabel = stringResource(R.string.action_rename),
            initialName = playlist.name,
            showDescription = false,
            onDismiss = { musicToRename = null },
            onConfirm = { name, _ ->
                musicViewModel.renamePlaylist(playlist.id, name)
                musicToRename = null
            },
        )
    }
}
