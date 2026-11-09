package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination

internal object PlaybackCrossfadeIndex {
    private val page = SettingsDestination.PLAYBACK

    val crossfade =
        SettingEntry(
            key = "playback.crossfade",
            title = R.string.settings_crossfade_title,
            summary = R.string.settings_crossfade_summary,
            keywords = R.string.settings_keywords_crossfade,
            section = R.string.settings_section_music,
            destination = page,
        )
    val crossfadeDuration =
        SettingEntry(
            key = "playback.crossfade_duration",
            title = R.string.settings_crossfade_duration_title,
            keywords = R.string.settings_keywords_crossfade_duration,
            section = R.string.settings_section_music,
            destination = page,
            revealVia = "playback.crossfade",
        )
    val crossfadeOnAutoAdvance =
        SettingEntry(
            key = "playback.crossfade_auto_advance",
            title = R.string.settings_crossfade_auto_title,
            summary = R.string.settings_crossfade_auto_summary,
            section = R.string.settings_section_music,
            destination = page,
            revealVia = "playback.crossfade",
        )
    val crossfadeOnManualSkip =
        SettingEntry(
            key = "playback.crossfade_manual_skip",
            title = R.string.settings_crossfade_skip_title,
            summary = R.string.settings_crossfade_skip_summary,
            section = R.string.settings_section_music,
            destination = page,
            revealVia = "playback.crossfade",
        )
    val crossfadeKeepAlbums =
        SettingEntry(
            key = "playback.crossfade_keep_albums",
            title = R.string.settings_crossfade_albums_title,
            summary = R.string.settings_crossfade_albums_summary,
            section = R.string.settings_section_music,
            destination = page,
            revealVia = "playback.crossfade",
        )
    val crossfadeCurve =
        SettingEntry(
            key = "playback.crossfade_curve",
            title = R.string.settings_crossfade_curve_title,
            section = R.string.settings_section_music,
            destination = page,
            revealVia = "playback.crossfade",
        )

    val all =
        listOf(
            crossfade,
            crossfadeDuration,
            crossfadeOnAutoAdvance,
            crossfadeOnManualSkip,
            crossfadeKeepAlbums,
            crossfadeCurve,
        )
}
