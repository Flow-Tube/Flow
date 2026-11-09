package io.github.aedev.flow.ui.screens.sync

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.FlowSwitchRow
import io.github.aedev.flow.utils.readPlainText
import kotlinx.coroutines.launch

/**
 * The pre-session steps: pick a direction, pick what travels, pick how the two devices pair, and
 * (when this device scans) the camera step.
 */

private const val MAX_CONNECTION_DATA_LENGTH = 4096

@Composable
internal fun SyncChooserContent(
    onSend: () -> Unit,
    onReceive: () -> Unit,
) {
    SyncOptions(
        listOf(
            SyncOption(
                icon = Icons.Outlined.Upload,
                title = stringResource(R.string.sync_send_to_device),
                body = stringResource(R.string.sync_send_option_body),
                onClick = onSend,
            ),
            SyncOption(
                icon = Icons.Outlined.Download,
                title = stringResource(R.string.sync_receive_from_device),
                body = stringResource(R.string.sync_receive_option_body),
                onClick = onReceive,
            ),
        ),
    )
}

@Composable
internal fun SyncSelectContent(
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    onContinue: () -> Unit,
) {
    val allSelected = selected.size == COLLECTION_KEYS.size
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text =
                pluralStringResource(
                    R.plurals.sync_selected_count,
                    selected.size,
                    selected.size,
                    COLLECTION_KEYS.size,
                ),
            style = MaterialTheme.typography.titleMedium,
        )
        TextButton(onClick = { onSelectedChange(if (allSelected) emptySet() else COLLECTION_KEYS.toSet()) }) {
            Text(
                stringResource(if (allSelected) R.string.sync_select_none else R.string.sync_select_all),
            )
        }
    }

    SyncRowGroup(count = COLLECTION_KEYS.size) { index, shape ->
        val key = COLLECTION_KEYS[index]
        FlowSwitchRow(
            title = collectionLabel(key),
            supportingText = collectionDescription(key),
            checked = key in selected,
            onCheckedChange = { isChecked -> onSelectedChange(if (isChecked) selected + key else selected - key) },
            leadingIcon = collectionIcon(key),
            shape = shape,
        )
    }

    SyncInfoRow(
        icon = Icons.Outlined.Shield,
        text = stringResource(R.string.sync_safety_backup_note),
    )
    SyncActionRow(
        confirmLabel = stringResource(R.string.sync_continue),
        onConfirm = onContinue,
        confirmEnabled = selected.isNotEmpty(),
    )
}

@Composable
internal fun SyncTransportContent(
    showQrLabel: String,
    showQrHint: String,
    scanLabel: String,
    scanHint: String,
    onShowQr: () -> Unit,
    onScan: () -> Unit,
    onManual: () -> Unit,
) {
    SyncOptions(
        listOf(
            SyncOption(icon = Icons.Outlined.QrCode2, title = showQrLabel, body = showQrHint, onClick = onShowQr),
            SyncOption(icon = Icons.Outlined.QrCodeScanner, title = scanLabel, body = scanHint, onClick = onScan),
            SyncOption(
                icon = Icons.Outlined.Link,
                title = stringResource(R.string.sync_enter_connection_data),
                body = stringResource(R.string.sync_enter_connection_data_hint),
                onClick = onManual,
            ),
        ),
    )
}

@Composable
internal fun SyncManualEntryContent(onSubmit: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var connectionData by remember { mutableStateOf("") }
    val trimmedData = connectionData.trim()

    SyncStepHeader(
        icon = Icons.Outlined.Link,
        body = stringResource(R.string.sync_enter_connection_data_hint),
    )
    OutlinedTextField(
        value = connectionData,
        onValueChange = { value ->
            if (value.length <= MAX_CONNECTION_DATA_LENGTH) connectionData = value
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.sync_connection_data_label)) },
        trailingIcon = {
            IconButton(
                onClick = {
                    scope.launch {
                        clipboard.readPlainText(context)?.let {
                            connectionData = it.take(MAX_CONNECTION_DATA_LENGTH)
                        }
                    }
                },
            ) {
                Icon(
                    Icons.Outlined.ContentPaste,
                    contentDescription = stringResource(R.string.sync_paste_connection_data),
                )
            }
        },
        maxLines = 4,
    )
    SyncInfoRow(
        icon = Icons.Outlined.Shield,
        text = stringResource(R.string.sync_connection_data_security_note),
    )
    SyncActionRow(
        confirmLabel = stringResource(R.string.sync_connect),
        onConfirm = { onSubmit(trimmedData) },
        confirmEnabled = trimmedData.isNotEmpty(),
    )
}

@Composable
internal fun SyncScanContent(
    prompt: String,
    onScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasPermission = granted
        }
    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    if (hasPermission) {
        SyncStepHeader(
            icon = Icons.Outlined.QrCodeScanner,
            title = prompt,
            body = stringResource(R.string.sync_scanning_hint),
        )
        QrScannerView(
            onQrScanned = onScanned,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.extraLarge),
        )
    } else {
        SyncStepHeader(
            icon = Icons.Outlined.CameraAlt,
            title = stringResource(R.string.sync_grant_camera),
            body = stringResource(R.string.sync_camera_permission_rationale),
        )
        SyncActionRow(
            confirmLabel = stringResource(R.string.sync_grant_camera),
            onConfirm = { launcher.launch(Manifest.permission.CAMERA) },
        )
    }
}
