package io.github.aedev.flow.ui.screens.sync.linked

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.sync2.service.LinkedDevice
import io.github.aedev.flow.ui.components.shared.FlowConnectedToggleGroup
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.FlowSectionHeader
import io.github.aedev.flow.ui.components.shared.FlowSheetHeader
import io.github.aedev.flow.ui.components.shared.FlowToggleOption
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionRow
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsGroup
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsSheet

private const val NAME_LIMIT = 64
private val ToggleHorizontalPadding = 16.dp
private val GroupGap = 16.dp

private enum class SheetMode { SHEET, RENAME, REMOVE }

/**
 * One device of the group: its name, whether it may start playback here, and removing it. This
 * device's own sheet only renames it. Renaming and removing hand the sheet over to a dialog.
 */
@Composable
internal fun LinkedDeviceSheet(
    device: LinkedDevice,
    status: String,
    icon: ImageVector,
    devices: List<LinkedDevice>,
    /** The remembered playback answer: true allowed, false not, null asks. */
    consent: Boolean?,
    onConsent: (Boolean?) -> Unit,
    onRename: (String) -> Unit,
    onRemove: (also: List<String>) -> Unit,
    addedBy: suspend (String) -> List<String>,
    onDismiss: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(SheetMode.SHEET) }
    when (mode) {
        SheetMode.SHEET -> {
            QuickActionsSheet(onDismiss = onDismiss) { sheet ->
                FlowSheetHeader(
                    title = device.name,
                    subtitle = status,
                    leadingIcon = icon,
                    onClose = null,
                    showDragHandle = false,
                    dividerAlpha = null,
                )
                QuickActionsGroup(
                    title = null,
                    rows =
                        listOf(
                            QuickActionRow("name") { shape ->
                                FlowNavRow(
                                    title = stringResource(R.string.linked_device_name),
                                    onClick = { sheet.hideThen { mode = SheetMode.RENAME } },
                                    supportingText = device.name,
                                    leadingIcon = Icons.Outlined.Edit,
                                    shape = shape,
                                )
                            },
                        ),
                )
                if (!device.thisDevice) {
                    FlowSectionHeader(stringResource(R.string.linked_consent_title))
                    FlowConnectedToggleGroup(
                        options =
                            listOf(
                                FlowToggleOption(null, stringResource(R.string.linked_consent_ask)),
                                FlowToggleOption(true, stringResource(R.string.linked_consent_allowed)),
                                FlowToggleOption(false, stringResource(R.string.linked_consent_declined)),
                            ),
                        selected = consent,
                        onSelected = onConsent,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = ToggleHorizontalPadding),
                    )
                    Spacer(Modifier.height(GroupGap))
                    QuickActionsGroup(
                        title = null,
                        rows =
                            listOf(
                                QuickActionRow("remove") { shape ->
                                    FlowNavRow(
                                        title = stringResource(R.string.linked_remove_from_group),
                                        onClick = { sheet.hideThen { mode = SheetMode.REMOVE } },
                                        leadingIcon = Icons.Outlined.Delete,
                                        showChevron = false,
                                        destructive = true,
                                        shape = shape,
                                    )
                                },
                            ),
                    )
                }
            }
        }

        SheetMode.RENAME -> {
            RenameDialog(device.name, onDismiss) { name ->
                onDismiss()
                onRename(name)
            }
        }

        SheetMode.REMOVE -> {
            RemoveDialog(device, devices, addedBy, onDismiss) { also ->
                onDismiss()
                onRemove(also)
            }
        }
    }
}

@Composable
private fun RenameDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.linked_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(NAME_LIMIT) },
                label = { Text(stringResource(R.string.linked_device_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.linked_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.linked_cancel)) } },
    )
}

/** Removing [device], after saying what that means, with the devices it added offered for removal too. */
@Composable
private fun RemoveDialog(
    device: LinkedDevice,
    devices: List<LinkedDevice>,
    addedBy: suspend (String) -> List<String>,
    onDismiss: () -> Unit,
    onRemove: (also: List<String>) -> Unit,
) {
    var added by remember { mutableStateOf(emptyList<LinkedDevice>()) }
    var also by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(device.id) {
        val ids = addedBy(device.id)
        added = devices.filter { it.id in ids && !it.thisDevice }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.linked_remove_title, device.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.linked_remove_body))
                if (added.isNotEmpty()) Text(stringResource(R.string.linked_remove_also, device.name))
                for (other in added) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = other.id in also,
                            onCheckedChange = { also = if (it) also + other.id else also - other.id },
                        )
                        Text(other.name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRemove(also.toList()) }) { Text(stringResource(R.string.linked_remove)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.linked_cancel)) } },
    )
}
