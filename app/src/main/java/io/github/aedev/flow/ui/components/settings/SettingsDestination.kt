package io.github.aedev.flow.ui.components.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import io.github.aedev.flow.R

/**
 * Every page inside Settings. A destination with a [parent] is a sub-page: on a phone it pushes over
 * its parent, beside the settings list it replaces the parent in the detail pane.
 */
enum class SettingsDestination(
    val id: String,
    @StringRes val titleRes: Int,
    val parent: SettingsDestination? = null,
) {
    /** The settings list itself, for options that live directly on it such as Deep Flow. */
    HOME("home", R.string.settings_title),
    TASTE("taste", R.string.taste_title),
    HIDDEN_CONTENT("hidden_content", R.string.taste_hidden_title, TASTE),
    FAVOURITE_ARTISTS("favourite_artists", R.string.favourite_artists_title, TASTE),
    DISCOVER_CHANNELS("discover_channels", R.string.discover_channels_title, TASTE),
    APPEARANCE("appearance", R.string.appearance_title),
    THEME("theme", R.string.settings_item_theme, APPEARANCE),
    FONT("font", R.string.settings_font_title, APPEARANCE),
    CUSTOM_THEME("custom_theme", R.string.settings_custom_themes_title, THEME),
    CUSTOM_THEME_EDIT("custom_theme_edit", R.string.settings_custom_theme_editor_title, CUSTOM_THEME),
    NAVIGATION_BAR("navigation_bar", R.string.settings_navigation_bar_title, APPEARANCE),
    DATE_TIME("date_time", R.string.settings_item_datetime, APPEARANCE),
    PLAYER_APPEARANCE("player_appearance", R.string.player_appearance_title, APPEARANCE),
    LANGUAGE_REGION("language_region", R.string.settings_language_region_title),
    WIDGETS("widgets", R.string.settings_widgets_title),
    PLAYBACK("playback", R.string.settings_playback_title),
    BUFFER("buffer", R.string.buffer_settings_title, PLAYBACK),
    EQUALIZER("equalizer", R.string.equalizer, PLAYBACK),
    QUALITY("quality", R.string.quality),
    CONTENT("content", R.string.settings_content_title),
    MUSIC_HOME("music_home", R.string.settings_music_home_title, CONTENT),
    LIBRARY("library", R.string.library, CONTENT),
    TOPICS("topics", R.string.settings_topics_title),
    INTEGRATIONS("integrations", R.string.settings_integrations_title),
    SCROBBLING("scrobbling", R.string.scrobbling_title, INTEGRATIONS),
    BACKUP("backup", R.string.settings_backup_title),
    SYNC("sync", R.string.sync_devices_title),
    PROFILES("profiles", R.string.profiles_title),
    PROFILE_EDIT("profile_edit", R.string.profiles_edit_title, PROFILES),
    HISTORY("history", R.string.settings_history_title),
    DOWNLOADS("downloads", R.string.settings_downloads_title),
    LOCAL_MEDIA("local_media", R.string.settings_local_media_title, DOWNLOADS),
    NOTIFICATIONS("notifications", R.string.settings_header_notifications),
    NETWORK("network", R.string.settings_network_title),
    WELLBEING("wellbeing", R.string.settings_wellbeing_title),
    SECURITY("security", R.string.settings_security_title),
    ABOUT("about", R.string.settings_item_about_flow),
    REMOTE_GUIDE("remote_guide", R.string.tv_remote_guide_title, ABOUT),
    DIAGNOSTICS("diagnostics", R.string.settings_item_diagnostics),
    ;

    /** The top-level destination this page lives under — what the settings list shows as selected. */
    val root: SettingsDestination
        get() = parent?.root ?: this

    /** This page and its parents, outermost first. */
    val path: List<SettingsDestination>
        get() = (parent?.path ?: emptyList()) + this

    companion object {
        fun fromId(id: String): SettingsDestination? = entries.firstOrNull { it.id == id }
    }
}

/** Tabs of the destinations that switch between views with a toggle group. */
object SettingsTabs {
    const val QUALITY_VIDEO = "video"
    const val QUALITY_SHORTS = "shorts"
    const val QUALITY_MUSIC = "music"
    const val BACKUP_EXPORT = "export"
    const val BACKUP_IMPORT = "import"
    const val BACKUP_AUTO = "auto"
    const val TOPICS_INTERESTS = "interests"
    const val TOPICS_BLOCKED = "blocked"
}

/**
 * Where to open Settings: a page, optionally one of its tabs, optionally an option on it to scroll
 * to and highlight. Encoded as a plain string so it survives the navigation route and saved state.
 */
@Immutable
data class SettingsTarget(
    val destination: SettingsDestination,
    val tab: String? = null,
    val highlight: String? = null,
) {
    fun encode(): String =
        buildString {
            append(destination.id)
            if (tab != null) append(TAB_SEPARATOR).append(tab)
            if (highlight != null) append(HIGHLIGHT_SEPARATOR).append(highlight)
        }

    companion object {
        private const val TAB_SEPARATOR = '~'
        private const val HIGHLIGHT_SEPARATOR = '#'

        fun decode(value: String?): SettingsTarget? {
            if (value.isNullOrBlank()) return null
            val highlight = value.substringAfter(HIGHLIGHT_SEPARATOR, "").ifBlank { null }
            val head = value.substringBefore(HIGHLIGHT_SEPARATOR)
            val tab = head.substringAfter(TAB_SEPARATOR, "").ifBlank { null }
            val destination = SettingsDestination.fromId(head.substringBefore(TAB_SEPARATOR)) ?: return null
            return SettingsTarget(destination, tab, highlight)
        }
    }
}
