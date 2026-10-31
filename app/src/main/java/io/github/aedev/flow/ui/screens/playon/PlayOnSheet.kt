package io.github.aedev.flow.ui.screens.playon

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.sync2.live.ConsentAnswer
import io.github.aedev.flow.ui.components.shared.FlowSheetHeader
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsSheet

private val SheetPadding = 16.dp

/** "Play on" for the whole app, provided once at the root; absent where it isn't, as in previews and tests. */
val LocalPlayOn = staticCompositionLocalOf<PlayOnViewModel?> { null }

/**
 * Opens "Play on" (Flow-Tube/Flow#1412), when the group can reach another device. Tinted while
 * the players drive another device, so they show that it isn't playing here. A host with its own
 * button size passes [buttonSize] and [iconSize].
 */
@Composable
fun PlayOnButton(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    buttonSize: Dp? = null,
    iconSize: Dp? = null,
) {
    val viewModel = LocalPlayOn.current ?: return
    val available by viewModel.available.collectAsStateWithLifecycle()
    if (!available) return
    val elsewhere by viewModel.elsewhere.collectAsStateWithLifecycle()
    IconButton(
        onClick = { viewModel.open.value = true },
        modifier = if (buttonSize != null) modifier.size(buttonSize) else modifier,
    ) {
        Icon(
            imageVector = Icons.Outlined.Devices,
            contentDescription =
                elsewhere?.let { stringResource(R.string.play_on_playing_on, it.name) } ?: stringResource(R.string.play_on_button),
            tint = if (elsewhere != null) MaterialTheme.colorScheme.primary else tint,
            modifier = if (iconSize != null) Modifier.size(iconSize) else Modifier,
        )
    }
}

/**
 * The shell's part of "Play on": the sheet when it is open, the consent question when a device
 * first asks, and a note when another device takes over playback here. [onManageDevices] opens
 * Linked devices from the sheet; without it the sheet has no such row.
 */
@Composable
fun PlayOnHost(onManageDevices: (() -> Unit)? = null) {
    val viewModel = LocalPlayOn.current ?: return
    val open by viewModel.open.collectAsStateWithLifecycle()
    val request by viewModel.consentRequest.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.controlledBy.collect { did ->
            Toast.makeText(context, context.getString(R.string.play_on_controlled_by, viewModel.nameOf(did)), Toast.LENGTH_SHORT).show()
        }
    }
    if (open) {
        QuickActionsSheet(onDismiss = { viewModel.open.value = false }, expandFully = true) { sheet ->
            PlayOnContent(
                viewModel,
                onManageDevices = onManageDevices?.let { manage -> { sheet.hideThen(manage) } },
            )
        }
    }
    request?.let { asking ->
        val name = viewModel.nameOf(asking.did)
        AlertDialog(
            onDismissRequest = { viewModel.answer(asking, ConsentAnswer.DECLINE) },
            icon = { Icon(Icons.Outlined.Devices, contentDescription = null) },
            title = { Text(stringResource(R.string.play_on_consent_title, name)) },
            text = { Text(stringResource(R.string.play_on_consent_body, name)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        onClick = { viewModel.answer(asking, ConsentAnswer.ALWAYS) },
                    ) { Text(stringResource(R.string.play_on_consent_always)) }
                    TextButton(
                        onClick = { viewModel.answer(asking, ConsentAnswer.ONCE) },
                    ) { Text(stringResource(R.string.play_on_consent_once)) }
                    TextButton(
                        onClick = { viewModel.answer(asking, ConsentAnswer.DECLINE) },
                    ) { Text(stringResource(R.string.play_on_consent_decline)) }
                }
            },
        )
    }
}

/** The sheet itself: the active session's card on top, then the group's devices. */
@Composable
internal fun PlayOnContent(
    viewModel: PlayOnViewModel,
    onManageDevices: (() -> Unit)?,
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val active by viewModel.activeDevice.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val movingTo by viewModel.movingTo.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    // Reads the players while the sheet shows, on each change rather than on a timer.
    LaunchedEffect(viewModel) { viewModel.watch() }
    FlowSheetHeader(
        title = stringResource(R.string.play_on_title),
        subtitle = stringResource(R.string.play_on_subtitle),
        onClose = null,
        showDragHandle = false,
        dividerAlpha = null,
    )
    val cardModifier = Modifier.fillMaxWidth().padding(horizontal = SheetPadding)
    val current = session
    if (current != null && current.now.video != null) {
        PlayOnNowCard(
            session = current,
            buffering = busy && movingTo == null,
            onControl = { cmd, vol -> viewModel.control(current, cmd, vol = vol) },
            modifier = cardModifier,
        )
    } else {
        PlayOnNothingCard(cardModifier)
    }
    DeviceList(
        devices = devices,
        state = PlayOnRowState(active = active, movingTo = movingTo, failed = failed),
        onPick = viewModel::playOn,
        onManage = onManageDevices,
    )
}
