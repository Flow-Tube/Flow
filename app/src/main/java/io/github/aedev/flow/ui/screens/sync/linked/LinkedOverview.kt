package io.github.aedev.flow.ui.screens.sync.linked

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.sync2.data.LAN_STORE
import io.github.aedev.flow.sync2.data.WEBDAV_STORE
import io.github.aedev.flow.sync2.service.LinkedDevice
import io.github.aedev.flow.sync2.service.LinkedError
import io.github.aedev.flow.ui.components.shared.FlowConnectedToggleGroup
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.FlowToggleOption
import io.github.aedev.flow.ui.components.shared.deviceKindIcon
import io.github.aedev.flow.ui.screens.sync.SyncActionRow
import io.github.aedev.flow.ui.screens.sync.SyncCard
import io.github.aedev.flow.ui.screens.sync.SyncInfoRow
import io.github.aedev.flow.ui.screens.sync.SyncRowGroup
import io.github.aedev.flow.ui.screens.sync.SyncStepHeader

private const val NAME_LIMIT = 64

/** A linked device's group: where it syncs, how the last sync went, and its devices. */
@Composable
internal fun LinkedOverview(
    state: LinkedUiState,
    onSyncNow: () -> Unit,
    onAddDevice: () -> Unit,
    /** Null on a device without a camera. */
    onScanNewDevice: (() -> Unit)?,
    onRename: (id: String, name: String) -> Unit,
    onRemove: (id: String, also: List<String>) -> Unit,
    addedBy: suspend (String) -> List<String>,
    onAnswerRecoveryNotice: () -> Unit,
    onRemoveAfterNotice: (String) -> Unit,
    onReplaceWords: () -> Unit,
    onLeave: () -> Unit,
    onForget: () -> Unit,
    onLoadContest: () -> Unit,
    onKeepSide: (String) -> Unit,
    onUpdateLogin: (user: String, password: String) -> Unit,
    onStartLoginChange: (user: String, password: String) -> Unit,
    onFinishLoginChange: () -> Unit,
    onCancelLoginChange: () -> Unit,
    /** Each device's remembered playback answer: true allowed, false not, absent asks. */
    consent: Map<String, Boolean>,
    onConsent: (id: String, allowed: Boolean?) -> Unit,
    onAccept: (id: String) -> Unit,
    onDecline: (id: String) -> Unit,
    /** Which profiles sync here. */
    profiles: @Composable () -> Unit,
) {
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    if (state.error == LinkedError.REMOVED) {
        SyncStepHeader(
            icon = Icons.Outlined.ErrorOutline,
            title = stringResource(R.string.linked_removed_title),
            body = stringResource(R.string.linked_removed_body),
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        SyncActionRow(confirmLabel = stringResource(R.string.linked_forget), onConfirm = onForget, confirmEnabled = !state.busy)
        return
    }
    if (state.error == LinkedError.REMOVAL_UNCONFIRMED) {
        SyncStepHeader(
            icon = Icons.Outlined.ErrorOutline,
            title = stringResource(R.string.linked_unconfirmed_title),
            body = stringResource(R.string.linked_unconfirmed_body),
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        SyncActionRow(confirmLabel = stringResource(R.string.linked_forget), onConfirm = onForget, confirmEnabled = !state.busy)
        return
    }
    if (state.error == LinkedError.CONTESTED || state.contest.isNotEmpty()) {
        LaunchedEffect(Unit) { onLoadContest() }
        SyncStepHeader(
            icon = Icons.Outlined.ErrorOutline,
            title = stringResource(R.string.linked_contest_title),
            body = stringResource(R.string.linked_contest_body),
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        for (side in state.contest) {
            Text(stringResource(R.string.linked_contest_removes, side.removes.joinToString()))
            SyncActionRow(
                confirmLabel = stringResource(R.string.linked_contest_keep, side.device),
                onConfirm = { onKeepSide(side.entry) },
                confirmEnabled = !state.busy,
            )
        }
        return
    }

    val lastSync =
        state.lastSync?.let { stringResource(R.string.linked_last_sync, DateUtils.getRelativeTimeSpanString(it.atMs)) }
            ?: stringResource(R.string.linked_last_sync_never)
    SyncRowGroup(count = 2) { index, shape ->
        if (index == 0) {
            FlowNavRow(
                title = stringResource(R.string.linked_server),
                onClick = {},
                supportingText =
                    if (state.storeUrl ==
                        LAN_STORE
                    ) {
                        stringResource(R.string.linked_server_lan)
                    } else {
                        state.storeUrl?.removePrefix(WEBDAV_STORE)
                    },
                leadingIcon = Icons.Outlined.Cloud,
                showChevron = false,
                shape = shape,
            )
        } else {
            FlowNavRow(
                title = stringResource(if (state.syncing) R.string.linked_syncing else R.string.linked_sync_now),
                onClick = onSyncNow,
                supportingText = lastSync,
                enabled = !state.busy && !state.syncing,
                shape = shape,
            )
        }
    }
    state.error?.let { error ->
        SyncCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
            Text(error.message(), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
    if (state.error == LinkedError.LOGIN_REFUSED) {
        LoginAgain(state.busy, onUpdateLogin)
    } else if (state.storeUrl?.startsWith(WEBDAV_STORE) == true) {
        LoginChange(state, onStartLoginChange, onFinishLoginChange, onCancelLoginChange)
    }
    RecoveryNotices(state, onReplaceWords, onAnswerRecoveryNotice, onRemoveAfterNotice)
    if (!state.recoverySaved) SyncInfoRow(icon = Icons.Outlined.Warning, text = stringResource(R.string.linked_recovery_unsaved))

    state.requests.forEach { request ->
        SyncCard {
            Text(
                stringResource(R.string.linked_join_request, request.name),
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        SyncActionRow(
            confirmLabel = stringResource(R.string.linked_accept),
            onConfirm = { onAccept(request.id) },
            confirmEnabled = !state.busy,
            dismissLabel = stringResource(R.string.linked_decline),
            onDismiss = { onDecline(request.id) },
        )
    }
    profiles()
    DeviceList(state.devices.orEmpty(), state.nearby, state.kinds, state.busy, consent, onRename, onRemove, addedBy, onConsent)
    ReplaceRecoveryWords(state.busy, onReplaceWords)
    onScanNewDevice?.let {
        SyncActionRow(confirmLabel = stringResource(R.string.linked_scan_new_device), onConfirm = it, confirmEnabled = !state.busy)
    }
    SyncActionRow(
        confirmLabel = stringResource(R.string.linked_add_device),
        onConfirm = onAddDevice,
        confirmEnabled = !state.busy,
        dismissLabel = stringResource(R.string.linked_leave),
        onDismiss = { confirmLeave = true },
    )
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.linked_leave_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.linked_leave_body))
                    if (state.devices.orEmpty().any { it.thisDevice && it.setRecoveryKey }) {
                        Text(stringResource(R.string.linked_leave_stops_others))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onLeave()
                }) { Text(stringResource(R.string.linked_leave)) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.linked_cancel)) } },
        )
    }
}

