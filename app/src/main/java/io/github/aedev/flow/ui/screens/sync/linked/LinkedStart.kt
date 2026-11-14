package io.github.aedev.flow.ui.screens.sync.linked

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.FlowActionButtonPair
import io.github.aedev.flow.ui.components.shared.FlowPairAction
import io.github.aedev.flow.ui.screens.sync.SyncOption
import io.github.aedev.flow.ui.screens.sync.SyncOptions
import io.github.aedev.flow.ui.screens.sync.SyncSection

private val IntroInset = 4.dp

/**
 * The page before this device is linked: what linking does, the two ways most people start as
 * buttons, and the rarer ways as rows. A device without a camera ([hostOnly]) starts by showing a
 * code instead of scanning one.
 */
@Composable
internal fun LinkedStart(
    hostOnly: Boolean,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onJoinWithAdmin: () -> Unit,
    onShowCode: () -> Unit,
    onRestore: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = IntroInset),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.linked_intro_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.linked_intro_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val create = FlowPairAction(Icons.Outlined.AddLink, stringResource(R.string.linked_create_title), onCreate)
    val join =
        SyncOption(
            Icons.Outlined.QrCodeScanner,
            stringResource(R.string.linked_join_title),
            stringResource(R.string.linked_join_body),
            onJoin,
        )
    val showCode =
        SyncOption(
            Icons.Outlined.QrCode2,
            stringResource(R.string.linked_show_code_title),
            stringResource(R.string.linked_show_code_body),
            onShowCode,
        )
    val second = if (hostOnly) showCode else join
    FlowActionButtonPair(primary = create, secondary = FlowPairAction(second.icon, second.title, second.onClick))
    SyncSection(stringResource(R.string.linked_other_ways)) {
        SyncOptions(
            listOfNotNull(
                if (hostOnly) join else null,
                SyncOption(
                    Icons.Outlined.Dns,
                    stringResource(R.string.linked_join_admin_title),
                    stringResource(R.string.linked_join_admin_body),
                    onJoinWithAdmin,
                ),
                if (hostOnly) null else showCode,
                SyncOption(
                    Icons.Outlined.Key,
                    stringResource(R.string.linked_restore_title),
                    stringResource(R.string.linked_restore_body),
                    onRestore,
                ),
            ),
        )
    }
}
