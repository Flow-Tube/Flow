package io.github.aedev.flow.ui.screens.sync.linked

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.sync2.data.WEBDAV_STORE
import io.github.aedev.flow.sync2.service.LinkedError
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.screens.sync.SyncActionRow
import io.github.aedev.flow.ui.screens.sync.SyncInfoItem
import io.github.aedev.flow.ui.screens.sync.SyncRowGroup
import io.github.aedev.flow.ui.screens.sync.SyncSection

/**
 * The group's rarer actions, last on the page: where it syncs, a WebDAV folder's password, the
 * recovery words, and leaving.
 */
@Composable
internal fun LinkedGroupRows(
    state: LinkedUiState,
    onReplaceWords: () -> Unit,
    onLeave: () -> Unit,
    onStartLoginChange: (user: String, password: String) -> Unit,
) {
    val store = linkedStore(state.storeUrl)
    // A refused login is asked for again at the top of the page, and a change under way shows there.
    val canChangeLogin = store == LinkedStore.WEBDAV && state.error != LinkedError.LOGIN_REFUSED && state.loginWaiting == null
    var changingLogin by rememberSaveable { mutableStateOf(false) }
    val rows =
        buildList<@Composable (Shape) -> Unit> {
            add { shape ->
                SyncInfoItem(
                    icon = store.icon(),
                    title = stringResource(R.string.linked_where_it_syncs),
                    supporting =
                        if (store == LinkedStore.LAN) {
                            stringResource(R.string.linked_server_lan)
                        } else {
                            state.storeUrl?.removePrefix(WEBDAV_STORE)
                        },
                    shape = shape,
                )
            }
            if (canChangeLogin) {
                add { shape ->
                    FlowNavRow(
                        title = stringResource(R.string.linked_login_change_title),
                        onClick = { changingLogin = true },
                        leadingIcon = Icons.Outlined.Password,
                        enabled = !state.busy,
                        shape = shape,
                    )
                }
            }
            add { shape ->
                FlowNavRow(
                    title = stringResource(R.string.linked_recovery_words),
                    onClick = onReplaceWords,
                    supportingText = stringResource(R.string.linked_replace_words_hint),
                    leadingIcon = Icons.Outlined.Key,
                    enabled = !state.busy,
                    shape = shape,
                )
            }
            add { shape ->
                FlowNavRow(
                    title = stringResource(R.string.linked_leave),
                    onClick = onLeave,
                    leadingIcon = Icons.AutoMirrored.Outlined.Logout,
                    showChevron = false,
                    destructive = true,
                    enabled = !state.busy,
                    shape = shape,
                )
            }
        }
    SyncSection(stringResource(R.string.linked_group)) {
        SyncRowGroup(count = rows.size) { index, shape -> rows[index](shape) }
    }
    if (canChangeLogin && (changingLogin || state.loginDue)) {
        LoginChangeForm(
            state = state,
            onStart = { user, password ->
                changingLogin = false
                onStartLoginChange(user, password)
            },
            onCancel = { changingLogin = false },
        )
    }
}

/** Replacing the folder's login (spec/protocol.md, Replacing a store's login): the new one, sent to every device first. */
@Composable
private fun LoginChangeForm(
    state: LinkedUiState,
    onStart: (user: String, password: String) -> Unit,
    onCancel: () -> Unit,
) {
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Text(stringResource(R.string.linked_login_change_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
    if (state.loginDue) Text(stringResource(R.string.linked_login_change_due))
    Text(stringResource(R.string.linked_login_change_first))
    WebDavLoginFields(user, { user = it }, password, { password = it })
    SyncActionRow(
        confirmLabel = stringResource(R.string.linked_login_change_send),
        onConfirm = { onStart(user.trim(), password) },
        confirmEnabled = !state.busy && user.isNotBlank() && password.isNotEmpty(),
        dismissLabel = if (state.loginDue) null else stringResource(R.string.linked_cancel),
        onDismiss = onCancel,
    )
}
