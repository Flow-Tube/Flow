package io.github.aedev.flow.ui.screens.sync.linked

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.screens.sync.QrCodeImage
import io.github.aedev.flow.ui.screens.sync.SyncActionRow
import io.github.aedev.flow.ui.screens.sync.SyncCard
import io.github.aedev.flow.ui.screens.sync.SyncRowGroup
import io.github.aedev.flow.ui.screens.sync.SyncStepHeader

/** How many words the user types back before the key counts as saved (spec/protocol.md, Recovery). */
private const val CHECKED_WORDS = 2

/** Shows the recovery words once, then asks for two of them back before calling them saved. */
@Composable
internal fun LinkedRecoveryWords(
    words: String,
    busy: Boolean,
    onSaved: () -> Unit,
) {
    val list = words.split(' ')
    var checking by rememberSaveable { mutableStateOf(false) }
    if (!checking) {
        SyncStepHeader(
            icon = Icons.Outlined.Key,
            title = stringResource(R.string.linked_words_title),
            body = stringResource(R.string.linked_words_body),
        )
        SyncCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list.withIndex().chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        pair.forEach { (i, word) ->
                            Text("${i + 1}. $word", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
        SyncCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
            QrCodeImage(words, Modifier.fillMaxWidth().aspectRatio(1f).padding(16.dp))
        }
        SyncActionRow(confirmLabel = stringResource(R.string.linked_words_saved), onConfirm = { checking = true })
        return
    }

    val positions by rememberSaveable {
        mutableStateOf(
            list.indices
                .shuffled()
                .take(CHECKED_WORDS)
                .sorted(),
        )
    }
    var answers by remember { mutableStateOf(List(CHECKED_WORDS) { "" }) }
    val matches = positions.zip(answers).all { (i, answer) -> answer.trim().equals(list[i], ignoreCase = true) }
    val typed = answers.all { it.isNotBlank() }
    SyncStepHeader(icon = Icons.Outlined.Spellcheck, title = stringResource(R.string.linked_check_title))
    positions.forEachIndexed { slot, i ->
        OutlinedTextField(
            value = answers[slot],
            onValueChange = { value -> answers = answers.toMutableList().also { it[slot] = value } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.linked_check_word, i + 1)) },
            singleLine = true,
        )
    }
    if (typed && !matches) {
        Text(
            stringResource(R.string.linked_check_mismatch),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    SyncActionRow(
        confirmLabel = stringResource(R.string.linked_confirm),
        onConfirm = onSaved,
        confirmEnabled = !busy && typed && matches,
        dismissLabel = stringResource(R.string.linked_back),
        onDismiss = { checking = false },
    )
}

/** Swaps in new recovery words after asking, for words that were lost or seen. */
@Composable
internal fun ReplaceRecoveryWords(
    busy: Boolean,
    onReplace: () -> Unit,
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    SyncRowGroup(count = 1) { _, shape ->
        FlowNavRow(
            title = stringResource(R.string.linked_replace_words),
            onClick = { confirm = true },
            supportingText = stringResource(R.string.linked_replace_words_hint),
            leadingIcon = Icons.Outlined.Key,
            enabled = !busy,
            shape = shape,
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.linked_replace_words_title)) },
            text = { Text(stringResource(R.string.linked_replace_words_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onReplace()
                }) { Text(stringResource(R.string.linked_replace)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.linked_cancel)) } },
        )
    }
}
