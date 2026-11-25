package io.github.aedev.flow.ui.screens.settings.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.FlowAlertDialog
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.FlowSelectionRow

/**
 * Where a kind of download is saved: the default folder, a preset, a folder picked with the system
 * picker, or a typed path. The default counts as selected while no location has been chosen.
 */
@Composable
internal fun DownloadLocationDialog(
    target: DownloadTarget,
    current: String?,
    defaultPath: String,
    downloadsPath: String?,
    internalPath: String,
    onSelect: (String?) -> Unit,
    onBrowse: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var typing by rememberSaveable { mutableStateOf(false) }
    if (typing) {
        ManualPathDialog(initial = current.orEmpty(), onConfirm = onSelect, onDismiss = { typing = false })
        return
    }
    val presets =
        buildList {
            add(Triple(null, stringResource(R.string.settings_location_default), defaultPath))
            downloadsPath?.let { add(Triple(it, stringResource(R.string.location_downloads_label), it)) }
            add(
                Triple(
                    internalPath,
                    stringResource(R.string.location_internal_app_label),
                    stringResource(R.string.location_internal_app_desc),
                ),
            )
        }
    val selected = current?.takeUnless { it == defaultPath }

    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (target == DownloadTarget.MUSIC) R.string.music_download_location_label else R.string.video_download_location_label,
                ),
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.location_dialog_subtitle))
                presets.forEach { (path, label, supporting) ->
                    FlowSelectionRow(
                        title = label,
                        supportingText = supporting,
                        selected = selected == path,
                        showSelectedContainer = false,
                        onClick = {
                            onSelect(path)
                            onDismiss()
                        },
                    )
                }
                onBrowse?.let { browse ->
                    FlowNavRow(
                        title = stringResource(R.string.location_custom_saf_label),
                        supportingText = stringResource(R.string.location_custom_saf_desc),
                        leadingIcon = Icons.Outlined.CreateNewFolder,
                        showChevron = false,
                        onClick = browse,
                    )
                }
                FlowNavRow(
                    title = stringResource(R.string.location_custom_manual_label),
                    supportingText = stringResource(R.string.location_custom_manual_desc),
                    leadingIcon = Icons.Outlined.Edit,
                    showChevron = false,
                    onClick = { typing = true },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun ManualPathDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by rememberSaveable { mutableStateOf(initial) }
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.location_manual_dialog_title)) },
        text = {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text(stringResource(R.string.location_manual_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                enabled = path.isNotBlank(),
                onClick = {
                    onConfirm(path.trim())
                    onDismiss()
                },
            ) { Text(stringResource(R.string.location_manual_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
