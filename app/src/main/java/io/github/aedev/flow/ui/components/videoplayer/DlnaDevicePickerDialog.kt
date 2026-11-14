package io.github.aedev.flow.ui.components.videoplayer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.player.dlna.DlnaDevice
import io.github.aedev.flow.ui.components.shared.FlowAlertDialog

/** DLNA / UPnP device-picker dialog shown when the cast button is pressed. */
@Composable
internal fun DlnaDevicePickerDialog(
    devices: List<DlnaDevice>,
    isDiscovering: Boolean,
    isCasting: Boolean,
    videoTitle: String,
    onDeviceSelected: (DlnaDevice) -> Unit,
    onStopCasting: () -> Unit,
    onDismiss: () -> Unit,
) {
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text =
                        stringResource(
                            if (isCasting) R.string.cast_connected else R.string.dlna_cast_to_device,
                        ),
                )
                if (isDiscovering) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        },
        text = {
            Column {
                if (!isCasting && devices.isEmpty()) {
                    Text(
                        text = stringResource(if (isDiscovering) R.string.dlna_searching else R.string.dlna_no_devices),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.dlna_google_cast_unsupported),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (isCasting) {
                    Text(
                        text = stringResource(R.string.dlna_now_casting, videoTitle),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn {
                        items(devices, key = { it.usn }) { device ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onDeviceSelected(device) }
                                        .padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = device.friendlyName,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isCasting) {
                TextButton(onClick = onStopCasting) {
                    Text(stringResource(R.string.dlna_stop_casting))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
