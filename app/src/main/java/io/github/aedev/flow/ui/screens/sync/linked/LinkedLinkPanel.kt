package io.github.aedev.flow.ui.screens.sync.linked

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.sync2.service.LinkPhase
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation
import io.github.aedev.flow.ui.screens.sync.QrCodeImage
import io.github.aedev.flow.ui.screens.sync.SyncActionRow
import io.github.aedev.flow.ui.screens.sync.SyncBusyContent
import io.github.aedev.flow.ui.screens.sync.SyncCard
import io.github.aedev.flow.ui.screens.sync.SyncFailedContent
import io.github.aedev.flow.ui.screens.sync.SyncSasContent
import io.github.aedev.flow.ui.screens.sync.SyncStepHeader
import io.github.aedev.flow.ui.screens.sync.dpadQrPlate

/** Adding or joining a device: the code to show, the six digits to compare, and the outcome. */
@Composable
internal fun LinkedLinkPanel(
    phase: LinkPhase,
    onAnswer: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    when (phase) {
        LinkPhase.Idle -> {}

        is LinkPhase.Waiting -> {
            val code = phase.code
            val cancel: @Composable () -> Unit = {
                SyncActionRow(confirmLabel = stringResource(R.string.linked_cancel), onConfirm = onClose)
            }
            if (code == null) {
                SyncBusyContent(stringResource(R.string.linked_waiting))
                cancel()
            } else {
                // A remote lands on Cancel; above the code, focusing it leaves the whole code on screen.
                val dpad = LocalDpadNavigation.current
                SyncStepHeader(
                    icon = Icons.Outlined.QrCode2,
                    title = stringResource(R.string.linked_scan_this_code),
                    body = stringResource(R.string.linked_scan_this_code_body),
                )
                if (dpad) cancel()
                SyncCard(
                    modifier = Modifier.dpadQrPlate(bringIntoView = true),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                ) {
                    QrCodeImage(code, Modifier.fillMaxWidth().aspectRatio(1f).padding(16.dp))
                }
                if (!dpad) cancel()
            }
        }

        is LinkPhase.Confirm -> {
            SyncSasContent(phase.sas, onConfirm = onAnswer)
        }

        LinkPhase.Asked -> {
            SyncBusyContent(stringResource(R.string.linked_asked))
            SyncActionRow(confirmLabel = stringResource(R.string.linked_cancel), onConfirm = onClose)
        }

        LinkPhase.Linking -> {
            SyncBusyContent(stringResource(R.string.linked_linking))
        }

        LinkPhase.Done -> {
            SyncStepHeader(
                icon = Icons.Outlined.CheckCircle,
                title = stringResource(R.string.linked_done_title),
                body = stringResource(R.string.linked_done_body),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            SyncActionRow(confirmLabel = stringResource(R.string.linked_done), onConfirm = onClose)
        }

        is LinkPhase.Failed -> {
            SyncFailedContent(phase.error.message(), onRetry = onClose)
        }
    }
}