@Composable
private fun DeviceList(
    devices: List<LinkedDevice>,
    nearby: Map<String, Long>,
    kinds: Map<String, String>,
    busy: Boolean,
    consent: Map<String, Boolean>,
    onRename: (id: String, name: String) -> Unit,
    onRemove: (id: String, also: List<String>) -> Unit,
    addedBy: suspend (String) -> List<String>,
    onConsent: (id: String, allowed: Boolean?) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    Text(stringResource(R.string.linked_devices), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
    SyncRowGroup(count = devices.size) { index, shape ->
        val device = devices[index]
        FlowNavRow(
            title = device.name,
            onClick = { open = device.id },
            supportingText =
                when {
                    device.thisDevice -> {
                        stringResource(R.string.linked_this_device)
                    }

                    device.id in nearby -> {
                        stringResource(
                            R.string.linked_device_nearby,
                            device.platform,
                            synced(nearby.getValue(device.id)),
                        )
                    }

                    device.seen != null -> {
                        stringResource(R.string.linked_device_synced, device.platform, synced(device.seen))
                    }

                    else -> {
                        device.platform
                    }
                },
            leadingIcon = deviceKindIcon(kinds[device.id], device.platform),
            enabled = !busy,
            shape = shape,
        )
    }
    devices.firstOrNull { it.id == open }?.let { device ->
        DeviceDialog(
            device = device,
            onRename = { name ->
                open = null
                onRename(device.id, name)
            },
            onRemove = { also ->
                open = null
                onRemove(device.id, also)
            },
            devices = devices,
            addedBy = addedBy,
            onDismiss = { open = null },
            consent = consent[device.id],
            onConsent = { onConsent(device.id, it) },
        )
    }
}

private fun synced(atMs: Long): String = DateUtils.getRelativeTimeSpanString(atMs).toString()

/** Renames a device, or, for another device, removes it after saying what that means. */
@Composable
private fun DeviceDialog(
    device: LinkedDevice,
    onRename: (String) -> Unit,
    onRemove: (also: List<String>) -> Unit,
    onDismiss: () -> Unit,
    devices: List<LinkedDevice>,
    addedBy: suspend (String) -> List<String>,
    consent: Boolean?,
    onConsent: (Boolean?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(device.name) }
    var removing by rememberSaveable { mutableStateOf(false) }
    var added by remember { mutableStateOf(emptyList<LinkedDevice>()) }
    var also by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(removing) {
        if (removing) {
            val ids = addedBy(device.id)
            added = devices.filter { it.id in ids && !it.thisDevice }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (removing) stringResource(R.string.linked_remove_title, device.name) else stringResource(R.string.linked_rename_title),
            )
        },
        text = {
            if (removing) {
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
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(NAME_LIMIT) },
                        label = { Text(stringResource(R.string.linked_device_name)) },
                        singleLine = true,
                    )
                    if (!device.thisDevice) {
                        Text(stringResource(R.string.linked_consent_title), style = MaterialTheme.typography.titleSmall)
                        FlowConnectedToggleGroup(
                            options =
                                listOf(
                                    FlowToggleOption(null, stringResource(R.string.linked_consent_ask)),
                                    FlowToggleOption(true, stringResource(R.string.linked_consent_allowed)),
                                    FlowToggleOption(false, stringResource(R.string.linked_consent_declined)),
                                ),
                            selected = consent,
                            onSelected = onConsent,
                        )
                        TextButton(onClick = { removing = true }) { Text(stringResource(R.string.linked_remove)) }
                    }
                }
            }
        },
        confirmButton = {
            if (removing) {
                TextButton(onClick = { onRemove(also.toList()) }) { Text(stringResource(R.string.linked_remove)) }
            } else {
                TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.linked_save)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.linked_cancel)) } },
    )
}

