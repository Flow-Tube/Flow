package io.github.aedev.flow.ui.components.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.ui.components.music.item.MusicCollectionCard
import io.github.aedev.flow.ui.components.shared.ignoreHeldOk

@Composable
internal fun MusicPlaylistLibraryCard(
    playlist: PlaylistInfo,
    onClick: () -> Unit,
    onDownload: (() -> Unit)?,
    onRename: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        MusicCollectionCard(
            title = playlist.name,
            subtitle = stringResource(R.string.tracks_count_template, playlist.videoCount),
            thumbnailUrl = playlist.thumbnailUrl,
            onClick = onClick,
            onLongClick = { showMenu = true },
            fillMaxWidth = true,
            modifier = Modifier.fillMaxWidth(),
        )

        PlaylistActionsMenu(
            expanded = showMenu,
            onDismiss = { showMenu = false },
            onDownload = onDownload,
            onRename = onRename,
            onDelete = onDelete,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/** Download, rename and delete for a playlist in the library, offering only what is passed. */
@Composable
internal fun PlaylistActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onDownload: (() -> Unit)?,
    onRename: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier.ignoreHeldOk(),
    ) {
        if (onDownload != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.download)) },
                onClick = {
                    onDismiss()
                    onDownload()
                },
                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
            )
        }
        if (onRename != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_rename)) },
                onClick = {
                    onDismiss()
                    onRename()
                },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete)) },
            onClick = {
                onDismiss()
                onDelete()
            },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
        )
    }
}
