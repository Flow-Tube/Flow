package io.github.aedev.flow.ui.screens.subscriptions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R

/** The long-press menu of a subscribed channel: pin or unpin it, and open it where a tap does something else. */
@Composable
internal fun SubscriptionChannelMenu(
    expanded: Boolean,
    isPinned: Boolean,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onOpenChannel: (() -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(if (isPinned) R.string.subscriptions_unpin_channel else R.string.subscriptions_pin_channel)) },
            leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
            onClick = {
                onDismiss()
                onTogglePin()
            },
        )
        if (onOpenChannel != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.subscriptions_open_channel)) },
                leadingIcon = { Icon(Icons.Outlined.AccountCircle, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onOpenChannel()
                },
            )
        }
    }
}