/** The WebDAV login the server refused, entered again; nothing is sent to the server until then. */
@Composable
private fun LoginAgain(
    busy: Boolean,
    onSave: (user: String, password: String) -> Unit,
) {
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Text(stringResource(R.string.linked_login_again), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
    WebDavLoginFields(user, { user = it }, password, { password = it })
    SyncActionRow(
        confirmLabel = stringResource(R.string.linked_save),
        onConfirm = { onSave(user.trim(), password) },
        confirmEnabled = !busy && user.isNotBlank() && password.isNotEmpty(),
    )
}

/** Replacing the folder's login (spec/protocol.md, Replacing a store's login). */
@Composable
private fun LoginChange(
    state: LinkedUiState,
    onStart: (user: String, password: String) -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    val waiting = state.loginWaiting
    if (waiting != null) {
        val names = waiting.map { id -> state.devices?.firstOrNull { it.id == id }?.name ?: id.take(8) }
        Text(stringResource(R.string.linked_login_change_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
        Text(
            if (names.isEmpty()) {
                stringResource(R.string.linked_login_change_everyone)
            } else {
                stringResource(R.string.linked_login_change_waiting, names.joinToString())
            },
        )
        Text(stringResource(R.string.linked_login_change_now))
        SyncActionRow(
            confirmLabel = stringResource(R.string.linked_login_change_done),
            onConfirm = onFinish,
            confirmEnabled = !state.busy,
            dismissLabel = stringResource(R.string.linked_cancel),
            onDismiss = onCancel,
        )
        return
    }
    var open by rememberSaveable { mutableStateOf(false) }
    if (!state.loginDue && !open) {
        SyncActionRow(
            confirmLabel = stringResource(R.string.linked_login_change_title),
            onConfirm = { open = true },
            confirmEnabled = !state.busy,
        )
        return
    }
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Text(stringResource(R.string.linked_login_change_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
    if (state.loginDue) Text(stringResource(R.string.linked_login_change_due))
    Text(stringResource(R.string.linked_login_change_first))
    WebDavLoginFields(user, { user = it }, password, { password = it })
    SyncActionRow(
        confirmLabel = stringResource(R.string.linked_login_change_send),
        onConfirm = { onStart(user.trim(), password) },
        confirmEnabled = !state.busy && user.isNotBlank() && password.isNotEmpty(),
    )
}

/**
 * A leave that couldn't tell the other devices, for [reason]: forget the group here anyway, as a
 * leave would, or keep it. The other devices can remove this one themselves.
 */
@Composable
internal fun LeaveAnyway(
    reason: LinkedError,
    busy: Boolean,
    onForget: () -> Unit,
    onKeep: () -> Unit,
) {
    SyncStepHeader(
        icon = Icons.Outlined.ErrorOutline,
        title = stringResource(R.string.linked_leave_failed_title),
        body = stringResource(R.string.linked_leave_failed_body, reason.message()),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    )
    SyncActionRow(
        confirmLabel = stringResource(R.string.linked_forget_anyway),
        onConfirm = onForget,
        confirmEnabled = !busy,
        dismissLabel = stringResource(R.string.linked_keep_group),
        onDismiss = onKeep,
    )
}

/** What the user has to know about the recovery key: it has to change, or it did. */
@Composable
private fun RecoveryNotices(
    state: LinkedUiState,
    onReplaceWords: () -> Unit,
    onAnswer: () -> Unit,
    onRemove: (String) -> Unit,
) {
    if (state.recoveryDue) {
        SyncStepHeader(
            icon = Icons.Outlined.Warning,
            title = stringResource(R.string.linked_recovery_due_title),
            body = stringResource(R.string.linked_recovery_due_body),
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        ReplaceRecoveryWords(state.busy, onReplaceWords)
    }
    val notice = state.recoveryNoticeDevice ?: return
    val device = state.devices?.firstOrNull { it.id == notice && !it.thisDevice }
    val name = device?.name?.ifBlank { null } ?: stringResource(R.string.linked_recovery_notice_unknown)
    var removing by rememberSaveable { mutableStateOf(false) }
    SyncStepHeader(
        icon = Icons.Outlined.Warning,
        title = stringResource(R.string.linked_recovery_notice_title),
        body = stringResource(R.string.linked_recovery_notice_body, name, synced(state.recoveryNoticeAt ?: 0)),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    )
    SyncActionRow(
        confirmLabel = stringResource(R.string.linked_recovery_notice_mine),
        onConfirm = onAnswer,
        confirmEnabled = !state.busy,
        dismissLabel = device?.let { stringResource(R.string.linked_recovery_notice_not_mine) },
        onDismiss = { removing = true },
    )
    if (removing && device != null) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text(stringResource(R.string.linked_remove_title, name)) },
            text = { Text(stringResource(R.string.linked_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = false
                    onRemove(device.id)
                }) { Text(stringResource(R.string.linked_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text(stringResource(R.string.linked_cancel)) } },
        )
    }
}
