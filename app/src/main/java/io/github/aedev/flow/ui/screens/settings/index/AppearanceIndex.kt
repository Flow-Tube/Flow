package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingAvailability
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination

internal object AppearanceIndex {
    private val page = SettingsDestination.APPEARANCE

    val appIcon =
        SettingEntry(
            key = "appearance.app_icon",
            title = R.string.settings_item_app_icon,
            summary = R.string.settings_item_app_icon_subtitle,
            keywords = R.string.settings_keywords_app_icon,
            section = R.string.settings_section_style,
            destination = page,
        )
    val interfaceMode =
        SettingEntry(
            key = "appearance.interface_mode",
            title = R.string.settings_item_interface_mode,
            summary = R.string.settings_item_interface_mode_subtitle,
            keywords = R.string.settings_keywords_interface_mode,
            section = R.string.settings_section_style,
            destination = page,
        )
    val homeViewMode =
        SettingEntry(
            key = "appearance.home_view_mode",
            title = R.string.content_settings_home_layout_title,
            summary = R.string.content_settings_home_layout_subtitle,
            section = R.string.settings_section_layout,
            destination = page,
        )
    val homeColumns =
        SettingEntry(
            key = "appearance.home_columns",
            title = R.string.content_settings_home_columns_title,
            summary = R.string.content_settings_home_columns_subtitle,
            section = R.string.settings_section_layout,
            destination = page,
        )
    val musicArtworkSize =
        SettingEntry(
            key = "appearance.music_artwork_size",
            title = R.string.settings_music_artwork_size_title,
            summary = R.string.settings_music_artwork_size_summary,
            section = R.string.settings_section_layout,
            destination = page,
        )
    val libraryPreviews =
        SettingEntry(
            key = "appearance.library_previews",
            title = R.string.content_settings_library_previews_title,
            summary = R.string.content_settings_library_previews_subtitle,
            section = R.string.settings_section_layout,
            destination = page,
        )
    val separatePlaylists =
        SettingEntry(
            key = "appearance.separate_playlists",
            title = R.string.settings_separate_playlists_title,
            summary = R.string.settings_separate_playlists_summary,
            section = R.string.settings_section_layout,
            destination = page,
        )
    val appLogo =
        SettingEntry(
            key = "appearance.app_logo",
            title = R.string.content_settings_show_app_logo_title,
            summary = R.string.content_settings_show_app_logo_subtitle,
            section = R.string.settings_section_layout,
            destination = page,
        )
    val groupBadges =
        SettingEntry(
            key = "appearance.group_badges",
            title = R.string.content_settings_channel_group_badge_title,
            summary = R.string.content_settings_channel_group_badge_subtitle,
            section = R.string.settings_section_layout,
            destination = page,
        )
    val cardLikeButtons =
        SettingEntry(
            key = "appearance.card_like_buttons",
            title = R.string.content_settings_video_card_actions_title,
            summary = R.string.content_settings_video_card_actions_subtitle,
            section = R.string.settings_section_video_cards,
            destination = page,
        )
    val cardWatchProgress =
        SettingEntry(
            key = "appearance.card_watch_progress",
            title = R.string.content_settings_video_card_progress_title,
            summary = R.string.content_settings_video_card_progress_subtitle,
            section = R.string.settings_section_video_cards,
            destination = page,
        )
    val cardMarkWatched =
        SettingEntry(
            key = "appearance.card_mark_watched",
            title = R.string.content_settings_video_card_mark_watched_title,
            summary = R.string.content_settings_video_card_mark_watched_subtitle,
            section = R.string.settings_section_video_cards,
            destination = page,
        )

    val all =
        listOf(
            appIcon,
            interfaceMode,
            homeViewMode,
            homeColumns,
            musicArtworkSize,
            libraryPreviews,
            separatePlaylists,
            appLogo,
            groupBadges,
            cardLikeButtons,
            cardWatchProgress,
            cardMarkWatched,
        )
}

internal object NavigationBarIndex {
    private val page = SettingsDestination.NAVIGATION_BAR

    private fun tab(
        key: String,
        title: Int,
        summary: Int,
    ) = SettingEntry(
        key = "navigation_bar.$key",
        title = title,
        summary = summary,
        keywords = R.string.settings_keywords_nav_bar,
        section = R.string.settings_section_tabs,
        destination = page,
    )

    val home = tab("home", R.string.settings_home_nav_tab_title, R.string.settings_home_nav_tab_subtitle)
    val shorts = tab("shorts", R.string.settings_shorts_nav_tab_title, R.string.settings_shorts_nav_tab_subtitle)
    val music = tab("music", R.string.settings_music_nav_tab_title, R.string.settings_music_nav_tab_subtitle)
    val search = tab("search", R.string.settings_search_nav_tab_title, R.string.settings_search_nav_tab_subtitle)
    val explore = tab("explore", R.string.settings_categories_nav_tab_title, R.string.settings_categories_nav_tab_subtitle)
    val downloads = tab("downloads", R.string.settings_downloads_nav_tab_title, R.string.settings_downloads_nav_tab_subtitle)
    val hideOnScroll =
        SettingEntry(
            key = "navigation_bar.hide_on_scroll",
            title = R.string.content_settings_navbar_hide_on_scroll_title,
            summary = R.string.content_settings_navbar_hide_on_scroll_subtitle,
            keywords = R.string.settings_keywords_nav_bar,
            section = R.string.settings_section_tabs,
            destination = page,
        )
    val order =
        SettingEntry(
            key = "navigation_bar.order",
            title = R.string.content_settings_nav_order_title,
            summary = R.string.content_settings_nav_order_subtitle,
            section = R.string.settings_section_tab_order,
            destination = page,
        )

