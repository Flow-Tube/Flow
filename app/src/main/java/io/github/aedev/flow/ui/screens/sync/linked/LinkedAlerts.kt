package io.github.aedev.flow.ui.screens.sync.linked

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.sync2.service.LinkedError
import io.github.aedev.flow.ui.components.settings.SettingsNotice
import io.github.aedev.flow.ui.screens.sync.SyncActionRow

/**
 * Everything on the group that waits for the user, at the top of the page in one card shape: a
 * failed step, a login to enter again, a login change under way, the recovery key, and devices
 * asking to join.
 */
@Composable
internal fun LinkedAlerts(
    state: LinkedUiState,
    onReplaceWords: () -> Unit,
    onAnswerRecoveryNotice: () -> Unit,
    onRemoveAfterNotice: (String) -> Unit,
    onUpdateLogin: (user: String, password: String) -> Unit,
    onFinishLoginChange: () -> Unit,
    onCancelLoginChange: () -> Unit,
    onAccept: (id: String) -> Unit,
    onDecline: (id: String) -> Unit,
) {
    state.error?.let { error ->
        SettingsNotice(text = error.message(), icon = Icons.Outlined.ErrorOutline, isWarning = true)
    }
    if (state.error == LinkedError.LOGIN_REFUSED) LoginAgain(state.busy, onUpdateLogin)
    LoginChangeWaiting(state, onFinishLoginChange, onCancelLoginChange)
    if (state.recoveryDue) {
        SettingsNotice(
            text = stringResource(R.string.linked_recovery_due_body),
            icon = Icons.Outlined.Warning,
            title = stringResource(R.string.linked_recovery_due_title),
            isWarning = true,
        ) {
            NoticeButton(stringResource(R.string.linked_replace_words), !state.busy, warning = true, onClick = onReplaceWords)
        }
    }
    RecoveryNotice(state, onAnswerRecoveryNotice, onRemoveAfterNotice)
    if (!state.recoverySaved) {
        SettingsNotice(text = stringResource(R.string.linked_recovery_unsaved), icon = Icons.Outlined.Warning)
    }
    state.requests.forEach { request ->
        SettingsNotice(
            text = stringResource(R.string.linked_join_request_hint),
            icon = Icons.Outlined.PersonAdd,
            title = stringResource(R.string.linked_join_request, request.name),
        ) {
            NoticeButton(stringResource(R.string.linked_decline), !state.busy) { onDecline(request.id) }
            NoticeButton(stringResource(R.string.linked_accept), !state.busy) { onAccept(request.id) }
        }
    }
}

@Composable
private fun NoticeButton(
    label: String,
    enabled: Boolean,
    warning: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors =
            if (warning) {
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
            } else {
                ButtonDefaults.textButtonColors()
            },
    ) { Text(label) }
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

/** A folder login change this device started, while other devices still have to read the new login. */
@Composable
private fun LoginChangeWaiting(
    state: LinkedUiState,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    val waiting = state.loginWaiting ?: return
    val names = waiting.map { id -> state.devices?.firstOrNull { it.id == id }?.name ?: id.take(8) }
    val who =
        if (names.isEmpty()) {
            stringResource(R.string.linked_login_change_everyone)
        } else {
            stringResource(R.string.linked_login_change_waiting, names.joinToString())
        }
    SettingsNotice(
        text = who + "\n" + stringResource(R.string.linked_login_change_now),
        title = stringResource(R.string.linked_login_change_title),
    ) {
        NoticeButton(stringResource(R.string.linked_cancel), !state.busy, onClick = onCancel)
        NoticeButton(stringResource(R.string.linked_login_change_done), !state.busy, onClick = onFinish)
    }
}

/** Another device changed the recovery key: the user says whether that was them, or removes it. */
@Composable
private fun RecoveryNotice(
    state: LinkedUiState,
    onAnswer: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val notice = state.recoveryNoticeDevice ?: return
    val device = state.devices?.firstOrNull { it.id == notice && !it.thisDevice }
    val name = device?.name?.ifBlank { null } ?: stringResource(R.string.linked_recovery_notice_unknown)
    var removing by rememberSaveable { mutableStateOf(false) }
    SettingsNotice(
        text = stringResource(R.string.linked_recovery_notice_body, name, synced(state.recoveryNoticeAt ?: 0)),
        icon = Icons.Outlined.Warning,
        title = stringResource(R.string.linked_recovery_notice_title),
        isWarning = true,
    ) {
        if (device != null) {
            NoticeButton(stringResource(R.string.linked_recovery_notice_not_mine), !state.busy, warning = true) { removing = true }
        }
        NoticeButton(stringResource(R.string.linked_recovery_notice_mine), !state.busy, warning = true, onClick = onAnswer)
    }
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
