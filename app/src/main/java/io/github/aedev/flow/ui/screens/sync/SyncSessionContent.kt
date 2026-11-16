package io.github.aedev.flow.ui.screens.sync

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.MergeType
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aedev.flow.R
import io.github.aedev.flow.sync.SyncState
import io.github.aedev.flow.sync.protocol.ApplyStats
import io.github.aedev.flow.ui.components.shared.FlowSpinner
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation
import io.github.aedev.flow.utils.copyPlainText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The steps that run once a session is live: QR connection, verification, merge consent, outcome. */

private val QrMaxWidthWithRemote = 240.dp

@Composable
internal fun SyncQrContent(
    s: SyncState.ShowingQr,
    onPrepareConnectionData: () -> String?,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // A remote focuses the actions first, and TV scrolls a focused item to a third of the way down; above
    // the code, capped to phone-camera size, they leave all of it on screen.
    val dpad = LocalDpadNavigation.current
    var remaining by remember { mutableLongStateOf(s.ttlSeconds) }
    LaunchedEffect(s.expiresAtEpochSeconds) {
        while (true) {
            remaining = (s.expiresAtEpochSeconds - System.currentTimeMillis() / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }

    val actions: @Composable () -> Unit = {
        SyncActionRow(
            confirmLabel = stringResource(R.string.sync_copy_connection_data),
            onConfirm = {
                val connectionData = onPrepareConnectionData() ?: s.qrText
                scope.launch {
                    clipboard.copyPlainText(
                        label = context.getString(R.string.sync_connection_data_label),
                        text = connectionData,
                        sensitive = true,
                    )
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, R.string.toast_copied_to_clipboard, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            dismissLabel = stringResource(R.string.sync_cancel_session),
            onDismiss = onCancel,
        )
    }

    Text(
        text =
            if (s.sending) {
                stringResource(R.string.sync_qr_scan_on_target_sending)
            } else {
                stringResource(R.string.sync_qr_scan_on_target_receiving)
            },
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
    )

    if (dpad) actions()

    // The code stays dark-on-light so any camera can read it, and sits on a tonal plate so it reads
    // as a deliberate element in both themes. Rounding only ever clips the quiet zone, never the
    // finder patterns.
    SyncCard(modifier = if (dpad) Modifier.widthIn(max = QrMaxWidthWithRemote) else Modifier) {
        QrCodeImage(
            text = s.qrText,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .aspectRatio(1f),
        )
    }

    SyncCard {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.sync_expires_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.sync_expires_seconds, remaining),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { (remaining.toFloat() / s.ttlSeconds).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    SyncInfoRow(icon = Icons.Outlined.Lan, text = stringResource(R.string.sync_qr_advertised_address, s.address))
    SyncInfoRow(icon = Icons.Outlined.Wifi, text = stringResource(R.string.sync_qr_network_note))
    SyncInfoRow(icon = Icons.Outlined.Shield, text = stringResource(R.string.sync_verification_after_connect))
    SyncInfoRow(icon = Icons.Outlined.Password, text = stringResource(R.string.sync_connection_data_security_note))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(R.string.sync_waiting_for_peer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (!dpad) actions()
}

/** The 6-digit short authentication string, spaced so two people can read it aloud reliably. */
@Composable
private fun SasReadout(sas: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Text(
            text = sas,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            style = MaterialTheme.typography.displaySmall,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 8.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun SyncSasContent(
    sas: String,
    onConfirm: (Boolean) -> Unit,
) {
    SyncStepHeader(
        icon = Icons.Outlined.Password,
        title = stringResource(R.string.sync_sas_title),
        body = stringResource(R.string.sync_sas_body),
    )
    SasReadout(sas)
    SyncActionRow(
        confirmLabel = stringResource(R.string.sync_sas_match),
        onConfirm = { onConfirm(true) },
        dismissLabel = stringResource(R.string.sync_sas_differ),
        onDismiss = { onConfirm(false) },
    )
}

@Composable
internal fun SyncConsentContent(
    collections: List<String>,
    onDecision: (Boolean) -> Unit,
) {
    SyncStepHeader(
        icon = Icons.Outlined.MergeType,
        title = stringResource(R.string.sync_consent_title),
    )
    SyncRowGroup(count = collections.size) { index, shape ->
        val key = collections[index]
        SyncInfoItem(icon = collectionIcon(key), title = collectionLabel(key), shape = shape)
    }
    SyncInfoRow(icon = Icons.Outlined.Shield, text = stringResource(R.string.sync_consent_note))
    SyncActionRow(
        confirmLabel = stringResource(R.string.sync_merge),
        onConfirm = { onDecision(true) },
        dismissLabel = stringResource(R.string.sync_decline),
        onDismiss = { onDecision(false) },
    )
}

@Composable
internal fun SyncTransferContent(s: SyncState.Transferring) {
    SyncStepHeader(
        icon = Icons.Outlined.Sync,
        title = stringResource(R.string.sync_transferring, collectionLabel(s.collection)),
    )
    SyncCard {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (s.total > 0) {
                LinearProgressIndicator(
                    progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = stringResource(R.string.sync_progress_fraction, s.done, s.total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SyncDoneContent(
    s: SyncState.Done,
    onDone: () -> Unit,
) {
    SyncStepHeader(
        icon = Icons.Outlined.CheckCircle,
        title = stringResource(R.string.sync_complete),
        body = stringResource(R.string.sync_synced_with, s.peerName),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    if (s.stats.isNotEmpty()) {
        val stats = s.stats.toList()
        SyncRowGroup(count = stats.size) { index, shape ->
            val (collection, applied) = stats[index]
            SyncInfoItem(
                icon = collectionIcon(collection),
                title = collectionLabel(collection),
                supporting = statsSummary(applied),
                shape = shape,
            )
        }
    }
    SyncActionRow(confirmLabel = stringResource(R.string.sync_done_button), onConfirm = onDone)
}

@Composable
private fun statsSummary(stats: ApplyStats): String =
    stringResource(R.string.sync_done_stats_summary, stats.added, stats.updated, stats.skipped)

@Composable
internal fun SyncFailedContent(
    message: String,
    onRetry: () -> Unit,
) {
    SyncStepHeader(
        icon = Icons.Outlined.ErrorOutline,
        title = stringResource(R.string.sync_failed_title),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    )
    SyncCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
        Text(
            text = message,
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
    SyncActionRow(confirmLabel = stringResource(R.string.sync_try_again), onConfirm = onRetry)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SyncBusyContent(label: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        FlowSpinner()
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
