package io.github.aedev.flow.ui.screens.settings.integrations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.FlowAlertDialog

private val FieldSpacing = 8.dp

/**
 * The list's server and, for a server that signs with its own key, that public key. A rejected
 * entry keeps the dialog open with the reason, and nothing is saved.
 */
@Composable
internal fun AiListSourceDialog(
    initialUrl: String?,
    initialKey: String?,
    onSave: (url: String, key: String, onResult: (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl.orEmpty()) }
    var key by rememberSaveable { mutableStateOf(initialKey.orEmpty()) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val save = { newUrl: String, newKey: String ->
        onSave(newUrl, newKey) { saved -> if (saved) onDismiss() else invalid = true }
    }
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_list_source_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                Text(stringResource(R.string.ai_list_source_dialog_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        invalid = false
                    },
                    label = { Text(stringResource(R.string.ai_list_source_url_hint)) },
                    placeholder = { Text(stringResource(R.string.ai_list_source_url_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = invalid,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = {
                        key = it
                        invalid = false
                    },
                    label = { Text(stringResource(R.string.ai_list_source_key_hint)) },
                    supportingText = {
                        Text(stringResource(if (invalid) R.string.ai_list_source_invalid else R.string.ai_list_source_key_help))
                    },
                    isError = invalid,
                    maxLines = 4,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { save("", "") }) { Text(stringResource(R.string.ai_list_source_reset)) }
                TextButton(onClick = { save(url, key) }) { Text(stringResource(R.string.btn_save)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) } },
    )
}

/** The name AiSList shows beside the viewer's reports; empty turns reporting back into a prompt. */
@Composable
internal fun AiReportNameDialog(
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_report_name_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                Text(stringResource(R.string.ai_report_name_dialog_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ai_report_name_hint)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(name)
                onDismiss()
            }) { Text(stringResource(R.string.btn_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) } },
    )
}
