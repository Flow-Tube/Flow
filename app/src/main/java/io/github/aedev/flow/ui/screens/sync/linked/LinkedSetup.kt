package io.github.aedev.flow.ui.screens.sync.linked

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.sync2.device.WebDavLogin
import io.github.aedev.flow.sync2.linking.RestoreDevice
import io.github.aedev.flow.sync2.service.LinkedError
import io.github.aedev.flow.ui.screens.sync.SyncActionRow
import io.github.aedev.flow.ui.screens.sync.SyncCard
import io.github.aedev.flow.ui.screens.sync.SyncManualEntryContent
import io.github.aedev.flow.ui.screens.sync.SyncOption
import io.github.aedev.flow.ui.screens.sync.SyncOptions
import io.github.aedev.flow.ui.screens.sync.SyncScanContent
import io.github.aedev.flow.ui.screens.sync.SyncStepHeader

private enum class Setup {
    CHOOSE,
    CREATE_WHERE,
    CREATE_SERVER,
    CREATE_WEBDAV,
    JOIN_SCAN,
    JOIN_PASTE,
    JOIN_LOGIN,
    JOIN_ADMIN,
    RESTORE_WHERE,
    RESTORE_SERVER,
    RESTORE_WEBDAV,
}

/**
 * Linking a device that isn't in a group yet: create one on a Flow server, in a WebDAV folder or on
 * this network alone, join one with a member's code, or restore one with the recovery words.
 * [hostOnly] devices have no camera, so they join by pasting or by showing a code.
 */
