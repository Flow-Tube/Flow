package io.github.aedev.flow.ui.components.music.sheet

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.components.shared.CollectionEditDialog
import io.github.aedev.flow.ui.components.shared.CollectionSheetEntry
import io.github.aedev.flow.ui.components.shared.SaveToCollectionSheet
import io.github.aedev.flow.ui.screens.music.SaveSongViewModel

/** Saves [track] to your music playlists, showing the ones that already hold it. */
@Composable
fun SaveSongSheet(
    track: MusicTrack,
    onDismiss: () -> Unit,
    viewModel: SaveSongViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val savedIds by viewModel.savedIds.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(track.videoId) { viewModel.loadMembership(track.videoId) }

    val entries =
        playlists.map { playlist ->
            CollectionSheetEntry(
                id = playlist.id,
                name = playlist.name,
                supporting = pluralStringResource(R.plurals.songs_count_template, playlist.videoCount, playlist.videoCount),
                thumbnailUrl = playlist.thumbnailUrl,
                isSaved = playlist.id in savedIds,
            )
        }

    SaveToCollectionSheet(
        title = stringResource(R.string.title_add_to_playlist),
        entries = entries,
        placeholderIcon = Icons.Default.MusicNote,
        createLabel = stringResource(R.string.create_new_playlist),
        emptyLabel = stringResource(R.string.empty_playlists_dialog),
        onToggle = { entry -> viewModel.toggle(track, entry.id) },
        onCreateNew = { showCreateDialog = true },
        onDismiss = onDismiss,
    )

    if (showCreateDialog) {
        CollectionEditDialog(
            title = stringResource(R.string.title_create_playlist),
            confirmLabel = stringResource(R.string.action_create),
            icon = Icons.AutoMirrored.Filled.PlaylistAdd,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, description ->
                viewModel.createAndAdd(track, name, description)
                showCreateDialog = false
            },
        )
    }
}

/**
 * Adds several songs to a playlist at once. Each playlist picked gets them all, once; a playlist that
 * already had some keeps those where they were.
 */
@Composable
fun SaveSongsSheet(
    tracks: List<MusicTrack>,
    onDismiss: () -> Unit,
    viewModel: SaveSongViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val savedIds by viewModel.savedIds.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }

    val entries =
        playlists.map { playlist ->
            CollectionSheetEntry(
                id = playlist.id,
                name = playlist.name,
                supporting = pluralStringResource(R.plurals.songs_count_template, playlist.videoCount, playlist.videoCount),
                thumbnailUrl = playlist.thumbnailUrl,
                isSaved = playlist.id in savedIds,
            )
        }

    SaveToCollectionSheet(
        title = pluralStringResource(R.plurals.add_songs_to_playlist_title, tracks.size, tracks.size),
        entries = entries,
        placeholderIcon = Icons.Default.MusicNote,
        createLabel = stringResource(R.string.create_new_playlist),
        emptyLabel = stringResource(R.string.empty_playlists_dialog),
        onToggle = { entry -> if (!entry.isSaved) viewModel.addAll(tracks, entry.id) },
        onCreateNew = { showCreateDialog = true },
        onDismiss = onDismiss,
    )

    if (showCreateDialog) {
        CollectionEditDialog(
            title = stringResource(R.string.title_create_playlist),
            confirmLabel = stringResource(R.string.action_create),
            icon = Icons.AutoMirrored.Filled.PlaylistAdd,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, description ->
                viewModel.createAndAddAll(tracks, name, description)
                showCreateDialog = false
            },
        )
    }
}
