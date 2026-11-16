package io.github.aedev.flow.ui.components.musicplayer.full

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.DlnaDevicePickerDialog
import io.github.aedev.flow.ui.screens.music.MusicCastViewModel

/** In the full player's top bar while the music plays on a TV, in the primary colour; it opens the picker to stop casting. */
@Composable
internal fun MusicCastButton(viewModel: MusicCastViewModel = hiltViewModel()) {
    val device by viewModel.device.collectAsStateWithLifecycle()
    val name = device?.friendlyName ?: return
    IconButton(onClick = viewModel::openPicker) {
        Icon(
            imageVector = Icons.Rounded.Cast,
            contentDescription = stringResource(R.string.play_on_playing_on, name),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** The device picker that "Cast to TV" in the song menu and [MusicCastButton] open. */
@Composable
internal fun MusicCastHost(
    title: String,
    viewModel: MusicCastViewModel = hiltViewModel(),
) {
    val open by viewModel.pickerOpen.collectAsStateWithLifecycle()
    if (!open) return
    val device by viewModel.device.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val discovering by viewModel.discovering.collectAsStateWithLifecycle()
    DlnaDevicePickerDialog(
        devices = devices,
        isDiscovering = discovering,
        isCasting = device != null,
        title = title,
        onDeviceSelected = viewModel::cast,
        onStopCasting = viewModel::stop,
        onDismiss = viewModel::closePicker,
    )
}