@Composable
internal fun LinkedSetup(
    hostOnly: Boolean,
    busy: Boolean,
    /** Why the last setup step failed. */
    error: LinkedError?,
    onCreate: (url: String, adminToken: String) -> Unit,
    onResetAndCreate: (url: String, adminToken: String) -> Unit,
    onCreateOnWebDav: (url: String, user: String, password: String) -> Unit,
    onCreateOnNetwork: () -> Unit,
    onEnterCode: (code: String, login: WebDavLogin?) -> Unit,
    onJoinWithAdmin: (url: String, adminToken: String) -> Unit,
    onShowCode: () -> Unit,
    onRestore: (url: String, words: String) -> Unit,
    onRestoreFromWebDav: (url: String, user: String, password: String, words: String) -> Unit,
    /** The group's devices once the words open it, while the user chooses which to keep. */
    restoreDevices: List<RestoreDevice>?,
    onConfirmRestore: (keep: Set<String>) -> Unit,
    onCancelRestore: () -> Unit,
) {
    var setup by rememberSaveable { mutableStateOf(Setup.CHOOSE) }
    // A WebDAV login entered before scanning; kept in memory only, never in saved state.
    var joinLogin by remember { mutableStateOf<WebDavLogin?>(null) }
    val back = { setup = Setup.CHOOSE }
    val join = if (hostOnly) Setup.JOIN_PASTE else Setup.JOIN_SCAN
    if (error != null && error != LinkedError.SERVER_HAS_GROUP) {
        SyncCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
            Text(error.message(), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
    if (restoreDevices != null) {
        RestoreChoice(restoreDevices, busy, onConfirmRestore, onCancelRestore)
        return
    }
    when (setup) {
        Setup.CHOOSE -> {
            SyncStepHeader(
                icon = Icons.Outlined.Devices,
                title = stringResource(R.string.linked_title),
                body = stringResource(R.string.linked_always_intro),
            )
            SyncOptions(
                listOf(
                    SyncOption(
                        Icons.Outlined.AddLink,
                        stringResource(R.string.linked_create_title),
                        stringResource(R.string.linked_create_body),
                    ) {
                        setup = Setup.CREATE_WHERE
                    },
                    SyncOption(
                        Icons.Outlined.QrCodeScanner,
                        stringResource(R.string.linked_join_title),
                        stringResource(R.string.linked_join_body),
                    ) {
                        setup = join
                    },
                    SyncOption(
                        Icons.Outlined.Dns,
                        stringResource(R.string.linked_join_admin_title),
                        stringResource(R.string.linked_join_admin_body),
                    ) {
                        setup = Setup.JOIN_ADMIN
                    },
                    SyncOption(
                        Icons.Outlined.QrCode2,
                        stringResource(R.string.linked_show_code_title),
                        stringResource(R.string.linked_show_code_body),
                    ) {
                        onShowCode()
                    },
                    SyncOption(
                        Icons.Outlined.Key,
                        stringResource(R.string.linked_restore_title),
                        stringResource(R.string.linked_restore_body),
                    ) {
                        setup = Setup.RESTORE_WHERE
                    },
                ),
            )
        }

        Setup.CREATE_WHERE -> {
            StoreChoice(
                title = stringResource(R.string.linked_where_title),
                onServer = { setup = Setup.CREATE_SERVER },
                onWebDav = { setup = Setup.CREATE_WEBDAV },
                onNetwork = { if (!busy) onCreateOnNetwork() },
                onBack = back,
            )
        }

        Setup.CREATE_SERVER -> {
            var url by rememberSaveable { mutableStateOf("") }
            var token by remember { mutableStateOf("") }
            if (error == LinkedError.SERVER_HAS_GROUP) {
                ServerHasGroup(busy, onJoin = { setup = join }, onReset = { onResetAndCreate(url, token) })
            } else {
                SyncStepHeader(icon = Icons.Outlined.Dns, title = stringResource(R.string.linked_where_server))
                ServerField(url) { url = it }
                AdminTokenField(token) { token = it }
                SyncActionRow(
                    confirmLabel = stringResource(R.string.linked_create),
                    onConfirm = { onCreate(url, token) },
                    confirmEnabled = !busy && url.isNotBlank() && token.isNotBlank(),
                    dismissLabel = stringResource(R.string.linked_back),
                    onDismiss = { setup = Setup.CREATE_WHERE },
                )
            }
        }

        Setup.CREATE_WEBDAV -> {
            WebDavForm(busy, stringResource(R.string.linked_create), onBack = { setup = Setup.CREATE_WHERE }) { url, user, password ->
                onCreateOnWebDav(url, user, password)
            }
        }

        Setup.JOIN_SCAN -> {
            SyncScanContent(prompt = stringResource(R.string.linked_scan_member_code), onScanned = { onEnterCode(it, joinLogin) })
            SyncActionRow(
                confirmLabel = stringResource(R.string.sync_paste_connection_data),
                onConfirm = { setup = Setup.JOIN_PASTE },
                dismissLabel = stringResource(R.string.linked_back),
                onDismiss = back,
            )
            JoinLoginRow(joinLogin != null) { setup = Setup.JOIN_LOGIN }
        }

        Setup.JOIN_PASTE -> {
            SyncManualEntryContent(onSubmit = { onEnterCode(it, joinLogin) })
            SyncActionRow(confirmLabel = stringResource(R.string.linked_back), onConfirm = back)
            JoinLoginRow(joinLogin != null) { setup = Setup.JOIN_LOGIN }
        }

        Setup.JOIN_LOGIN -> {
            var user by rememberSaveable { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            SyncStepHeader(
                icon = Icons.Outlined.Cloud,
                title = stringResource(R.string.linked_join_login_title),
                body = stringResource(R.string.linked_join_login_body),
            )
            WebDavLoginFields(user, { user = it }, password, { password = it })
            SyncActionRow(
                confirmLabel = stringResource(R.string.linked_continue),
                onConfirm = {
                    joinLogin = WebDavLogin(user.trim(), password)
                    setup = join
                },
                confirmEnabled = user.isNotBlank() && password.isNotEmpty(),
                dismissLabel = stringResource(R.string.linked_back),
                onDismiss = { setup = join },
            )
        }

        Setup.JOIN_ADMIN -> {
            var url by rememberSaveable { mutableStateOf("") }
            var token by remember { mutableStateOf("") }
            SyncStepHeader(icon = Icons.Outlined.Dns, title = stringResource(R.string.linked_join_admin_title))
            ServerField(url) { url = it }
            AdminTokenField(token) { token = it }
            SyncActionRow(
                confirmLabel = stringResource(R.string.linked_join_admin_ask),
                onConfirm = { onJoinWithAdmin(url, token) },
                confirmEnabled = !busy && url.isNotBlank() && token.isNotBlank(),
                dismissLabel = stringResource(R.string.linked_back),
                onDismiss = back,
            )
        }

        Setup.RESTORE_WHERE -> {
            StoreChoice(
                title = stringResource(R.string.linked_restore_where_title),
                onServer = { setup = Setup.RESTORE_SERVER },
                onWebDav = { setup = Setup.RESTORE_WEBDAV },
                onNetwork = null,
                onBack = back,
            )
        }

        Setup.RESTORE_SERVER -> {
            var url by rememberSaveable { mutableStateOf("") }
            var words by remember { mutableStateOf("") }
            SyncStepHeader(icon = Icons.Outlined.Key, title = stringResource(R.string.linked_restore_title))
            ServerField(url) { url = it }
            WordsField(words) { words = it }
            SyncActionRow(
                confirmLabel = stringResource(R.string.linked_restore),
                onConfirm = { onRestore(url, words) },
                confirmEnabled = !busy && url.isNotBlank() && words.isNotBlank(),
                dismissLabel = stringResource(R.string.linked_back),
                onDismiss = { setup = Setup.RESTORE_WHERE },
            )
        }

        Setup.RESTORE_WEBDAV -> {
            var words by remember { mutableStateOf("") }
            WebDavForm(
                busy,
                stringResource(
                    R.string.linked_restore,
                ),
                onBack = { setup = Setup.RESTORE_WHERE },
                ready = words.isNotBlank(),
                extra = {
                    WordsField(words) { words = it }
                },
            ) { url, user, password ->
                onRestoreFromWebDav(url, user, password, words)
            }
        }
    }
}

/** Which of the group's [devices] the user still has; a restore signs out the rest, so none is ticked to begin with. */
@Composable
private fun RestoreChoice(
    devices: List<RestoreDevice>,
    busy: Boolean,
    onConfirm: (keep: Set<String>) -> Unit,
    onBack: () -> Unit,
) {
    var keep by rememberSaveable { mutableStateOf(emptyList<String>()) }
    SyncStepHeader(
        icon = Icons.Outlined.Key,
        title = stringResource(R.string.linked_restore_choose_title),
        body = stringResource(R.string.linked_restore_choose_body),
    )
    for (device in devices) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = device.did in keep,
                onCheckedChange = { keep = if (it) keep + device.did else keep - device.did },
            )
            Text(device.name.ifEmpty { stringResource(R.string.linked_restore_unknown_device, device.did.take(8)) })
        }
    }
    SyncActionRow(
        confirmLabel = stringResource(R.string.linked_restore),
        onConfirm = { onConfirm(keep.toSet()) },
        confirmEnabled = !busy,
        dismissLabel = stringResource(R.string.linked_back),
        onDismiss = onBack,
    )
}

