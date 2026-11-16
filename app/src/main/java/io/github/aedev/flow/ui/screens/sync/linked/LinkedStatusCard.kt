package io.github.aedev.flow.ui.screens.sync.linked

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.sync2.data.LAN_STORE
import io.github.aedev.flow.sync2.data.WEBDAV_STORE
import io.github.aedev.flow.ui.screens.sync.SyncCard

private val CardPadding = 16.dp
private val TileSize = 40.dp
private val TileIconSize = 22.dp

/** Where a group keeps its data. */
internal enum class LinkedStore { SERVER, WEBDAV, LAN }

internal fun linkedStore(storeUrl: String?): LinkedStore =
    when {
        storeUrl == LAN_STORE -> LinkedStore.LAN
        storeUrl?.startsWith(WEBDAV_STORE) == true -> LinkedStore.WEBDAV
        else -> LinkedStore.SERVER
    }

internal fun LinkedStore.icon(): ImageVector =
    when (this) {
        LinkedStore.SERVER -> Icons.Outlined.Dns
        LinkedStore.WEBDAV -> Icons.Outlined.Cloud
        LinkedStore.LAN -> Icons.Outlined.Wifi
    }

/** Where the group syncs, when this device last did, and the page's one button: Sync now. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LinkedStatusCard(
    state: LinkedUiState,
    onSyncNow: () -> Unit,
) {
    val store = linkedStore(state.storeUrl)
    val status =
        when {
            state.syncing -> stringResource(R.string.linked_syncing)
            state.lastSync != null -> stringResource(R.string.linked_synced_at, DateUtils.getRelativeTimeSpanString(state.lastSync.atMs))
            else -> stringResource(R.string.linked_last_sync_never)
        }
    SyncCard {
        Row(
            modifier = Modifier.padding(CardPadding),
            horizontalArrangement = Arrangement.spacedBy(CardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(TileSize),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(store.icon(), contentDescription = null, modifier = Modifier.size(TileIconSize))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text =
                        stringResource(
                            when (store) {
                                LinkedStore.SERVER -> R.string.linked_store_server
                                LinkedStore.WEBDAV -> R.string.linked_store_webdav
                                LinkedStore.LAN -> R.string.linked_store_lan
                            },
                        ),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = onSyncNow,
                enabled = !state.busy && !state.syncing,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.linked_sync_now), Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
        }
    }
}
