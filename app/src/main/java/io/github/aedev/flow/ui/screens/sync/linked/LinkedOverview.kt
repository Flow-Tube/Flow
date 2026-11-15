package io.github.aedev.flow.ui.screens.sync.linked

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.sync2.service.LinkedError
import io.github.aedev.flow.ui.screens.sync.SyncActionRow
import io.github.aedev.flow.ui.screens.sync.SyncStepHeader

/**
 * A linked device's group, laid out like a Settings page: what needs an answer, where it syncs and
 * when it last did, the devices, the profiles, and the rarer group actions last. [profiles] draws
 * the profiles section.
 */
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
    /** How this device itself syncs. */
    thisDevice: @Composable () -> Unit,
    /** Which profiles sync here. */
    profiles: @Composable () -> Unit,
) {
    if (groupGone(state, onForget, onLoadContest, onKeepSide)) return

    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var replacingWords by rememberSaveable { mutableStateOf(false) }
    LinkedAlerts(
        state = state,
        onReplaceWords = { replacingWords = true },
        onAnswerRecoveryNotice = onAnswerRecoveryNotice,
        onRemoveAfterNotice = onRemoveAfterNotice,
        onUpdateLogin = onUpdateLogin,
        onFinishLoginChange = onFinishLoginChange,
        onCancelLoginChange = onCancelLoginChange,
        onAccept = onAccept,
        onDecline = onDecline,
    )
    LinkedStatusCard(state, onSyncNow)
    LinkedDeviceRows(
        devices = state.devices.orEmpty(),
        nearby = state.nearby,
        kinds = state.kinds,
        busy = state.busy,
        consent = consent,
        onAddDevice = onAddDevice,
        onScanNewDevice = onScanNewDevice,
        onRename = onRename,
        onRemove = onRemove,
        addedBy = addedBy,
        onConsent = onConsent,
    )
    thisDevice()
    profiles()
    LinkedGroupRows(
        state = state,
        onReplaceWords = { replacingWords = true },
        onLeave = { confirmLeave = true },
        onStartLoginChange = onStartLoginChange,
    )

    if (replacingWords) {
        ReplaceWordsDialog(
            onDismiss = { replacingWords = false },
            onReplace = {
                replacingWords = false
                onReplaceWords()
            },
        )
    }
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

/**
 * The states where this device is no longer part of the group as it stands, each a whole page of
 * its own. Returns true when it drew one.
 */
@Composable
private fun groupGone(
    state: LinkedUiState,
    onForget: () -> Unit,
    onLoadContest: () -> Unit,
    onKeepSide: (String) -> Unit,
): Boolean {
    val contested = state.error == LinkedError.CONTESTED || state.contest.isNotEmpty()
    val (title, body) =
        when {
            state.error == LinkedError.REMOVED -> R.string.linked_removed_title to R.string.linked_removed_body
            state.error == LinkedError.REMOVAL_UNCONFIRMED -> R.string.linked_unconfirmed_title to R.string.linked_unconfirmed_body
            contested -> R.string.linked_contest_title to R.string.linked_contest_body
            else -> return false
        }
    SyncStepHeader(
        icon = Icons.Outlined.ErrorOutline,
        title = stringResource(title),
        body = stringResource(body),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    )
    if (contested) {
        LaunchedEffect(Unit) { onLoadContest() }
        for (side in state.contest) {
            Text(stringResource(R.string.linked_contest_removes, side.removes.joinToString()))
            SyncActionRow(
                confirmLabel = stringResource(R.string.linked_contest_keep, side.device),
                onConfirm = { onKeepSide(side.entry) },
                confirmEnabled = !state.busy,
            )
        }
    } else {
        SyncActionRow(confirmLabel = stringResource(R.string.linked_forget), onConfirm = onForget, confirmEnabled = !state.busy)
    }
    return true
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
