package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination

internal object PlaybackShortsIndex {
    private val page = SettingsDestination.PLAYBACK

    val disableShortsPlayer =
        SettingEntry(
            key = "playback.disable_shorts_player",
            title = R.string.content_settings_disable_shorts_player_title,
            summary = R.string.content_settings_disable_shorts_player_subtitle,
            section = R.string.settings_section_shorts_player,
            destination = page,
        )
    val shortsPlayerPrompt =
        SettingEntry(
            key = "playback.shorts_player_prompt",
            title = R.string.content_settings_shorts_player_prompt_title,
            summary = R.string.content_settings_shorts_player_prompt_subtitle,
            section = R.string.settings_section_shorts_player,
            destination = page,
        )
    val shortsPlaybackMode =
        SettingEntry(
            key = "playback.shorts_playback_mode",
            title = R.string.player_settings_shorts_playback_mode_title,
            section = R.string.settings_section_shorts_player,
            destination = page,
        )
    val shortsBackgroundPlay =
        SettingEntry(
            key = "playback.shorts_background_play",
            title = R.string.player_settings_shorts_background_play,
            summary = R.string.player_settings_shorts_background_play_subtitle,
            section = R.string.settings_section_shorts_player,
            destination = page,
        )
    val shortsPip =
        SettingEntry(
            key = "playback.shorts_pip",
            title = R.string.player_settings_shorts_pip,
            summary = R.string.player_settings_shorts_pip_subtitle,
            section = R.string.settings_section_shorts_player,
            destination = page,
        )
    val shortsContinueIntoFeed =
        SettingEntry(
            key = "playback.shorts_continue_into_feed",
            title = R.string.player_settings_shorts_continue_into_feed,
            summary = R.string.player_settings_shorts_continue_into_feed_subtitle,
            section = R.string.settings_section_shorts_player,
            destination = page,
        )

    val all =
        listOf(
            disableShortsPlayer,
            shortsPlayerPrompt,
            shortsPlaybackMode,
            shortsBackgroundPlay,
            shortsPip,
            shortsContinueIntoFeed,
        )
}
