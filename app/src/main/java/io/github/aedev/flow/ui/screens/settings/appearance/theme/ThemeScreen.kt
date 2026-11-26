package io.github.aedev.flow.ui.screens.settings.appearance.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingsDestination
import io.github.aedev.flow.ui.components.settings.SettingsPage
import io.github.aedev.flow.ui.components.settings.SettingsTarget
import io.github.aedev.flow.ui.components.settings.nav
import io.github.aedev.flow.ui.components.settings.switch
import io.github.aedev.flow.ui.components.settings.toggleGroup
import io.github.aedev.flow.ui.components.shared.FlowAlertDialog
import io.github.aedev.flow.ui.components.shared.FlowToggleOption
import io.github.aedev.flow.ui.screens.settings.appearance.themeVariantLabel
import io.github.aedev.flow.ui.screens.settings.index.DestinationIndex
import io.github.aedev.flow.ui.screens.settings.index.ThemeIndex
import io.github.aedev.flow.ui.theme.CustomTheme
import io.github.aedev.flow.ui.theme.ThemeCatalog
import io.github.aedev.flow.ui.theme.ThemeCatalogEntry
import io.github.aedev.flow.ui.theme.ThemeMode
import io.github.aedev.flow.ui.theme.ThemeVariant

private val PaletteDialogMaxHeight = 440.dp
private val PaletteDialogSpacing = 8.dp

private enum class SystemSlot { LIGHT, DARK }

/**
 * The theme picker. With Follow system on, Flow switches between a light-mode and a dark-mode
 * theme with the device; with it off, one theme is shown in the chosen style. Custom themes sit
 * beside the built-in palettes and are managed on their own page.
 */
@Composable
internal fun ThemeScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    onNavigate: (SettingsTarget) -> Unit,
    viewModel: ThemeViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val swatches by viewModel.swatches.collectAsStateWithLifecycle()
    val isSystemDark = isSystemInDarkTheme()
    var slotDialog by rememberSaveable { mutableStateOf<SystemSlot?>(null) }

    val styleOptions =
        ThemeVariant.entries.map { FlowToggleOption(it, stringResource(themeVariantLabel(it))) }
    val darkStyleOptions = styleOptions.filter { it.value != ThemeVariant.LIGHT }
    val activeCustomName = settings.customThemes.firstOrNull { it.id == settings.activeCustomId }?.name
    val lightSlotName = themeName(settings.systemLightMode, activeCustomName)
    val darkSlotName = themeName(settings.systemDarkMode, activeCustomName)
    val paletteItems = paletteCards(viewModel.palettes, swatches[settings.variant].orEmpty(), settings.mode, viewModel::setTheme)
    val customItems =
        customCards(
            themes = settings.customThemes,
            swatches = swatches[settings.variant].orEmpty(),
            selectedId = settings.activeCustomId.takeIf { settings.mode == ThemeMode.CUSTOM },
            onSelect = viewModel::useCustomTheme,
        )

    SettingsPage(
        title = stringResource(R.string.settings_item_theme),
        onBack = onBack,
        highlight = highlight,
    ) {
        group(key = "theme.mode") {
            switch(
                ThemeIndex.followSystem,
                checked = settings.followsSystem,
                onCheckedChange = { viewModel.setFollowSystem(it, isSystemDark) },
            )
            if (settings.followsSystem) {
                nav(
                    ThemeIndex.lightModeTheme,
                    value = lightSlotName,
                    icon = Icons.Outlined.LightMode,
                    onClick = { slotDialog = SystemSlot.LIGHT },
                )
                nav(
                    ThemeIndex.darkModeTheme,
                    value = darkSlotName,
                    icon = Icons.Outlined.DarkMode,
                    onClick = { slotDialog = SystemSlot.DARK },
                )
                toggleGroup(ThemeIndex.darkModeStyle, darkStyleOptions, settings.systemDarkVariant, viewModel::setSystemDarkVariant)
            } else {
                toggleGroup(ThemeIndex.style, styleOptions, settings.variant, viewModel::setVariant)
            }
        }
        if (!settings.followsSystem && customItems.isNotEmpty()) {
            header(key = "theme.custom", text = R.string.settings_custom_themes_title)
            item("theme.custom.grid") { ThemeCardGrid(customItems) }
        }
        group(key = "theme.manage") {
            nav(
                DestinationIndex.entry(SettingsDestination.CUSTOM_THEME),
                icon = Icons.Outlined.Palette,
                onClick = { onNavigate(SettingsTarget(SettingsDestination.CUSTOM_THEME)) },
            )
        }
        if (!settings.followsSystem) {
            header(key = ThemeIndex.palettes.key, text = R.string.settings_theme_palettes)
            item("theme.palettes.grid") { ThemeCardGrid(paletteItems) }
        }
    }

    slotDialog?.let { slot ->
        val lightSlot = slot == SystemSlot.LIGHT
        val slotVariant = if (lightSlot) ThemeVariant.LIGHT else settings.systemDarkVariant
        val slotMode = if (lightSlot) settings.systemLightMode else settings.systemDarkMode
        ThemePaletteDialog(
            title = stringResource(if (lightSlot) R.string.appearance_system_light_theme else R.string.appearance_system_dark_theme),
            items =
                paletteCards(viewModel.palettes, swatches[slotVariant].orEmpty(), slotMode) { mode ->
                    if (lightSlot) viewModel.setSystemLightTheme(mode) else viewModel.setSystemDarkTheme(mode)
                    slotDialog = null
                } +
                    customCards(
                        themes = settings.customThemes,
                        swatches = swatches[slotVariant].orEmpty(),
                        selectedId = settings.activeCustomId.takeIf { slotMode == ThemeMode.CUSTOM },
                    ) { id ->
                        viewModel.setSystemSlotCustom(lightSlot, id)
                        slotDialog = null
                    },
            onDismiss = { slotDialog = null },
        )
    }
}

