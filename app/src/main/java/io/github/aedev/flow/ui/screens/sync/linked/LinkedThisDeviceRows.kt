package io.github.aedev.flow.ui.screens.sync.linked

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.FlowSwitchRow
import io.github.aedev.flow.ui.screens.sync.COLLECTION_KEYS
import io.github.aedev.flow.ui.screens.sync.SyncRowGroup
import io.github.aedev.flow.ui.screens.sync.SyncSection
import io.github.aedev.flow.ui.screens.sync.collectionLabel

/** How this device takes part in the group, whatever the other devices do. */
@Composable
internal fun LinkedThisDeviceRows(
    autoSync: Boolean,
    leftOut: Set<String>,
    busy: Boolean,
    onAutoSync: (Boolean) -> Unit,
    onLeftOut: (Set<String>) -> Unit,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    SyncSection(stringResource(R.string.linked_this_device)) {
        SyncRowGroup(count = 2) { index, shape ->
            if (index == 0) {
                FlowSwitchRow(
                    title = stringResource(R.string.linked_auto_sync),
                    checked = autoSync,
                    onCheckedChange = onAutoSync,
                    supportingText = stringResource(if (autoSync) R.string.linked_auto_sync_on else R.string.linked_auto_sync_off),
                    leadingIcon = Icons.Outlined.Sync,
                    enabled = !busy,
                    shape = shape,
                )
            } else {
                FlowNavRow(
                    title = stringResource(R.string.linked_sync_choice),
                    onClick = { choosing = true },
                    supportingText = choiceSummary(leftOut),
                    leadingIcon = Icons.Outlined.Checklist,
                    enabled = !busy,
                    shape = shape,
                )
            }
        }
    }
    if (choosing) LinkedSyncChoiceSheet(leftOut, onLeftOut, onDismiss = { choosing = false })
}

@Composable
private fun choiceSummary(leftOut: Set<String>): String {
    val left = COLLECTION_KEYS.filter { it in leftOut }
    return when {
        left.isEmpty() -> stringResource(R.string.linked_sync_choice_all)
        left.size <= 2 -> stringResource(R.string.linked_sync_choice_except, left.map { collectionLabel(it) }.joinToString())
        else -> stringResource(R.string.linked_sync_choice_count, COLLECTION_KEYS.size - left.size, COLLECTION_KEYS.size)
    }
}
