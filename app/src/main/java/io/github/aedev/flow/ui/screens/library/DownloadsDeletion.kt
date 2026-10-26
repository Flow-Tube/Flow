package io.github.aedev.flow.ui.screens.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.FlowAlertDialog
import kotlinx.coroutines.flow.collectLatest

/**
 * The Undo snackbar for each delete the Downloads screen makes. The files go when it closes without
 * Undo, or when the screen leaves; a newer delete replaces it after the ViewModel commits the older one.
 */
@Composable
internal fun DownloadsUndoEffect(
    viewModel: DownloadsViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.deleted.collectLatest { count ->
            snackbarHostState.currentSnackbarData?.dismiss()
            val result =
                snackbarHostState.showSnackbar(
                    message =
                        if (count == 1) {
                            context.getString(R.string.download_deleted)
                        } else {
                            context.resources.getQuantityString(R.plurals.downloads_deleted, count, count)
                        },
                    actionLabel = context.getString(R.string.undo),
                    duration = SnackbarDuration.Long,
                )
            if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete() else viewModel.commitDeletes()
        }
    }
    DisposableEffect(viewModel) { onDispose { viewModel.commitDeletes() } }
}

@Composable
internal fun DeleteDownloadsDialog(
    deletion: PendingDeletion,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_download_dialog_title)) },
        text = {
            Text(
                if (deletion.title != null) {
                    stringResource(R.string.delete_download_dialog_text, deletion.title)
                } else {
                    pluralStringResource(R.plurals.delete_downloads_dialog_text, deletion.ids.size, deletion.ids.size)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** What a confirmed delete removes: one download named by [title], or a selection; a queued one is cancelled with no Undo. */
internal data class PendingDeletion(
    val ids: Set<String>,
    val title: String?,
    val undoable: Boolean = true,
)
