package io.github.aedev.flow.ui.screens.settings.scrobbling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.scrobble.AudioscrobblerKeys
import io.github.aedev.flow.data.scrobble.ScrobbleService
import io.github.aedev.flow.data.scrobble.ScrobbleWebhook
import io.github.aedev.flow.data.scrobble.isWebhookAddress
import io.github.aedev.flow.ui.components.shared.FlowAlertDialog

private val FieldSpacing = 8.dp

internal enum class ScrobbleDialogKind { SIGN_IN, SIGN_OUT, OWN_KEY, OFFER_IMPORT, WEBHOOK }

@Composable
internal fun ScrobbleService.label(): String =
    stringResource(
        when (this) {
            ScrobbleService.LASTFM -> R.string.scrobbling_lastfm
            ScrobbleService.LIBREFM -> R.string.scrobbling_librefm
            ScrobbleService.LISTENBRAINZ -> R.string.scrobbling_listenbrainz
        },
    )

/** A user name and password for Last.fm and Libre.fm, or the user token for ListenBrainz. */
@Composable
internal fun SignInDialog(
    service: ScrobbleService,
    state: SignInState,
    onSubmit: (userName: String, secret: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val usesToken = service == ScrobbleService.LISTENBRAINZ
    var userName by rememberSaveable { mutableStateOf("") }
    var secret by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state) { if (state == SignInState.Done) onDismiss() }
    val working = state == SignInState.Working
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scrobbling_sign_in_title, service.label())) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                if (!usesToken) {
                    OutlinedTextField(
                        value = userName,
                        onValueChange = { userName = it },
                        label = { Text(stringResource(R.string.scrobbling_user_name)) },
                        singleLine = true,
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SecretField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = stringResource(if (usesToken) R.string.scrobbling_token else R.string.scrobbling_password),
                    enabled = !working,
                )
                Text(
                    text =
                        when (state) {
                            is SignInState.Failed -> state.message ?: stringResource(R.string.scrobbling_sign_in_failed)
                            else -> stringResource(if (usesToken) R.string.scrobbling_token_note else R.string.scrobbling_password_note)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (state is SignInState.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(userName, secret) },
                enabled = !working && secret.isNotBlank() && (usesToken || userName.isNotBlank()),
            ) { Text(stringResource(R.string.scrobbling_sign_in)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) } },
    )
}

@Composable
internal fun SignOutDialog(
    service: ScrobbleService,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scrobbling_sign_out_title, service.label())) },
        text = { Text(stringResource(R.string.scrobbling_sign_out_body)) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(stringResource(R.string.scrobbling_sign_out)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) } },
    )
}

/** Offered once right after signing in; the same import stays available on the account's row. */
@Composable
internal fun ImportOfferDialog(
    service: ScrobbleService,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scrobbling_import_offer_title, service.label())) },
        text = { Text(stringResource(R.string.scrobbling_import_offer_body)) },
        confirmButton = {
            TextButton(onClick = {
                onImport()
                onDismiss()
            }) { Text(stringResource(R.string.scrobbling_import_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.scrobbling_import_later)) } },
    )
}

@Composable
internal fun OwnKeyDialog(
    current: AudioscrobblerKeys,
    onSave: (AudioscrobblerKeys) -> Unit,
    onDismiss: () -> Unit,
) {
    var apiKey by rememberSaveable { mutableStateOf(current.apiKey) }
    var secret by rememberSaveable { mutableStateOf(current.secret) }
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scrobbling_own_key_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                Text(stringResource(R.string.scrobbling_own_key_dialog_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.scrobbling_api_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecretField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = stringResource(R.string.scrobbling_secret),
                    enabled = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(AudioscrobblerKeys(apiKey.trim(), secret.trim()))
                onDismiss()
            }) { Text(stringResource(R.string.btn_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) } },
    )
}

/** Adds a webhook, or edits [current] with a delete button beside Cancel. */
@Composable
internal fun WebhookDialog(
    current: ScrobbleWebhook?,
    onSave: (name: String, url: String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(current?.name.orEmpty()) }
    var url by rememberSaveable { mutableStateOf(current?.url.orEmpty()) }
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scrobbling_webhook_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.scrobbling_webhook_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.scrobbling_webhook_url)) },
                    supportingText = { Text(stringResource(R.string.scrobbling_webhook_url_note)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(name, url)
                    onDismiss()
                },
                enabled = isWebhookAddress(url),
            ) { Text(stringResource(R.string.btn_save)) }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = {
                        onDelete()
                        onDismiss()
                    }) { Text(stringResource(R.string.delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
            }
        },
    )
}

@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(stringResource(if (visible) R.string.scrobbling_hide_secret else R.string.scrobbling_show_secret))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
