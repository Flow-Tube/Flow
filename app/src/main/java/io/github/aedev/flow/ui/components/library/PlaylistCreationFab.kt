package io.github.aedev.flow.ui.components.library

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R

internal enum class PlaylistCreationTarget {
    Video,
    Music,
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaylistCreationFabMenu(
    onCreateVideo: () -> Unit,
    onCreateMusic: () -> Unit,
    onImport: () -> Unit,
    onExportAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = expanded) { expanded = false }

    FloatingActionButtonMenu(
        expanded = expanded,
        modifier = modifier,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = { expanded = it },
            ) {
                val icon by remember {
                    derivedStateOf { if (checkedProgress > 0.5f) Icons.Default.Close else Icons.Default.Add }
                }
                Icon(
                    painter = rememberVectorPainter(icon),
                    contentDescription =
                        stringResource(
                            if (expanded) {
                                R.string.playlist_creation_menu_close
                            } else {
                                R.string.playlist_creation_menu_open
                            },
                        ),
                    modifier = Modifier.animateIcon({ checkedProgress }),
                )
            }
        },
    ) {
        FloatingActionButtonMenuItem(
            onClick = {
                expanded = false
                onCreateVideo()
            },
            icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
            text = { Text(stringResource(R.string.video)) },
        )
        FloatingActionButtonMenuItem(
            onClick = {
                expanded = false
                onCreateMusic()
            },
            icon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
            text = { Text(stringResource(R.string.tab_music)) },
        )
        FloatingActionButtonMenuItem(
            onClick = {
                expanded = false
                onImport()
            },
            icon = { Icon(Icons.Outlined.FileOpen, contentDescription = null) },
            text = { Text(stringResource(R.string.import_playlist_action)) },
        )
        FloatingActionButtonMenuItem(
            onClick = {
                expanded = false
                onExportAll()
            },
            icon = { Icon(Icons.Outlined.SaveAs, contentDescription = null) },
            text = { Text(stringResource(R.string.playlist_export_all_action)) },
        )
    }
}
