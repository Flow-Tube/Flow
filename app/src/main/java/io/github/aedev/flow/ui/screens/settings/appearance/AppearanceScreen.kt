package io.github.aedev.flow.ui.screens.settings.appearance

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.AppShortcut
import androidx.compose.material.icons.outlined.FiberNew
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TextRotationNone
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.AppFontSelection
import io.github.aedev.flow.data.local.HomeFeedColumns
import io.github.aedev.flow.data.local.HomeViewMode
import io.github.aedev.flow.platform.AppUiMode
import io.github.aedev.flow.ui.components.settings.SettingsDestination
import io.github.aedev.flow.ui.components.settings.SettingsPage
import io.github.aedev.flow.ui.components.settings.SettingsTarget
import io.github.aedev.flow.ui.components.settings.choice
import io.github.aedev.flow.ui.components.settings.nav
import io.github.aedev.flow.ui.components.settings.switch
import io.github.aedev.flow.ui.components.settings.toggleGroup
import io.github.aedev.flow.ui.components.shared.FlowChoice
import io.github.aedev.flow.ui.components.shared.FlowChoiceDialog
import io.github.aedev.flow.ui.components.shared.FlowToggleOption
import io.github.aedev.flow.ui.screens.settings.index.AppearanceIndex
import io.github.aedev.flow.ui.screens.settings.index.DestinationIndex
import io.github.aedev.flow.ui.screens.settings.index.NewSettingsPolicy
import io.github.aedev.flow.ui.theme.AppFont
import io.github.aedev.flow.ui.theme.GridItemSize
import io.github.aedev.flow.ui.theme.ThemeCatalog
import io.github.aedev.flow.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/** Everything about how Flow looks: theme, icon, layout, and the sub-pages for finer control. */
@Composable
internal fun AppearanceScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    onNavigate: (SettingsTarget) -> Unit,
    viewModel: AppearanceViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val themeVariant by viewModel.themeVariant.collectAsStateWithLifecycle()
    val customThemeName by viewModel.customThemeName.collectAsStateWithLifecycle()
    val interfaceMode by viewModel.interfaceMode.collectAsStateWithLifecycle()
    val font by viewModel.font.collectAsStateWithLifecycle()
    val appIcon by viewModel.appIcon.collectAsStateWithLifecycle()
    val homeViewMode by viewModel.homeViewMode.collectAsStateWithLifecycle()
    val homeColumns by viewModel.homeColumns.collectAsStateWithLifecycle()
    val gridItemSize by viewModel.gridItemSize.collectAsStateWithLifecycle()
    val libraryPreviews by viewModel.libraryPreviews.collectAsStateWithLifecycle()
    val separatePlaylists by viewModel.separatePlaylists.collectAsStateWithLifecycle()
    val appLogo by viewModel.appLogo.collectAsStateWithLifecycle()
    val groupBadges by viewModel.groupBadges.collectAsStateWithLifecycle()
    val cardLikeButtons by viewModel.cardLikeButtons.collectAsStateWithLifecycle()
    val cardMarkWatched by viewModel.cardMarkWatched.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val iconAppliedMessage = stringResource(R.string.app_icon_apply_toast)
    var showIconDialog by rememberSaveable { mutableStateOf(false) }
    var showInterfaceDialog by rememberSaveable { mutableStateOf(false) }
    var showNewSettingsDialog by rememberSaveable { mutableStateOf(false) }
    val highlightNewSettings by viewModel.newSettingsHighlight.collectAsStateWithLifecycle()

    val themeSummary =
        if (themeMode == ThemeMode.SYSTEM) {
            stringResource(R.string.theme_name_system_default)
        } else {
            stringResource(
                R.string.settings_value_pair,
                customThemeName.takeIf { themeMode == ThemeMode.CUSTOM } ?: stringResource(ThemeCatalog.nameRes(themeMode)),
                stringResource(themeVariantLabel(themeVariant)),
            )
        }
    val iconSummary = stringResource(appIconOption(appIcon).nameRes)
    val fontSummary = fontLabel(font)
    val interfaceSummary = stringResource(interfaceModeLabel(interfaceMode))
    val viewModeOptions =
        listOf(
            FlowToggleOption(HomeViewMode.GRID, stringResource(R.string.content_settings_layout_grid), Icons.Outlined.GridView),
            FlowToggleOption(
                HomeViewMode.LIST,
                stringResource(R.string.content_settings_layout_list),
                Icons.AutoMirrored.Outlined.ViewList,
            ),
        )
    val columnOptions =
        HomeFeedColumns.entries.map { columns ->
            FlowToggleOption(
                columns,
                columns.fixedCount?.toString() ?: stringResource(R.string.content_settings_home_columns_auto),
            )
        }
    val artworkOptions =
        listOf(
            FlowToggleOption(GridItemSize.BIG, stringResource(R.string.content_settings_grid_big_title)),
            FlowToggleOption(GridItemSize.SMALL, stringResource(R.string.content_settings_grid_small_title)),
        )

    SettingsPage(
        title = stringResource(R.string.appearance_title),
        onBack = onBack,
        highlight = highlight,
        snackbarHostState = snackbarHostState,
    ) {
        group(key = "appearance.style", header = R.string.settings_section_style) {
            nav(
                DestinationIndex.entry(SettingsDestination.THEME),
                value = themeSummary,
                icon = Icons.Outlined.Palette,
                onClick = { onNavigate(SettingsTarget(SettingsDestination.THEME)) },
            )
            nav(
                DestinationIndex.entry(SettingsDestination.FONT),
                value = fontSummary,
                icon = Icons.Outlined.TextFields,
                onClick = { onNavigate(SettingsTarget(SettingsDestination.FONT)) },
            )
            nav(
                AppearanceIndex.appIcon,
                value = iconSummary,
                icon = Icons.Outlined.AppShortcut,
                showChevron = false,
                onClick = { showIconDialog = true },
            )
            nav(
                AppearanceIndex.interfaceMode,
                value = interfaceSummary,
                icon = Icons.Outlined.Tv,
                showChevron = false,
                onClick = { showInterfaceDialog = true },
            )
            switch(
                AppearanceIndex.skeletonGradient,
                viewModel.skeletonGradient,
                viewModel::setSkeletonGradient,
                icon = Icons.Outlined.Gradient,
            )
            switch(
                AppearanceIndex.marquee,
                viewModel.marqueeEnabled,
                viewModel::setMarqueeEnabled,
                icon = Icons.Outlined.TextRotationNone,
            )
            switch(AppearanceIndex.haptics, viewModel.haptics, viewModel::setHaptics, icon = Icons.Outlined.Vibration)
            switch(
                AppearanceIndex.highlightNewSettings,
                viewModel.newSettingsHighlight,
                viewModel::setNewSettingsHighlight,
                icon = Icons.Outlined.FiberNew,
            )
            if (highlightNewSettings) {
                choice(AppearanceIndex.highlightNewSettingsDays, onClick = { showNewSettingsDialog = true }, icon = Icons.Outlined.Timer) {
                    val days by viewModel.newSettingsDays.collectAsStateWithLifecycle()
                    newSettingsDaysLabel(days)
                }
            }
        }
        group(key = "appearance.layout", header = R.string.settings_section_layout) {
            toggleGroup(AppearanceIndex.homeViewMode, viewModeOptions, homeViewMode, viewModel::setHomeViewMode)
            if (homeViewMode == HomeViewMode.GRID) {
                toggleGroup(AppearanceIndex.homeColumns, columnOptions, homeColumns, viewModel::setHomeColumns)
            }
            toggleGroup(AppearanceIndex.musicArtworkSize, artworkOptions, gridItemSize, viewModel::setGridItemSize)
            switch(AppearanceIndex.libraryPreviews, libraryPreviews, viewModel::setLibraryPreviews)
            switch(AppearanceIndex.separatePlaylists, separatePlaylists, viewModel::setSeparatePlaylists)
            switch(AppearanceIndex.appLogo, appLogo, viewModel::setAppLogo)
            switch(AppearanceIndex.groupBadges, groupBadges, viewModel::setGroupBadges)
        }
        group(key = "appearance.cards", header = R.string.settings_section_video_cards) {
            switch(AppearanceIndex.cardLikeButtons, cardLikeButtons, viewModel::setCardLikeButtons)
            switch(AppearanceIndex.cardWatchProgress, viewModel.cardWatchProgress, viewModel::setCardWatchProgress)
            switch(AppearanceIndex.cardMarkWatched, cardMarkWatched, viewModel::setCardMarkWatched)
        }
        group(key = "appearance.more", header = R.string.settings_section_more) {
            listOf(
                SettingsDestination.NAVIGATION_BAR,
                SettingsDestination.DATE_TIME,
                SettingsDestination.PLAYER_APPEARANCE,
            ).forEach { destination ->
                nav(DestinationIndex.entry(destination), onClick = { onNavigate(SettingsTarget(destination)) })
            }
        }
    }

    if (showIconDialog) {
        AppIconDialog(
            selected = appIcon,
            onApply = { suffix ->
                viewModel.setAppIcon(suffix)
                scope.launch { snackbarHostState.showSnackbar(iconAppliedMessage) }
            },
            onDismiss = { showIconDialog = false },
        )
    }

    if (showNewSettingsDialog) {
        val days by viewModel.newSettingsDays.collectAsStateWithLifecycle()
        FlowChoiceDialog(
            title = stringResource(R.string.settings_highlight_new_days_title),
            options = NewSettingsPolicy.DAY_OPTIONS.map { FlowChoice(it, newSettingsDaysLabel(it)) },
            selected = days,
            onSelect = viewModel::setNewSettingsDays,
            onDismiss = { showNewSettingsDialog = false },
        )
    }

    if (showInterfaceDialog) {
        FlowChoiceDialog(
            title = stringResource(R.string.interface_mode_title),
            options =
                AppUiMode.entries.map { mode ->
                    FlowChoice(mode, stringResource(interfaceModeLabel(mode)), stringResource(interfaceModeSummary(mode)))
                },
            selected = interfaceMode,
            onSelect = viewModel::setInterfaceMode,
            onDismiss = { showInterfaceDialog = false },
        )
    }
}

@Composable
private fun newSettingsDaysLabel(days: Int): String = pluralStringResource(R.plurals.settings_highlight_new_days, days, days)

private fun interfaceModeLabel(mode: AppUiMode): Int =
    when (mode) {
        AppUiMode.AUTOMATIC -> R.string.interface_mode_automatic
        AppUiMode.MOBILE -> R.string.interface_mode_mobile
        AppUiMode.TV -> R.string.interface_mode_tv
    }

private fun interfaceModeSummary(mode: AppUiMode): Int =
    when (mode) {
        AppUiMode.AUTOMATIC -> R.string.interface_mode_automatic_summary
        AppUiMode.MOBILE -> R.string.interface_mode_mobile_summary
        AppUiMode.TV -> R.string.interface_mode_tv_summary
    }

@Composable
private fun fontLabel(font: AppFontSelection): String =
    when (font.font) {
        AppFont.SYSTEM -> stringResource(R.string.font_system_default)
        AppFont.CONDENSED -> stringResource(R.string.font_condensed)
        AppFont.SERIF -> stringResource(R.string.font_serif)
        AppFont.CUSTOM -> font.customName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.font_custom)
    }
