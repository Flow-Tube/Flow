package io.github.aedev.flow.ui.screens.settings.appearance

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.CustomFontStore
import io.github.aedev.flow.data.local.FontImportError
import io.github.aedev.flow.data.local.FontImportResult
import io.github.aedev.flow.ui.components.settings.SettingsPage
import io.github.aedev.flow.ui.components.settings.nav
import io.github.aedev.flow.ui.components.settings.notice
import io.github.aedev.flow.ui.components.settings.option
import io.github.aedev.flow.ui.screens.settings.index.FontIndex
import io.github.aedev.flow.ui.theme.AppFont
import io.github.aedev.flow.ui.utils.rememberDocumentPickerAvailable

private const val BYTES_PER_MB = 1024 * 1024
private const val ANY_FILE = "*/*"
private val PreviewPadding = 20.dp
private val PreviewSpacing = 4.dp

/** The app font: the system font, a device family, or a .ttf or .otf file the user picks. */
@Composable
internal fun FontScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: FontViewModel = hiltViewModel(),
) {
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val importResult by viewModel.importResult.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // Font files have no reliable MIME type across file managers, so every file is offered and the name is checked.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::import) }
    val maxMb = (CustomFontStore.MAX_SIZE_BYTES / BYTES_PER_MB).toInt()
    val customKind = stringResource(R.string.font_custom)
    val customLabel = selection.customName?.takeIf { it.isNotBlank() } ?: customKind
    val builtInLabels = builtInFonts.map { (font, label) -> font to stringResource(label) }
    val fileSummary = stringResource(R.string.font_choose_file_summary, maxMb)
    val canPickFile = rememberDocumentPickerAvailable()

    FontImportMessageEffect(importResult, maxMb, snackbarHostState, viewModel::consumeImportResult)

    SettingsPage(
        title = stringResource(R.string.settings_font_title),
        onBack = onBack,
        highlight = highlight,
        snackbarHostState = snackbarHostState,
    ) {
        item("font.preview") { FontPreviewCard() }
        group(key = FontIndex.font.key, header = R.string.settings_font_title) {
            builtInLabels.forEach { (font, label) ->
                option(
                    "font.${font.storageId}",
                    label,
                    selected = selection.font == font,
                    onClick = { viewModel.select(font) },
                )
            }
            if (selection.customName != null) {
                option(
                    key = "font.custom",
                    label = customLabel,
                    supportingText = customKind,
                    selected = selection.font == AppFont.CUSTOM,
                    onClick = { viewModel.select(AppFont.CUSTOM) },
                )
            }
            if (canPickFile) {
                nav(
                    FontIndex.customFile,
                    value = fileSummary,
                    icon = Icons.Outlined.UploadFile,
                    showChevron = false,
                    onClick = { picker.launch(arrayOf(ANY_FILE)) },
                )
            }
        }
        notice("font.note", text = { stringResource(R.string.font_system_only_note) }, icon = Icons.Outlined.Info)
    }
}

private val builtInFonts =
    listOf(
        AppFont.SYSTEM to R.string.font_system_default,
        AppFont.CONDENSED to R.string.font_condensed,
        AppFont.SERIF to R.string.font_serif,
    )

/** A sample in each type role, drawn with the app's own typography so it changes the moment the font does. */
@Composable
private fun FontPreviewCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(PreviewPadding), verticalArrangement = Arrangement.spacedBy(PreviewSpacing)) {
            Text(stringResource(R.string.font_preview_glyphs), style = MaterialTheme.typography.displayMedium)
            Text(stringResource(R.string.font_preview_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.font_preview_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.font_preview_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Says once what a file pick did. */
@Composable
private fun FontImportMessageEffect(
    result: FontImportResult?,
    maxMb: Int,
    snackbarHostState: SnackbarHostState,
    onConsumed: () -> Unit,
) {
    val text =
        when (result) {
            is FontImportResult.Imported -> {
                stringResource(R.string.font_imported, result.displayName.ifBlank { stringResource(R.string.font_custom) })
            }

            is FontImportResult.Rejected -> {
                when (result.error) {
                    FontImportError.NOT_A_FONT_FILE -> stringResource(R.string.font_import_not_font)
                    FontImportError.TOO_LARGE -> stringResource(R.string.font_import_too_large, maxMb)
                    FontImportError.UNREADABLE -> stringResource(R.string.font_import_unreadable)
                }
            }

            null -> {
                null
            }
        }
    LaunchedEffect(result) {
        if (text == null) return@LaunchedEffect
        try {
            snackbarHostState.showSnackbar(text)
        } finally {
            onConsumed()
        }
    }
}
