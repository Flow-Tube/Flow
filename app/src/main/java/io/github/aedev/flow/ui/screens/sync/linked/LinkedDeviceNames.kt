package io.github.aedev.flow.ui.screens.sync.linked

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.sync2.service.LinkedDevice

private const val SHORT_ID = 4

/**
 * The start of each device's id where two devices share a name, so two installs that both default to
 * "Flow Desktop (Windows)" can be told apart when removing one (#2184). Null for a name of its own.
 */
internal fun shortIdsForSharedNames(devices: List<LinkedDevice>): Map<String, String?> {
    val shared =
        devices
            .groupBy { it.name.trim().lowercase() }
            .filterValues { it.size > 1 }
            .values
            .flatten()
    var length = SHORT_ID
    while (length < SHORT_ID * 4 && shared.map { it.id.take(length) }.toSet().size < shared.size) length *= 2
    val ids = shared.associate { it.id to it.id.take(length) }
    return devices.associate { it.id to ids[it.id] }
}

/** Each device's name as the list and the remove dialog show it. */
@Composable
internal fun rememberDeviceNames(devices: List<LinkedDevice>): Map<String, String> {
    val shortIds = remember(devices) { shortIdsForSharedNames(devices) }
    return devices.associate { device ->
        device.id to (shortIds[device.id]?.let { stringResource(R.string.linked_device_name_with_id, device.name, it) } ?: device.name)
    }
}