/**
 * A server that already holds a group, which nobody here can join: join it from a device still in it,
 * or reset the server, after a warning, and create the new group there.
 */
@Composable
private fun ServerHasGroup(
    busy: Boolean,
    onJoin: () -> Unit,
    onReset: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    SyncStepHeader(
        icon = Icons.Outlined.Dns,
        title = stringResource(R.string.linked_has_group_title),
        body = stringResource(R.string.linked_has_group_body),
    )
    SyncOptions(
        listOf(
            SyncOption(
                Icons.Outlined.QrCodeScanner,
                stringResource(R.string.linked_has_group_join),
                stringResource(R.string.linked_has_group_join_body),
                onJoin,
            ),
            SyncOption(
                Icons.Outlined.DeleteForever,
                stringResource(R.string.linked_reset_server),
                stringResource(R.string.linked_reset_server_body),
            ) {
                if (!busy) confirming = true
            },
        ),
    )
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.linked_reset_server_title)) },
            text = { Text(stringResource(R.string.linked_reset_server_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onReset()
                }) { Text(stringResource(R.string.linked_reset_server_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.linked_cancel)) } },
        )
    }
}

/** Where a group lives: a Flow server, a WebDAV folder, or, when [onNetwork] is given, this network alone. */
@Composable
private fun StoreChoice(
    title: String,
    onServer: () -> Unit,
    onWebDav: () -> Unit,
    onNetwork: (() -> Unit)?,
    onBack: () -> Unit,
) {
    SyncStepHeader(icon = Icons.Outlined.AddLink, title = title)
    SyncOptions(
        listOfNotNull(
            SyncOption(
                Icons.Outlined.Dns,
                stringResource(R.string.linked_where_server),
                stringResource(R.string.linked_where_server_body),
                onServer,
            ),
            SyncOption(
                Icons.Outlined.Cloud,
                stringResource(R.string.linked_where_webdav),
                stringResource(R.string.linked_where_webdav_body),
                onWebDav,
            ),
            onNetwork?.let {
                SyncOption(
                    Icons.Outlined.Wifi,
                    stringResource(R.string.linked_create_lan_title),
                    stringResource(R.string.linked_create_lan_body),
                    it,
                )
            },
        ),
    )
    SyncActionRow(confirmLabel = stringResource(R.string.linked_back), onConfirm = onBack)
}

