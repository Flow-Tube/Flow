package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination

internal object PlaybackLyricsIndex {
    private val page = SettingsDestination.PLAYBACK

    val lyricsOnLockScreen =
        SettingEntry(
            key = "playback.lyrics_on_lock_screen",
            title = R.string.lyrics_on_lock_screen,
            summary = R.string.lyrics_on_lock_screen_summary,
            section = R.string.settings_section_music,
            destination = page,
        )
    val lyricsOnLockScreenTiming =
        SettingEntry(
            key = "playback.lyrics_on_lock_screen_timing",
            title = R.string.lyrics_on_lock_screen_timing,
            summary = R.string.lyrics_on_lock_screen_timing_summary,
            section = R.string.settings_section_music,
            destination = page,
        )
    val lyricsSponsorBlockTiming =
        SettingEntry(
            key = "playback.lyrics_sponsorblock_timing",
            title = R.string.lyrics_sponsorblock_timing,
            summary = R.string.lyrics_sponsorblock_timing_summary,
            section = R.string.settings_section_music,
            destination = page,
        )
    val lyricsOnDemand =
        SettingEntry(
            key = "playback.lyrics_on_demand",
            title = R.string.lyrics_on_demand,
            summary = R.string.lyrics_on_demand_summary,
            keywords = R.string.settings_keywords_lyrics_on_demand,
            section = R.string.settings_section_music,
            destination = page,
        )
    val lyricsProviders =
        SettingEntry(
            key = "playback.lyrics_providers",
            title = R.string.lyrics_provider_title,
            summary = R.string.lyrics_provider_subtitle,
            section = R.string.settings_section_music,
            destination = page,
        )

    val all =
        listOf(
            lyricsOnLockScreen,
            lyricsOnLockScreenTiming,
            lyricsSponsorBlockTiming,
            lyricsOnDemand,
            lyricsProviders,
        )
}
