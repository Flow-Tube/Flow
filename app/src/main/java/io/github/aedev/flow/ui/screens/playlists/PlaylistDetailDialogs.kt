package io.github.aedev.flow.ui.screens.playlists

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.ui.components.shared.CollectionEditDialog
import io.github.aedev.flow.ui.components.shared.CollectionTarget
import io.github.aedev.flow.ui.components.shared.DeleteCollectionDialog
import io.github.aedev.flow.ui.components.shared.FlowAlertDialog
import io.github.aedev.flow.ui.components.shared.MergeIntoCollectionSheet

/** The dialog or sheet the playlist page has open, if any. */
internal sealed interface PlaylistDialog {
    data object Edit : PlaylistDialog

    data object Delete : PlaylistDialog

    data object AddAll : PlaylistDialog

    data class RemoveSelected(
        val ids: Set<String>,
    ) : PlaylistDialog
}

@Composable
internal fun PlaylistDetailDialogs(
    dialog: PlaylistDialog?,
    uiState: PlaylistDetailUiState,
    mergeTargets: List<PlaylistInfo>,
    viewModel: PlaylistDetailViewModel,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
    onRemovedSelected: () -> Unit,
) {
    when (dialog) {
        null -> {
            Unit
        }

        PlaylistDialog.Edit -> {
            CollectionEditDialog(
                title = stringResource(R.string.edit_playlist_action),
                confirmLabel = stringResource(R.string.action_save),
                initialName = uiState.playlistName,
                initialDescription = uiState.description,
                icon = Icons.Default.Edit,
                onDismiss = onDismiss,
                onConfirm = { name, description ->
                    viewModel.updatePlaylist(name, description)
                    onDismiss()
                },
            )
        }

        PlaylistDialog.Delete -> {
            DeleteCollectionDialog(
                collectionName = uiState.playlistName,
                onDismiss = onDismiss,
                onConfirm = {
                    viewModel.deletePlaylist()
                    onDismiss()
                    onDeleted()
                },
            )
        }

        PlaylistDialog.AddAll -> {
            var creating by remember { mutableStateOf(false) }
            MergeIntoCollectionSheet(
                targets =
                    remember(mergeTargets) {
                        mergeTargets.map {
                            CollectionTarget(id = it.id, name = it.name, thumbnailUrl = it.thumbnailUrl, itemCount = it.videoCount)
                        }
                    },
                placeholder = Icons.AutoMirrored.Filled.PlaylistPlay,
                itemCountLabel = { pluralStringResource(R.plurals.videos_count_template, it, it) },
                createLabel = stringResource(R.string.create_new_playlist),
                onSelect = { viewModel.mergeIntoPlaylist(it.id) },
                onCreateNew = { creating = true },
                onDismiss = onDismiss,
            )
            if (creating) {
                CollectionEditDialog(
                    title = stringResource(R.string.create_new_playlist),
                    confirmLabel = stringResource(R.string.create),
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    onDismiss = { creating = false },
                    onConfirm = { name, description ->
                        viewModel.createAndMerge(name, description)
                        onDismiss()
                    },
                )
            }
        }

        is PlaylistDialog.RemoveSelected -> {
            val count = dialog.ids.size
            FlowAlertDialog(
                onDismissRequest = onDismiss,
                icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                title = { Text(pluralStringResource(R.plurals.remove_selected_videos_title, count, count)) },
                text = {
                    Text(
                        stringResource(
                            when {
                                uiState.isLikes -> R.string.remove_selected_likes_text
                                uiState.isWatchLater -> R.string.remove_selected_watch_later_text
                                else -> R.string.remove_selected_playlist_text
                            },
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.removeVideos(dialog.ids)
                            onDismiss()
                            onRemovedSelected()
                        },
                    ) { Text(text = stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}