/** A WebDAV folder's address and login, with [extra] fields below, for [label]. */
@Composable
private fun WebDavForm(
    busy: Boolean,
    label: String,
    onBack: () -> Unit,
    ready: Boolean = true,
    extra: @Composable () -> Unit = {},
    onConfirm: (url: String, user: String, password: String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    SyncStepHeader(
        icon = Icons.Outlined.Cloud,
        title = stringResource(R.string.linked_where_webdav),
        body = stringResource(R.string.linked_webdav_body),
    )
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.linked_webdav_url)) },
        placeholder = { Text(stringResource(R.string.linked_webdav_url_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        singleLine = true,
    )
    WebDavLoginFields(user, { user = it }, password, { password = it })
    extra()
    SyncActionRow(
        confirmLabel = label,
        onConfirm = { onConfirm(url.trim(), user.trim(), password) },
        confirmEnabled = !busy && ready && url.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
        dismissLabel = stringResource(R.string.linked_back),
        onDismiss = onBack,
    )
}

/** A WebDAV user name and password; the password is never kept in saved state. */
@Composable
internal fun WebDavLoginFields(
    user: String,
    onUser: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
) {
    OutlinedTextField(
        value = user,
        onValueChange = onUser,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.linked_webdav_user)) },
        singleLine = true,
    )
    OutlinedTextField(
        value = password,
        onValueChange = onPassword,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.linked_webdav_password)) },
        supportingText = { Text(stringResource(R.string.linked_webdav_password_hint)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
    )
}

/** Offers a WebDAV login before scanning, so pairing can go through the folder when the devices are on different networks. */
@Composable
private fun JoinLoginRow(
    entered: Boolean,
    onEnter: () -> Unit,
) {
    SyncActionRow(
        confirmLabel = stringResource(if (entered) R.string.linked_join_login_entered else R.string.linked_join_login_title),
        onConfirm = onEnter,
    )
}

@Composable
private fun WordsField(
    words: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = words,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.linked_recovery_words_field)) },
        minLines = 3,
    )
}

@Composable
private fun ServerField(
    url: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = url,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.linked_server_url)) },
        placeholder = { Text(stringResource(R.string.linked_server_url_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        singleLine = true,
    )
}

@Composable
private fun AdminTokenField(
    token: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = token,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.linked_admin_token)) },
        supportingText = { Text(stringResource(R.string.linked_admin_token_hint)) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
    )
}
