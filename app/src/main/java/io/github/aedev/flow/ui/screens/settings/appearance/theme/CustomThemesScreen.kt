package io.github.aedev.flow.ui.screens.settings.appearance.theme

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingsDestination
import io.github.aedev.flow.ui.components.settings.SettingsPage
import io.github.aedev.flow.ui.components.settings.SettingsTarget
import io.github.aedev.flow.ui.components.settings.nav
import io.github.aedev.flow.ui.components.settings.notice
import io.github.aedev.flow.ui.components.shared.FlowEmptyState
import io.github.aedev.flow.ui.screens.settings.index.CustomThemeIndex
import io.github.aedev.flow.ui.theme.CustomTheme
import io.github.aedev.flow.ui.theme.ThemeCatalog
import io.github.aedev.flow.ui.theme.ThemeCatalogEntry
import io.github.aedev.flow.ui.theme.ThemeMode
import io.github.aedev.flow.ui.theme.ThemeVariant
import io.github.aedev.flow.ui.theme.toColorScheme
import io.github.aedev.flow.ui.utils.rememberDocumentPickerAvailable

private const val JSON_MIME = "application/json"
private val ImportMimeTypes = arrayOf(JSON_MIME, "text/plain", "application/octet-stream")
private val EmptyStateHeight = 220.dp

private sealed interface CustomThemeAction {
    data object Create : CustomThemeAction

    data class Rename(
        val id: String,
    ) : CustomThemeAction

    data class Duplicate(
        val id: String,
    ) : CustomThemeAction

    data class Delete(
        val id: String,
    ) : CustomThemeAction
}

/**
 * The user's own themes, up to [CustomTheme.MAX_COUNT]: make one from any palette, edit, copy or
 * delete it, and move themes to and from Flow Desktop as JSON files in desktop's own format.
 */
@Composable
internal fun CustomThemesScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    onNavigate: (SettingsTarget) -> Unit,
    viewModel: CustomThemesViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val themes by viewModel.themes.collectAsStateWithLifecycle()
    val inUseId by viewModel.inUseId.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var action by remember { mutableStateOf<CustomThemeAction?>(null) }
    var exporting by remember { mutableStateOf<List<CustomTheme>>(emptyList()) }
    val list = themes.orEmpty()

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::import) }
    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(JSON_MIME)) { uri ->
            if (uri != null && exporting.isNotEmpty()) viewModel.export(uri, exporting)
        }
    val palettes = ThemeCatalog.palettes.filter { it.mode != ThemeMode.MATERIAL_YOU }
    val allFileName = stringResource(R.string.settings_custom_themes_title)
    val canPickFile = rememberDocumentPickerAvailable()

    fun exportThemes(themes: List<CustomTheme>) {
        exporting = themes
        exporter.launch("${themes.singleOrNull()?.name ?: allFileName}.json")
    }

    CustomThemeMessageEffect(message, snackbarHostState, viewModel::consumeMessage)

    SettingsPage(
        title = stringResource(R.string.settings_custom_themes_title),
        onBack = onBack,
        highlight = highlight,
        snackbarHostState = snackbarHostState,
        actions = {
            if (list.isNotEmpty() && canPickFile) {
                IconButton(onClick = { exportThemes(list) }) {
                    Icon(Icons.Outlined.SaveAlt, contentDescription = stringResource(R.string.settings_custom_theme_export_all))
                }
            }
        },
    ) {
        group(key = "custom_themes.actions") {
            nav(
                CustomThemeIndex.create,
                icon = Icons.Outlined.Add,
                showChevron = false,
                enabled = list.size < CustomTheme.MAX_COUNT,
                onClick = { action = CustomThemeAction.Create },
            )
            if (canPickFile) {
                nav(
                    CustomThemeIndex.import,
                    icon = Icons.Outlined.FileOpen,
                    showChevron = false,
                    enabled = list.size < CustomTheme.MAX_COUNT,
                    onClick = { importer.launch(ImportMimeTypes) },
                )
            }
        }
        if (themes != null && list.isEmpty()) {
            item("custom_themes.empty") {
                Box(Modifier.fillMaxWidth().height(EmptyStateHeight)) {
                    FlowEmptyState(
                        title = stringResource(R.string.settings_custom_theme_empty),
                        subtitle = stringResource(R.string.settings_custom_theme_empty_body),
                        icon = Icons.Outlined.Palette,
                    )
                }
            }
        } else if (list.isNotEmpty()) {
            group(key = "custom_themes.list", header = R.string.settings_custom_themes_title) {
                list.forEach { theme ->
                    row("custom_themes.${theme.id}") { shape ->
                        CustomThemeRow(
                            theme = theme,
                            inUse = theme.id == inUseId,
                            shape = shape,
                            onEdit = { onNavigate(SettingsTarget(SettingsDestination.CUSTOM_THEME_EDIT, tab = theme.id)) },
                            onUse = { viewModel.use(theme.id) },
                            onRename = { action = CustomThemeAction.Rename(theme.id) },
                            onDuplicate = { action = CustomThemeAction.Duplicate(theme.id) },
                            onExport = { exportThemes(listOf(theme)) }.takeIf { canPickFile },
                            onShare = {
                                val send =
                                    Intent(Intent.ACTION_SEND)
                                        .setType(JSON_MIME)
                                        .putExtra(Intent.EXTRA_SUBJECT, theme.name)
                                        .putExtra(Intent.EXTRA_TEXT, viewModel.shareText(theme))
                                context.startActivity(Intent.createChooser(send, theme.name))
                            },
                            onDelete = { action = CustomThemeAction.Delete(theme.id) },
                        )
                    }
                }
            }
            notice("custom_themes.count", text = {
                stringResource(R.string.settings_custom_theme_count, list.size, CustomTheme.MAX_COUNT)
            })
        }
    }

    CustomThemeActionDialogs(
        action = action,
        themes = list,
        palettes = palettes,
        viewModel = viewModel,
        onCreated = { id -> onNavigate(SettingsTarget(SettingsDestination.CUSTOM_THEME_EDIT, tab = id)) },
        onDismiss = { action = null },
    )
}