    val all = listOf(home, shorts, music, search, explore, downloads, hideOnScroll, order)
}

internal object ThemeIndex {
    private val page = SettingsDestination.THEME

    val followSystem =
        SettingEntry(
            key = "theme.follow_system",
            title = R.string.settings_theme_follow_system,
            summary = R.string.settings_theme_follow_system_summary,
            keywords = R.string.settings_keywords_theme,
            destination = page,
        )
    val lightModeTheme =
        SettingEntry(
            key = "theme.light_mode_theme",
            title = R.string.appearance_system_light_theme,
            destination = page,
            revealVia = followSystem.key,
        )
    val darkModeTheme =
        SettingEntry(
            key = "theme.dark_mode_theme",
            title = R.string.appearance_system_dark_theme,
            destination = page,
            revealVia = followSystem.key,
        )
    val darkModeStyle =
        SettingEntry(
            key = "theme.dark_mode_style",
            title = R.string.appearance_system_dark_surface,
            keywords = R.string.settings_keywords_style,
            destination = page,
            revealVia = followSystem.key,
        )
    val style =
        SettingEntry(
            key = "theme.style",
            title = R.string.appearance_variant_title,
            keywords = R.string.settings_keywords_style,
            destination = page,
        )
    val palettes =
        SettingEntry(
            key = "theme.palettes",
            title = R.string.settings_theme_palettes,
            keywords = R.string.settings_keywords_theme,
            destination = page,
        )
    val materialYou =
        SettingEntry(
            key = "theme.material_you",
            title = R.string.theme_name_material_you,
            summary = R.string.theme_desc_material_you,
            destination = page,
            revealVia = palettes.key,
            availability = SettingAvailability.MinSdk(android.os.Build.VERSION_CODES.S),
        )

    val all = listOf(followSystem, lightModeTheme, darkModeTheme, darkModeStyle, style, palettes, materialYou)
}

internal object CustomThemeIndex {
    private fun list(
        key: String,
        title: Int,
        summary: Int? = null,
    ) = SettingEntry(
        key = "custom_themes.$key",
        title = title,
        summary = summary,
        keywords = R.string.settings_keywords_custom_themes,
        destination = SettingsDestination.CUSTOM_THEME,
    )

    val create = list("create", R.string.settings_custom_theme_create, R.string.settings_custom_theme_start_from)
    val import = list("import", R.string.settings_custom_theme_import, R.string.settings_custom_theme_empty_body)

    val name =
        SettingEntry(
            key = "custom_theme.name",
            title = R.string.settings_custom_theme_name,
            keywords = R.string.settings_keywords_custom_themes,
            destination = SettingsDestination.CUSTOM_THEME_EDIT,
        )
    val variant =
        SettingEntry(
            key = "custom_theme.variant",
            title = R.string.settings_custom_theme_editing,
            keywords = R.string.settings_keywords_custom_themes,
            destination = SettingsDestination.CUSTOM_THEME_EDIT,
        )

    /** Only the list is searchable: the editor needs a theme to open. */
    val all = listOf(create, import)
}

internal object DateTimeIndex {
    private val page = SettingsDestination.DATE_TIME

    val mode =
        SettingEntry(
            key = "date_time.mode",
            title = R.string.datetime_mode_header,
            destination = page,
        )
    val format =
        SettingEntry(
            key = "date_time.format",
            title = R.string.datetime_format_header,
            destination = page,
        )
    val listsOverride =
        SettingEntry(
            key = "date_time.override.lists",
            title = R.string.datetime_context_lists,
            section = R.string.datetime_overrides_header,
            destination = page,
        )
    val watchOverride =
        SettingEntry(
            key = "date_time.override.watch",
            title = R.string.datetime_context_watch,
            section = R.string.datetime_overrides_header,
            destination = page,
        )
    val descriptionOverride =
        SettingEntry(
            key = "date_time.override.description",
            title = R.string.datetime_context_description,
            section = R.string.datetime_overrides_header,
            destination = page,
        )

    val all = listOf(mode, format, listsOverride, watchOverride, descriptionOverride)
}

internal object FontIndex {
    private val page = SettingsDestination.FONT

    val font =
        SettingEntry(
            key = "font.choice",
            title = R.string.settings_font_title,
            keywords = R.string.settings_keywords_font,
            destination = page,
        )
    val customFile =
        SettingEntry(
            key = "font.custom_file",
            title = R.string.font_choose_file,
            keywords = R.string.settings_keywords_font,
            destination = page,
        )

    val all = listOf(font, customFile)
}
