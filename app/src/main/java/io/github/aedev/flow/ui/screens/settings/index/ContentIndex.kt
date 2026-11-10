package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination

internal object ContentIndex {
    private val page = SettingsDestination.CONTENT

    val homeFeed =
        SettingEntry(
            key = "content.home.feed",
            title = R.string.content_settings_home_feed_title,
            summary = R.string.content_settings_home_feed_subtitle,
            section = R.string.settings_section_home,
            destination = page,
        )
    val refreshOnReselect =
        SettingEntry(
            key = "content.home.refresh_on_reselect",
            title = R.string.content_settings_home_reselect_refresh_title,
            summary = R.string.content_settings_home_reselect_refresh_subtitle,
            section = R.string.settings_section_home,
            destination = page,
        )
    val homeChips =
        SettingEntry(
            key = "content.home.chips",
            title = R.string.content_settings_home_chips_title,
            summary = R.string.content_settings_home_chips_subtitle,
            section = R.string.settings_section_home,
            destination = page,
        )
    val homeNewVideosButton =
        SettingEntry(
            key = "content.home.new_videos_button",
            title = R.string.content_settings_home_new_videos_title,
            summary = R.string.content_settings_home_new_videos_subtitle,
            section = R.string.settings_section_home,
            destination = page,
        )
    val continueWatching =
        SettingEntry(
            key = "content.home.continue_watching",
            title = R.string.settings_continue_watching_title,
            summary = R.string.settings_continue_watching_subtitle,
            section = R.string.settings_section_home,
            destination = page,
        )
    val homeShortsShelf =
        SettingEntry(
            key = "content.home.shorts_shelf",
            title = R.string.settings_home_shorts_shelf_title,
            summary = R.string.settings_home_shorts_shelf_subtitle,
            section = R.string.settings_section_home,
            destination = page,
        )
    val homeSubscriptions =
        SettingEntry(
            key = "content.home.subscriptions",
            title = R.string.content_settings_home_subscriptions_title,
            summary = R.string.content_settings_home_subscriptions_subtitle,
            section = R.string.settings_section_home,
            destination = page,
        )
    val hideWatchedHome =
        SettingEntry(
            key = "content.home.hide_watched",
            title = R.string.content_settings_hide_watched_home_title,
            summary = R.string.content_settings_hide_watched_home_subtitle,
            keywords = R.string.settings_keywords_hide_watched,
            section = R.string.settings_section_home,
            destination = page,
        )
    val subsVideos =
        SettingEntry(
            key = "content.subs.videos",
            title = R.string.content_settings_subs_show_videos_title,
            summary = R.string.content_settings_subs_show_videos_subtitle,
            section = R.string.settings_section_subscriptions,
            destination = page,
        )
    val subsShorts =
        SettingEntry(
            key = "content.subs.shorts",
            title = R.string.content_settings_subs_show_shorts_title,
            summary = R.string.content_settings_subs_show_shorts_subtitle,
            section = R.string.settings_section_subscriptions,
            destination = page,
        )
    val subsShortsShelf =
        SettingEntry(
            key = "content.subs.shorts_shelf",
            title = R.string.settings_subs_shorts_shelf_title,
            summary = R.string.settings_subs_shorts_shelf_subtitle,
            section = R.string.settings_section_subscriptions,
            destination = page,
        )
    val subsLive =
        SettingEntry(
            key = "content.subs.live",
            title = R.string.content_settings_subs_show_live_title,
            summary = R.string.content_settings_subs_show_live_subtitle,
            section = R.string.settings_section_subscriptions,
            destination = page,
        )
    val subsUpcoming =
        SettingEntry(
            key = "content.subs.upcoming",
            title = R.string.content_settings_subs_show_upcoming_title,
            summary = R.string.content_settings_subs_show_upcoming_subtitle,
            section = R.string.settings_section_subscriptions,
            destination = page,
        )
    val subsPostsChip =
        SettingEntry(
            key = "content.subs.posts_chip",
            title = R.string.content_settings_subs_posts_chip_title,
            summary = R.string.content_settings_subs_posts_chip_subtitle,
            section = R.string.settings_section_subscriptions,
            destination = page,
        )
    val subsUngrouped =
        SettingEntry(
            key = "content.subs.ungrouped",
            title = R.string.content_settings_subs_ungrouped_title,
            summary = R.string.content_settings_subs_ungrouped_subtitle,
            section = R.string.settings_section_subscriptions,
            destination = page,
        )
    val subsCollaborations =
        SettingEntry(
            key = "content.subs.collaborations",
            title = R.string.content_settings_subs_collaborations_title,
            summary = R.string.content_settings_subs_collaborations_subtitle,
            section = R.string.settings_section_subscriptions,
            destination = page,
        )
    val hideWatchedSubs =
        SettingEntry(
            key = "content.subs.hide_watched",
            title = R.string.content_settings_hide_watched_subscriptions_title,
            summary = R.string.content_settings_hide_watched_subscriptions_subtitle,
            keywords = R.string.settings_keywords_hide_watched,
            section = R.string.settings_section_subscriptions,
            destination = page,
        )
    val hideUnplayableSubs =
        SettingEntry(
            key = "content.subs.hide_unplayable",
            title = R.string.content_settings_hide_unplayable_subscriptions_title,
            summary = R.string.content_settings_hide_unplayable_subscriptions_subtitle,
            section = R.string.settings_section_subscriptions,
            destination = page,
        )
    val subsRefreshOnStartup =
        SettingEntry(
            key = "content.subs.refresh_on_startup",
            title = R.string.content_settings_subs_startup_refresh_title,
            summary = R.string.content_settings_subs_startup_refresh_subtitle,
            section = R.string.settings_section_subscriptions,
            destination = page,
        )
    val subsCheckedCount =
        SettingEntry(
            key = "content.subs.checked_count",
            title = R.string.content_settings_subs_show_checked_count_title,
            summary = R.string.content_settings_subs_show_checked_count_subtitle,
            section = R.string.settings_section_subscriptions,
            destination = page,
        )
    val shortsContent =
        SettingEntry(
            key = "content.shorts.enabled",
            title = R.string.content_settings_shorts_content_title,
            summary = R.string.content_settings_shorts_content_subtitle,
            section = R.string.content_settings_header_shorts,
            destination = page,
        )
    val channelShortsTab =
        SettingEntry(
            key = "content.shorts.channel_tab",
            title = R.string.content_settings_channel_shorts_tab_title,
            summary = R.string.content_settings_channel_shorts_tab_subtitle,
            section = R.string.content_settings_header_shorts,
            destination = page,
        )
    val hideWatchedShorts =
        SettingEntry(
            key = "content.shorts.hide_watched",
            title = R.string.content_settings_hide_watched_shorts_title,
            summary = R.string.content_settings_hide_watched_shorts_subtitle,
            keywords = R.string.settings_keywords_hide_watched,
            section = R.string.content_settings_header_shorts,
            destination = page,
        )
    val removeWatchedWatchLater =
        SettingEntry(
            key = "content.watch_later.remove_watched",
            title = R.string.content_settings_remove_watched_watch_later_title,
            summary = R.string.content_settings_remove_watched_watch_later_subtitle,
            keywords = R.string.settings_keywords_hide_watched,
            section = R.string.watch_later,
            destination = page,
        )
    val watchedThreshold =
        SettingEntry(
            key = "content.watched.threshold",
            title = R.string.content_settings_watched_threshold_title,
            keywords = R.string.settings_keywords_hide_watched,
            section = R.string.settings_section_watched,
            destination = page,
        )
    val notes =
        SettingEntry(
            key = "content.notes.enabled",
            title = R.string.content_settings_notes_title,
            summary = R.string.content_settings_notes_subtitle,
            section = R.string.content_settings_notes_title,
            destination = page,
        )
    val channelNotes =
        SettingEntry(
            key = "content.notes.channel",
            title = R.string.content_settings_channel_notes_title,
            summary = R.string.content_settings_channel_notes_subtitle,
            section = R.string.content_settings_notes_title,
            destination = page,
        )
    val videoNotes =
        SettingEntry(
            key = "content.notes.video",
            title = R.string.content_settings_video_notes_title,
            summary = R.string.content_settings_video_notes_subtitle,
            section = R.string.content_settings_notes_title,
            destination = page,
        )
    val shareWithoutText =
        SettingEntry(
            key = "content.sharing.without_text",
            title = R.string.content_settings_share_without_text_title,
            summary = R.string.content_settings_share_without_text_subtitle,
            section = R.string.settings_section_sharing,
            destination = page,
        )
    val openLinks =
        SettingEntry(
            key = "content.links.open",
            title = R.string.content_settings_open_links_title,
            summary = R.string.content_settings_open_links_subtitle,
            section = R.string.settings_section_links,
            destination = page,
        )
    val sharedLinks =
        SettingEntry(
            key = "content.links.shared",
            title = R.string.content_settings_shared_links_title,
            summary = R.string.content_settings_shared_links_subtitle,
            section = R.string.settings_section_links,
            destination = page,
        )

    val all =
        listOf(
            homeFeed,
            refreshOnReselect,
            homeChips,
            homeNewVideosButton,
            continueWatching,
            homeShortsShelf,
            homeSubscriptions,
            hideWatchedHome,
            subsVideos,
            subsShorts,
            subsShortsShelf,
            subsLive,
            subsUpcoming,
            subsCollaborations,
            subsPostsChip,
            subsUngrouped,
            hideWatchedSubs,
            hideUnplayableSubs,
            subsRefreshOnStartup,
            subsCheckedCount,
            shortsContent,
            channelShortsTab,
            hideWatchedShorts,
            removeWatchedWatchLater,
            watchedThreshold,
            notes,
            channelNotes,
            videoNotes,
            shareWithoutText,
            openLinks,
            sharedLinks,
        )
}
