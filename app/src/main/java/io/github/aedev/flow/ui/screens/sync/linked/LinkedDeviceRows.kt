package io.github.aedev.flow.ui.screens.sync.linked

import android.text.format.DateUtils
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.sync2.service.LinkedDevice
import io.github.aedev.flow.ui.components.shared.FlowChoice
import io.github.aedev.flow.ui.components.shared.FlowChoiceDialog
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.deviceKindIcon
import io.github.aedev.flow.ui.screens.sync.SyncRowGroup
import io.github.aedev.flow.ui.screens.sync.SyncSection

private enum class AddWay { SHOW_CODE, SCAN_CODE }

/**
 * The group's devices, each opening its own sheet, and "Add a device" as the last row. A device
 * with a camera asks whether to show a code or scan the new device's; one without shows a code.
 */
@Composable
internal fun LinkedDeviceRows(
    devices: List<LinkedDevice>,
    presence: Map<String, DevicePresence>,
    nearby: Map<String, Long>,
    kinds: Map<String, String>,
    busy: Boolean,
    consent: Map<String, Boolean>,
    onAddDevice: () -> Unit,
    /** Null on a device without a camera. */
    onScanNewDevice: (() -> Unit)?,
    onRename: (id: String, name: String) -> Unit,
    onRemove: (id: String, also: List<String>) -> Unit,
    addedBy: suspend (String) -> List<String>,
    onConsent: (id: String, allowed: Boolean?) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingAdd by rememberSaveable { mutableStateOf(false) }
    SyncSection(stringResource(R.string.linked_devices)) {
        SyncRowGroup(count = devices.size + 1) { index, shape ->
            val device = devices.getOrNull(index)
            if (device != null) {
                FlowNavRow(
                    title = device.name,
                    onClick = { open = device.id },
                    supportingText = deviceStatus(device, presence, nearby),
                    leadingIcon = deviceKindIcon(kinds[device.id], device.platform),
                    enabled = !busy,
                    shape = shape,
                )
            } else {
                FlowNavRow(
                    title = stringResource(R.string.linked_add_device),
                    onClick = { if (onScanNewDevice == null) onAddDevice() else choosingAdd = true },
                    supportingText =
                        stringResource(
                            if (onScanNewDevice == null) R.string.linked_add_device_show_hint else R.string.linked_add_device_hint,
                        ),
                    leadingIcon = Icons.Outlined.Add,
                    showChevron = false,
                    enabled = !busy,
                    shape = shape,
                )
            }
        }
    }
    if (choosingAdd && onScanNewDevice != null) {
        FlowChoiceDialog(
            title = stringResource(R.string.linked_add_device),
            options =
                listOf(
                    FlowChoice(
                        AddWay.SHOW_CODE,
                        stringResource(R.string.linked_add_show_code),
                        stringResource(R.string.linked_add_show_code_hint),
                    ),
                    FlowChoice(
                        AddWay.SCAN_CODE,
                        stringResource(R.string.linked_add_scan_code),
                        stringResource(R.string.linked_add_scan_code_hint),
                    ),
                ),
            selected = null,
            onSelect = { way ->
                choosingAdd = false
                when (way) {
                    AddWay.SHOW_CODE -> onAddDevice()
                    AddWay.SCAN_CODE -> onScanNewDevice()
                }
            },
            onDismiss = { choosingAdd = false },
        )
    }
    devices.firstOrNull { it.id == open }?.let { device ->
        LinkedDeviceSheet(
            device = device,
            status = deviceStatus(device, presence, nearby),
            icon = deviceKindIcon(kinds[device.id], device.platform),
            devices = devices,
            consent = consent[device.id],
            onConsent = { onConsent(device.id, it) },
            onRename = { name -> onRename(device.id, name) },
            onRemove = { also -> onRemove(device.id, also) },
            addedBy = addedBy,
            onDismiss = { open = null },
        )
    }
}

/** One line on what a device is doing, or when it last synced. */
@Composable
internal fun deviceStatus(
    device: LinkedDevice,
    presence: Map<String, DevicePresence>,
    nearby: Map<String, Long>,
): String =
    when (val status = deviceStatusOf(device, presence, nearby)) {
        DeviceStatus.ThisDevice -> {
            stringResource(R.string.linked_this_device)
        }

        is DeviceStatus.Playing -> {
            if (status.title.isBlank()) {
                stringResource(R.string.status_playing)
            } else {
                stringResource(R.string.play_on_status_playing, status.title)
            }
        }

        is DeviceStatus.Online -> {
            stringResource(R.string.linked_device_online, status.platform)
        }

        is DeviceStatus.Nearby -> {
            stringResource(R.string.linked_device_nearby, status.platform, synced(status.at))
        }

        is DeviceStatus.Synced -> {
            stringResource(R.string.linked_device_synced, status.platform, synced(status.at))
        }

        is DeviceStatus.Platform -> {
            status.platform
        }
    }

internal fun synced(atMs: Long): String = DateUtils.getRelativeTimeSpanString(atMs).toString()
