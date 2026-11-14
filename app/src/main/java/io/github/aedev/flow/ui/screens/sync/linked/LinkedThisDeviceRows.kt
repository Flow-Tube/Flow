package io.github.aedev.flow.ui.screens.sync.linked

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.FlowSwitchRow
import io.github.aedev.flow.ui.screens.sync.SyncRowGroup
import io.github.aedev.flow.ui.screens.sync.SyncSection

/** How this device takes part in the group, whatever the other devices do. */
@Composable
internal fun LinkedThisDeviceRows(
    autoSync: Boolean,
    busy: Boolean,
    onAutoSync: (Boolean) -> Unit,
) {
    SyncSection(stringResource(R.string.linked_this_device)) {
        SyncRowGroup(count = 1) { _, shape ->
            FlowSwitchRow(
                title = stringResource(R.string.linked_auto_sync),
                checked = autoSync,
                onCheckedChange = onAutoSync,
                supportingText = stringResource(if (autoSync) R.string.linked_auto_sync_on else R.string.linked_auto_sync_off),
                leadingIcon = Icons.Outlined.Sync,
                enabled = !busy,
                shape = shape,
            )
        }
    }
}
