package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination

internal object LanguageRegionIndex {
    private val page = SettingsDestination.LANGUAGE_REGION

    val appLanguage =
        SettingEntry(
            key = "language_region.app_language",
            title = R.string.settings_item_app_language,
            keywords = R.string.settings_keywords_language,
            destination = page,
        )
    val contentLanguage =
        SettingEntry(
            key = "language_region.content_language",
            title = R.string.music_content_language_title,
            summary = R.string.settings_content_language_summary,
            keywords = R.string.settings_keywords_language,
            destination = page,
        )
    val contentCountry =
        SettingEntry(
            key = "language_region.content_country",
            title = R.string.music_content_country_title,
            summary = R.string.settings_content_country_summary,
            keywords = R.string.settings_keywords_region,
            destination = page,
        )

    val originalTitles =
        SettingEntry(
            key = "language_region.original_titles",
            title = R.string.settings_original_titles,
            summary = R.string.settings_original_titles_summary,
            keywords = R.string.settings_keywords_original_titles,
            destination = page,
        )

    val translateWith =
        SettingEntry(
            key = "language_region.translate_with",
            title = R.string.settings_translate_with,
            keywords = R.string.settings_keywords_translation,
            destination = page,
        )
    val translationServer =
        SettingEntry(
            key = "language_region.translation_server",
            title = R.string.translation_server_title,
            summary = R.string.settings_translation_server_summary,
            keywords = R.string.settings_keywords_translation,
            destination = page,
        )
    val translateTo =
        SettingEntry(
            key = "language_region.translate_to",
            title = R.string.settings_translate_to,
            keywords = R.string.settings_keywords_translation,
            destination = page,
        )

    val all = listOf(appLanguage, contentLanguage, contentCountry, originalTitles, translateWith, translationServer, translateTo)
}