@Composable
private fun CustomThemeActionDialogs(
    action: CustomThemeAction?,
    themes: List<CustomTheme>,
    palettes: List<ThemeCatalogEntry>,
    viewModel: CustomThemesViewModel,
    onCreated: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (action) {
        CustomThemeAction.Create -> {
            ThemeNameDialog(
                title = stringResource(R.string.settings_custom_theme_create),
                initialName = stringResource(R.string.settings_custom_theme_default_name),
                confirmLabel = stringResource(R.string.settings_custom_theme_create),
                palettes = palettes,
                onConfirm = { name, base ->
                    onDismiss()
                    viewModel.create(name, base)?.let(onCreated)
                },
                onDismiss = onDismiss,
            )
        }

        is CustomThemeAction.Rename -> {
            val theme = themes.firstOrNull { it.id == action.id } ?: return
            ThemeNameDialog(
                title = stringResource(R.string.settings_custom_theme_rename),
                initialName = theme.name,
                confirmLabel = stringResource(R.string.settings_custom_theme_rename),
                onConfirm = { name, _ ->
                    viewModel.rename(theme, name)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        is CustomThemeAction.Duplicate -> {
            val theme = themes.firstOrNull { it.id == action.id } ?: return
            ThemeNameDialog(
                title = stringResource(R.string.settings_custom_theme_duplicate),
                initialName = stringResource(R.string.settings_custom_theme_copy_name, theme.name),
                confirmLabel = stringResource(R.string.settings_custom_theme_duplicate),
                onConfirm = { name, _ ->
                    viewModel.duplicate(theme, name)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        is CustomThemeAction.Delete -> {
            val theme = themes.firstOrNull { it.id == action.id } ?: return
            DeleteThemeDialog(name = theme.name, onConfirm = { viewModel.delete(theme.id) }, onDismiss = onDismiss)
        }

        null -> {
            Unit
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CustomThemeRow(
    theme: CustomTheme,
    inUse: Boolean,
    shape: Shape,
    onEdit: () -> Unit,
    onUse: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: (() -> Unit)?,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val variant = if (MaterialTheme.colorScheme.background.luminance() < HALF_LUMINANCE) ThemeVariant.DARK else ThemeVariant.LIGHT
    val swatch = remember(theme, variant) { theme.colorsFor(variant).toColorScheme(variant).toSwatch() }
    var menuOpen by remember { mutableStateOf(false) }

    SegmentedListItem(
        onClick = onEdit,
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        leadingContent = { ThemeSwatchTile(swatch) },
        supportingContent = {
            Text(stringResource(if (inUse) R.string.settings_custom_theme_in_use else R.string.settings_custom_theme_card_description))
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.settings_custom_theme_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    listOfNotNull(
                        R.string.settings_custom_theme_use to onUse,
                        R.string.settings_custom_theme_rename to onRename,
                        R.string.settings_custom_theme_duplicate to onDuplicate,
                        onExport?.let { R.string.settings_custom_theme_export to it },
                        R.string.settings_custom_theme_share to onShare,
                        R.string.settings_custom_theme_delete to onDelete,
                    ).forEach { (label, onClick) ->
                        if (label == R.string.settings_custom_theme_use && inUse) return@forEach
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            onClick = {
                                menuOpen = false
                                onClick()
                            },
                        )
                    }
                }
            }
        },
    ) {
        Text(theme.name)
    }
}

private const val HALF_LUMINANCE = 0.5f

/** Says once what an import or export did. */
@Composable
internal fun CustomThemeMessageEffect(
    message: CustomThemeMessage?,
    snackbarHostState: SnackbarHostState,
    onConsumed: () -> Unit,
) {
    val text =
        when (message) {
            is CustomThemeMessage.Imported -> pluralStringResource(R.plurals.settings_custom_theme_imported, message.count, message.count)
            CustomThemeMessage.ImportFailed -> stringResource(R.string.settings_custom_theme_import_failed)
            CustomThemeMessage.LimitReached -> stringResource(R.string.settings_custom_theme_limit, CustomTheme.MAX_COUNT)
            CustomThemeMessage.Exported -> stringResource(R.string.settings_custom_theme_exported)
            CustomThemeMessage.ExportFailed -> stringResource(R.string.settings_custom_theme_export_failed)
            null -> null
        }
    LaunchedEffect(message) {
        if (text == null) return@LaunchedEffect
        try {
            snackbarHostState.showSnackbar(text)
        } finally {
            onConsumed()
        }
    }
}
