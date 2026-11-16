package io.github.aedev.flow.ui.screens.settings.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.FlowAlertDialog
import io.github.aedev.flow.ui.components.shared.FlowConnectedToggleGroup
import io.github.aedev.flow.ui.components.shared.FlowToggleOption

private val FieldSpacing = 12.dp

/** A cache size typed in by hand, from 50 MB to 50 GB; OK stays off until the number is in range. */
@Composable
internal fun DownloadCacheCustomSizeDialog(
    title: String,
    currentMb: Int?,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val startUnit = currentMb?.let(CacheSizeInput::unitFor) ?: CacheSizeUnit.MB
    var unit by rememberSaveable { mutableStateOf(startUnit) }
    var text by rememberSaveable { mutableStateOf(currentMb?.let { CacheSizeInput.format(it, startUnit) }.orEmpty()) }
    val megabytes = CacheSizeInput.parse(text, unit)
    val invalid = text.isNotBlank() && megabytes == null

    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                Text(
                    text = stringResource(R.string.cache_size_applies_on_restart),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.cache_size_custom_field)) },
                    suffix = { Text(stringResource(unit.label())) },
                    supportingText = { Text(stringResource(R.string.cache_size_custom_range)) },
                    isError = invalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowConnectedToggleGroup(
                    options = CacheSizeUnit.entries.map { FlowToggleOption(it, stringResource(it.label())) },
                    selected = unit,
                    onSelected = { unit = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    megabytes?.let(onConfirm)
                    onDismiss()
                },
                enabled = megabytes != null,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun CacheSizeUnit.label(): Int =
    when (this) {
        CacheSizeUnit.MB -> R.string.cache_size_unit_mb
        CacheSizeUnit.GB -> R.string.cache_size_unit_gb
    }
