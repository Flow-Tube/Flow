package io.github.aedev.flow.ui.components.videoplayer.sheet

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.transcript.TranscriptCue
import io.github.aedev.flow.data.video.storage.DownloadNaming
import io.github.aedev.flow.utils.copyPlainText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A clip travels through a Binder transaction capped near 1 MB, and the text goes as UTF-16. */
private const val MAX_CLIPBOARD_CHARS = 250_000

/**
 * The header button that takes the whole transcript out of the app: copied with or without
 * timestamps, or saved as a text file named after [title] and [language].
 */
@Composable
internal fun TranscriptExportMenu(
    cues: List<TranscriptCue>,
    chapters: List<TranscriptChapter>,
    title: String,
    language: String?,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<String?>(null) }

    val saveLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val text = pendingSave
            pendingSave = null
            if (uri == null || text == null) return@rememberLauncherForActivityResult
            scope.launch {
                val saved =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val out = context.contentResolver.openOutputStream(uri)
                            out?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                            out != null
                        }.getOrDefault(false)
                    }
                val message = if (saved) R.string.transcript_saved else R.string.transcript_save_failed
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

    fun copy(withTimestamps: Boolean) {
        val text = transcriptText(cues, chapters, withTimestamps)
        if (text.length > MAX_CLIPBOARD_CHARS) {
            Toast.makeText(context, R.string.transcript_too_long_to_copy, Toast.LENGTH_LONG).show()
            return
        }
        scope.launch {
            clipboard.copyPlainText(label = title, text = text)
            // Android 13 and later confirm a copy on their own.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, R.string.transcript_copied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(40.dp)) {
            Icon(imageVector = Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.transcript_actions))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.transcript_copy)) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = {
                    expanded = false
                    copy(withTimestamps = false)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.transcript_copy_with_timestamps)) },
                leadingIcon = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
                onClick = {
                    expanded = false
                    copy(withTimestamps = true)
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.transcript_save_file)) },
                leadingIcon = { Icon(Icons.Outlined.SaveAlt, contentDescription = null) },
                onClick = {
                    expanded = false
                    pendingSave = transcriptText(cues, chapters, withTimestamps = true)
                    saveLauncher.launch(
                        DownloadNaming.fileName(
                            title = listOfNotNull(title, language).filter { it.isNotBlank() }.joinToString(" - "),
                            fallback = "transcript",
                            extension = "txt",
                        ),
                    )
                },
            )
        }
    }
}