@Composable
private fun themeName(
    mode: ThemeMode,
    customName: String?,
): String = if (mode == ThemeMode.CUSTOM && customName != null) customName else stringResource(ThemeCatalog.nameRes(mode))

@Composable
private fun paletteCards(
    palettes: List<ThemeCatalogEntry>,
    swatches: Map<String, ThemeSwatch>,
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
): List<ThemeCardItem> =
    palettes.map { entry ->
        ThemeCardItem(
            key = swatchKey(entry.mode),
            name = stringResource(entry.nameRes),
            description = stringResource(entry.descriptionRes),
            swatch = swatches[swatchKey(entry.mode)],
            selected = entry.mode == selected,
            onClick = { onSelect(entry.mode) },
        )
    }

@Composable
private fun customCards(
    themes: List<CustomTheme>,
    swatches: Map<String, ThemeSwatch>,
    selectedId: String?,
    onSelect: (String) -> Unit,
): List<ThemeCardItem> {
    val description = stringResource(R.string.settings_custom_theme_card_description)
    return themes.map { theme ->
        ThemeCardItem(
            key = swatchKey(theme),
            name = theme.name,
            description = description,
            swatch = swatches[swatchKey(theme)],
            selected = theme.id == selectedId,
            onClick = { onSelect(theme.id) },
        )
    }
}

/** Picks the theme for one Follow-system slot, drawn with the style that slot will use. */
@Composable
private fun ThemePaletteDialog(
    title: String,
    items: List<ThemeCardItem>,
    onDismiss: () -> Unit,
) {
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = PaletteDialogMaxHeight),
                verticalArrangement = Arrangement.spacedBy(PaletteDialogSpacing),
            ) {
                items(items, key = { it.key }) { item ->
                    ThemeCard(
                        name = item.name,
                        description = item.description,
                        swatch = item.swatch,
                        selected = item.selected,
                        onClick = item.onClick,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
