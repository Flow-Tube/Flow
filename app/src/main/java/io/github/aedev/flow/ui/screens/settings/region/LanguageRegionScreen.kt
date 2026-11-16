package io.github.aedev.flow.ui.screens.settings.region

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.GTranslate
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.CONTENT_LANGUAGE_FOLLOW_APP
import io.github.aedev.flow.data.translation.TranslationServerType
import io.github.aedev.flow.data.translation.TranslationSettings
import io.github.aedev.flow.ui.components.settings.SettingsPage
import io.github.aedev.flow.ui.components.settings.nav
import io.github.aedev.flow.ui.components.settings.switch
import io.github.aedev.flow.ui.components.shared.FlowChoice
import io.github.aedev.flow.ui.components.shared.FlowChoiceDialog
import io.github.aedev.flow.ui.screens.settings.index.LanguageRegionIndex
import io.github.aedev.flow.utils.AppLanguageManager
import io.github.aedev.flow.utils.RegionCatalog
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private enum class LanguageRegionDialog {
    APP_LANGUAGE,
    CONTENT_LANGUAGE,
    CONTENT_COUNTRY,
    TRANSLATE_WITH,
    TRANSLATION_SERVER,
    TRANSLATE_TO,
}

/** The UI language, and the language and country YouTube serves content for. */
@Composable
internal fun LanguageRegionScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: LanguageRegionViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val contentLanguage by viewModel.contentLanguage.collectAsStateWithLifecycle()
    val contentCountry by viewModel.contentCountry.collectAsStateWithLifecycle()
    val translation by viewModel.translation.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<LanguageRegionDialog?>(null) }

    val systemDefault = stringResource(R.string.settings_language_system_default)
    val followApp = stringResource(R.string.music_content_language_follow_app)
    val appLanguages = remember { AppLanguageManager.getSupportedLanguages() }
    val normalizedAppLanguage = AppLanguageManager.normalizeLanguageTag(appLanguage)
    val appLanguageLabel =
        if (normalizedAppLanguage == AppLanguageManager.SYSTEM_DEFAULT) {
            systemDefault
        } else {
            appLanguages.firstOrNull { it.tag == normalizedAppLanguage }?.nativeName
                ?: AppLanguageManager.getLanguageLabel(normalizedAppLanguage)
        }
    val contentLanguageLabel =
        viewModel.contentLanguages.firstOrNull { it.tag == contentLanguage }?.nativeName ?: followApp
    val countryLabel = remember(contentCountry) { RegionCatalog.displayName(contentCountry) }

    SettingsPage(
        title = stringResource(R.string.settings_language_region_title),
        onBack = onBack,
        highlight = highlight,
    ) {
        group(key = "language_region.languages") {
            nav(
                LanguageRegionIndex.appLanguage,
                value = appLanguageLabel,
                icon = Icons.Outlined.Language,
                showChevron = false,
                onClick = { dialog = LanguageRegionDialog.APP_LANGUAGE },
            )
            nav(
                LanguageRegionIndex.contentLanguage,
                value = contentLanguageLabel,
                icon = Icons.Outlined.Translate,
                showChevron = false,
                onClick = { dialog = LanguageRegionDialog.CONTENT_LANGUAGE },
            )
            nav(
                LanguageRegionIndex.contentCountry,
                value = countryLabel,
                icon = Icons.Outlined.Public,
                showChevron = false,
                onClick = { dialog = LanguageRegionDialog.CONTENT_COUNTRY },
            )
            switch(
                LanguageRegionIndex.originalTitles,
                state = viewModel.originalTitles,
                onCheckedChange = viewModel::setOriginalTitles,
                icon = Icons.Outlined.Title,
            )
        }
        group(key = "language_region.translation", header = R.string.settings_translation_header) {
            nav(
                LanguageRegionIndex.translateWith,
                valueText = {
                    stringResource(
                        if (translation.server !=
                            null
                        ) {
                            R.string.translate_with_server
                        } else {
                            R.string.translate_with_app
                        },
                    )
                },
                icon = Icons.Outlined.GTranslate,
                showChevron = false,
                onClick = { dialog = LanguageRegionDialog.TRANSLATE_WITH },
            )
            nav(
                LanguageRegionIndex.translationServer,
                valueText = { translation.serverUrl?.let { serverLabel(translation) } },
                icon = Icons.Outlined.Dns,
                showChevron = false,
                onClick = { dialog = LanguageRegionDialog.TRANSLATION_SERVER },
            )
            nav(
                LanguageRegionIndex.translateTo,
                valueText = {
                    viewModel.contentLanguages.firstOrNull { it.tag == translation.target }?.nativeName
                        ?: stringResource(R.string.translate_to_content_language)
                },
                icon = Icons.Outlined.Language,
                enabled = translation.server != null,
                showChevron = false,
                onClick = { dialog = LanguageRegionDialog.TRANSLATE_TO },
            )
        }
    }

    when (dialog) {
        LanguageRegionDialog.APP_LANGUAGE -> {
            FlowChoiceDialog(
                title = stringResource(R.string.settings_language_dialog_title),
                options =
                    listOf(
                        FlowChoice(
                            AppLanguageManager.SYSTEM_DEFAULT,
                            systemDefault,
                            stringResource(R.string.settings_item_app_language_subtitle),
                        ),
                    ) +
                        appLanguages.map { option ->
                            FlowChoice(option.tag, option.nativeName, option.localizedName.takeIf { it != option.nativeName })
                        },
                selected = normalizedAppLanguage,
                onSelect = { tag ->
                    viewModel.setAppLanguage(tag).invokeOnCompletion {
                        if (!AppLanguageManager.systemStoresLanguage) AppLanguageManager.activityContext(context)?.recreate()
                    }
                },
                onDismiss = { dialog = null },
            )
        }

        LanguageRegionDialog.CONTENT_LANGUAGE -> {
            FlowChoiceDialog(
                title = stringResource(R.string.music_content_language_dialog_title),
                options =
                    listOf(FlowChoice(CONTENT_LANGUAGE_FOLLOW_APP, followApp)) +
                        viewModel.contentLanguages.map { FlowChoice(it.tag, it.nativeName, it.localizedName) },
                selected = contentLanguage,
                onSelect = viewModel::setContentLanguage,
                onDismiss = { dialog = null },
            )
        }

        LanguageRegionDialog.CONTENT_COUNTRY -> {
            FlowChoiceDialog(
                title = stringResource(R.string.settings_region_dialog_title),
                options = remember { RegionCatalog.sorted().map { (code, name) -> FlowChoice(code, name) } },
                selected = contentCountry,
                onSelect = viewModel::setContentCountry,
                onDismiss = { dialog = null },
            )
        }

        LanguageRegionDialog.TRANSLATE_WITH -> {
            FlowChoiceDialog(
                title = stringResource(R.string.settings_translate_with),
                options =
                    listOf(
                        FlowChoice(false, stringResource(R.string.translate_with_app), stringResource(R.string.translate_with_app_summary)),
                        FlowChoice(
                            true,
                            stringResource(R.string.translate_with_server),
                            stringResource(R.string.translate_with_server_summary),
                        ),
                    ),
                selected = translation.server != null,
                onSelect = { useServer ->
                    if (useServer && translation.serverUrl == null) {
                        dialog = LanguageRegionDialog.TRANSLATION_SERVER
                    } else {
                        viewModel.setTranslateWithServer(useServer)
                    }
                },
                onDismiss = { if (dialog == LanguageRegionDialog.TRANSLATE_WITH) dialog = null },
            )
        }

        LanguageRegionDialog.TRANSLATION_SERVER -> {
            TranslationServerDialog(
                initial = translation,
                onSave = viewModel::saveTranslationServer,
                onDismiss = { dialog = null },
            )
        }

        LanguageRegionDialog.TRANSLATE_TO -> {
            FlowChoiceDialog(
                title = stringResource(R.string.settings_translate_to),
                options =
                    listOf(FlowChoice("", stringResource(R.string.translate_to_content_language))) +
                        viewModel.contentLanguages.map { FlowChoice(it.tag, it.nativeName, it.localizedName) },
                selected = translation.target,
                onSelect = viewModel::setTranslationTarget,
                onDismiss = { dialog = null },
            )
        }

        null -> {
            Unit
        }
    }
}

@Composable
private fun serverLabel(settings: TranslationSettings): String {
    val type =
        stringResource(
            if (settings.serverType ==
                TranslationServerType.LINGVA
            ) {
                R.string.translation_server_lingva
            } else {
                R.string.translation_server_libretranslate
            },
        )
    val host = settings.serverUrl?.toHttpUrlOrNull()?.host ?: settings.serverUrl.orEmpty()
    return stringResource(R.string.translation_server_value, type, host)
}
