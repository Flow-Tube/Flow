package io.github.aedev.flow.ui.screens.settings.region

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.translation.TranslationServerType
import io.github.aedev.flow.data.translation.TranslationSettings
import io.github.aedev.flow.ui.components.shared.FlowAlertDialog
import io.github.aedev.flow.ui.components.shared.FlowConnectedToggleGroup
import io.github.aedev.flow.ui.components.shared.FlowToggleOption

private val FieldSpacing = 8.dp

/**
 * The viewer's own LibreTranslate or Lingva server (#892). There is no preset one. An address Flow
 * won't send text to keeps the dialog open with the reason, and nothing is saved.
 */
@Composable
internal fun TranslationServerDialog(
    initial: TranslationSettings,
    onSave: (type: TranslationServerType, url: String, apiKey: String, onResult: (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var type by rememberSaveable { mutableStateOf(initial.serverType) }
    var url by rememberSaveable { mutableStateOf(initial.serverUrl.orEmpty()) }
    var apiKey by rememberSaveable { mutableStateOf(initial.apiKey.orEmpty()) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.translation_server_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                Text(stringResource(R.string.translation_server_dialog_body), style = MaterialTheme.typography.bodyMedium)
                FlowConnectedToggleGroup(
                    options =
                        listOf(
                            FlowToggleOption(
                                TranslationServerType.LIBRETRANSLATE,
                                stringResource(R.string.translation_server_libretranslate),
                            ),
                            FlowToggleOption(TranslationServerType.LINGVA, stringResource(R.string.translation_server_lingva)),
                        ),
                    selected = type,
                    onSelected = { type = it },
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        invalid = false
                    },
                    label = { Text(stringResource(R.string.translation_server_url_hint)) },
                    placeholder = { Text(stringResource(R.string.translation_server_url_placeholder)) },
                    supportingText = {
                        Text(stringResource(if (invalid) R.string.translation_server_invalid else R.string.translation_server_url_help))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = invalid,
                )
                if (type == TranslationServerType.LIBRETRANSLATE) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringResource(R.string.translation_server_key_hint)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(type, url, apiKey) { saved -> if (saved) onDismiss() else invalid = true } }) {
                Text(stringResource(R.string.btn_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) } },
    )
}
