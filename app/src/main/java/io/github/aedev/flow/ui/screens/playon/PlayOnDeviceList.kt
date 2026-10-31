package io.github.aedev.flow.ui.screens.playon

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.PlayingWaveform
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.deviceKindIcon
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionRow
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsGroup

private val TileSize = 48.dp
private val TileIconSize = 24.dp
private val TrailingSize = 24.dp
private val GroupGap = 12.dp

/** What the rows show beyond the devices themselves: which one is checked, being moved to, or just failed. */
internal data class PlayOnRowState(
    val active: String?,
    val movingTo: String? = null,
    val failed: String? = null,
) {
    val moving: Boolean get() = movingTo != null
}

/**
 * The group's devices as Settings draws its rows: the [PlayOnRowState.active] one checked, offline
 * ones folded once there are a few, and a way to Linked devices when [onManage] is set.
 */
@Composable
internal fun DeviceList(
    devices: List<PlayOnDevice>,
    state: PlayOnRowState,
    onPick: (PlayOnDevice) -> Unit,
    onManage: (() -> Unit)? = null,
) {
    val rows = playOnRows(devices, state.active)
    var showOffline by rememberSaveable { mutableStateOf(false) }
    val offline = if (rows.foldOffline && !showOffline) emptyList() else rows.offline
    QuickActionsGroup(
        title = stringResource(R.string.linked_devices),
        rows =
            (rows.reachable + offline).map { device ->
                QuickActionRow(device.did) { shape -> DeviceRow(device, state, onPick, shape) }
            } +
                listOfNotNull(
                    if (rows.foldOffline) {
                        QuickActionRow("offline") { shape ->
                            FlowNavRow(
                                title = stringResource(R.string.play_on_not_available),
                                onClick = { showOffline = !showOffline },
                                supportingText = if (showOffline) null else rows.offline.joinToString { it.name },
                                leadingContent = { DeviceTile(Icons.Outlined.CloudOff, active = false) },
                                showChevron = false,
                                shape = shape,
                                trailingContent = {
                                    Icon(
                                        if (showOffline) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                        contentDescription = null,
                                    )
                                },
                            )
                        }
                    } else {
                        null
                    },
                ),
    )
    if (onManage != null) {
        Spacer(Modifier.height(GroupGap))
        QuickActionsGroup(
            title = null,
            rows =
                listOf(
                    QuickActionRow("manage") { shape ->
                        FlowNavRow(
                            title = stringResource(R.string.play_on_manage),
                            onClick = onManage,
                            supportingText = stringResource(R.string.play_on_manage_summary),
                            leadingContent = { DeviceTile(Icons.Outlined.Devices, active = false) },
                            shape = shape,
                        )
                    },
                ),
        )
    }
}

@Composable
private fun DeviceRow(
    device: PlayOnDevice,
    state: PlayOnRowState,
    onPick: (PlayOnDevice) -> Unit,
    shape: Shape,
) {
    val active = device.did == state.active
    val moving = device.did == state.movingTo
    FlowNavRow(
        title = deviceName(device),
        onClick = { if (!state.moving) onPick(device) },
        supportingContent = { DeviceStatus(device, moving, failed = device.did == state.failed) },
        leadingContent = { DeviceTile(deviceKindIcon(device.kind), active) },
        showChevron = false,
        enabled = device.group != PlayOnGroup.OFFLINE || active,
        selected = active,
        shape = shape,
        trailingContent =
            when {
                moving -> {
                    { MovingIndicator() }
                }

                active -> {
                    { Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                }

                else -> {
                    null
                }
            },
    )
}

/** The device's icon on its tile; the active device's tile takes the primary container. */
@Composable
private fun DeviceTile(
    icon: ImageVector,
    active: Boolean,
) {
    Surface(
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.size(TileSize),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(TileIconSize))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MovingIndicator() {
    LoadingIndicator(modifier = Modifier.size(TrailingSize))
}

/** "Playing · title" behind a small waveform, "Paused · title", "Ready" or when it was last seen. */
@Composable
private fun DeviceStatus(
    device: PlayOnDevice,
    moving: Boolean,
    failed: Boolean,
) {
    val title = device.now?.title.orEmpty()
    val playing = device.group == PlayOnGroup.PLAYING
    val text =
        when {
            moving -> {
                stringResource(R.string.play_on_moving)
            }

            failed -> {
                stringResource(R.string.play_on_move_failed)
            }

            playing -> {
                stringResource(R.string.play_on_status_playing, title)
            }

            device.group == PlayOnGroup.OFFLINE -> {
                device.seenAt?.let { stringResource(R.string.play_on_last_seen, DateUtils.getRelativeTimeSpanString(it).toString()) }
                    ?: stringResource(R.string.play_on_never_seen)
            }

            else -> {
                val status =
                    if (device.now?.video != null) {
                        stringResource(R.string.play_on_status_paused, title)
                    } else {
                        stringResource(R.string.play_on_status_ready)
                    }
                if (device.nearby) stringResource(R.string.play_on_status_nearby, status) else status
            }
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (playing && !moving && !failed) {
            PlayingWaveform(
                barCount = 3,
                barWidth = 2.dp,
                minBarHeight = 4.dp,
                maxBarHeight = 11.dp,
            )
        }
        Text(
            text,
            color = if (failed) MaterialTheme.colorScheme.error else Color.Unspecified,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
