package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingAvailability
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination

/** One entry per settings page, so a page is found by its own name as well as by its options. */
internal object DestinationIndex {
    private const val KEY_PREFIX = "dest."

    /**
     * Pages about phone hardware or features TV leaves out: widgets, the navigation bar, local media,
     * notifications, and the app lock, which needs a screen lock most TVs don't have.
     */
    private val notOnTv =
        setOf(
            SettingsDestination.WIDGETS,
            SettingsDestination.NAVIGATION_BAR,
            SettingsDestination.LOCAL_MEDIA,
            SettingsDestination.NOTIFICATIONS,
            SettingsDestination.SECURITY,
        )

    fun entry(destination: SettingsDestination): SettingEntry =
        SettingEntry(
            key = KEY_PREFIX + destination.id,
            title = destination.titleRes,
            destination = destination.parent ?: SettingsDestination.HOME,
            summary = summaryOf(destination),
            keywords = if (destination == SettingsDestination.EQUALIZER) R.string.eq_settings_keywords else null,
            availability =
                when (destination) {
                    in notOnTv -> SettingAvailability.NotOnTv
                    SettingsDestination.REMOTE_GUIDE -> SettingAvailability.TvOnly
                    else -> SettingAvailability.Always
                },
        )

    /** Whether [option] can be reached on a TV ([tv]) or elsewhere: it and every page above it exist there. */
    fun isReachable(
        option: SettingEntry,
        tv: Boolean,
    ): Boolean = option.availability.shownOn(tv) && option.destination.path.all { entry(it).availability.shownOn(tv) }

    /** The page a destination entry stands for, or null for an option entry. */
    fun destinationOf(entry: SettingEntry): SettingsDestination? =
        entry.key
            .takeIf { it.startsWith(KEY_PREFIX) }
            ?.removePrefix(KEY_PREFIX)
            ?.let(SettingsDestination::fromId)

    /** The editor is left out: it opens one theme, so it has no page of its own to find. */
    val all: List<SettingEntry> =
        SettingsDestination.entries
            .filter {
                it != SettingsDestination.HOME && it != SettingsDestination.CUSTOM_THEME_EDIT &&
                    it != SettingsDestination.PROFILE_EDIT
            }.map(::entry)

    private fun summaryOf(destination: SettingsDestination): Int? =
        when (destination) {
            SettingsDestination.HOME -> null
            SettingsDestination.TASTE -> R.string.taste_summary
            SettingsDestination.HIDDEN_CONTENT -> R.string.taste_hidden_summary
            SettingsDestination.FAVOURITE_ARTISTS -> R.string.favourite_artists_summary
            SettingsDestination.DISCOVER_CHANNELS -> R.string.discover_channels_summary
            SettingsDestination.APPEARANCE -> R.string.settings_appearance_summary
            SettingsDestination.THEME -> R.string.settings_theme_summary
            SettingsDestination.FONT -> R.string.settings_font_summary
            SettingsDestination.CUSTOM_THEME -> R.string.settings_custom_theme_summary
            SettingsDestination.CUSTOM_THEME_EDIT -> null
            SettingsDestination.NAVIGATION_BAR -> R.string.settings_navigation_bar_summary
            SettingsDestination.DATE_TIME -> R.string.settings_item_datetime_subtitle
            SettingsDestination.PLAYER_APPEARANCE -> R.string.settings_player_appearance_summary
            SettingsDestination.LANGUAGE_REGION -> R.string.settings_language_region_summary
            SettingsDestination.PLAYBACK -> R.string.settings_playback_summary
            SettingsDestination.BUFFER -> R.string.settings_item_buffer_subtitle
            SettingsDestination.EQUALIZER -> R.string.eq_settings_summary
            SettingsDestination.QUALITY -> R.string.settings_quality_summary
            SettingsDestination.CONTENT -> R.string.settings_content_summary
            SettingsDestination.MUSIC_HOME -> R.string.settings_music_home_summary
            SettingsDestination.LIBRARY -> R.string.settings_library_summary
            SettingsDestination.TOPICS -> R.string.settings_topics_summary
            SettingsDestination.INTEGRATIONS -> R.string.settings_integrations_summary
            SettingsDestination.SCROBBLING -> R.string.scrobbling_summary
            SettingsDestination.BACKUP -> R.string.settings_backup_summary
            SettingsDestination.SYNC -> R.string.sync_devices_summary
            SettingsDestination.PROFILES -> R.string.profiles_summary
            SettingsDestination.PROFILE_EDIT -> null
            SettingsDestination.HISTORY -> R.string.settings_history_summary
            SettingsDestination.DOWNLOADS -> R.string.settings_downloads_summary
            SettingsDestination.LOCAL_MEDIA -> R.string.settings_local_media_summary
            SettingsDestination.NOTIFICATIONS -> R.string.settings_item_notifications_subtitle
            SettingsDestination.NETWORK -> R.string.settings_network_summary
            SettingsDestination.WELLBEING -> R.string.settings_wellbeing_summary
            SettingsDestination.SECURITY -> R.string.settings_security_summary
            SettingsDestination.WIDGETS -> R.string.settings_widgets_summary
            SettingsDestination.ABOUT -> R.string.settings_about_summary
            SettingsDestination.DIAGNOSTICS -> R.string.settings_item_diagnostics_subtitle
            SettingsDestination.REMOTE_GUIDE -> R.string.tv_remote_guide_subtitle
        }
}
