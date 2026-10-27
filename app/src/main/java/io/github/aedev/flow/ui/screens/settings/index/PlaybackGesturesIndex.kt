package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination

internal object PlaybackGesturesIndex {
    private val page = SettingsDestination.PLAYBACK

    val doubleTapSeek =
        SettingEntry(
            key = "playback.double_tap_seek",
            title = R.string.player_settings_double_tap_seek,
            keywords = R.string.settings_keywords_gestures,
            section = R.string.player_appearance_gestures_header,
            destination = page,
        )
    val seekZoneWidth =
        SettingEntry(
            key = "playback.seek_zone_width",
            title = R.string.player_settings_seek_zone_width,
            keywords = R.string.settings_keywords_gestures,
            section = R.string.player_appearance_gestures_header,
            destination = page,
        )
    val brightnessGesture =
        SettingEntry(
            key = "playback.brightness_gesture",
            title = R.string.player_appearance_brightness_gesture_title,
            summary = R.string.player_appearance_brightness_gesture_subtitle,
            keywords = R.string.settings_keywords_gestures,
            section = R.string.player_appearance_gestures_header,
            destination = page,
        )
    val rememberBrightness =
        SettingEntry(
            key = "playback.remember_brightness",
            title = R.string.player_appearance_remember_brightness_title,
            summary = R.string.player_appearance_remember_brightness_subtitle,
            section = R.string.player_appearance_gestures_header,
            destination = page,
        )
    val volumeGesture =
        SettingEntry(
            key = "playback.volume_gesture",
            title = R.string.player_appearance_volume_gesture_title,
            summary = R.string.player_appearance_volume_gesture_subtitle,
            keywords = R.string.settings_keywords_gestures,
            section = R.string.player_appearance_gestures_header,
            destination = page,
        )
    val volumeBoost =
        SettingEntry(
            key = "playback.volume_boost",
            title = R.string.player_appearance_volume_boost_title,
            summary = R.string.player_appearance_volume_boost_subtitle,
            section = R.string.player_appearance_gestures_header,
            destination = page,
        )
    val speedSwipe =
        SettingEntry(
            key = "playback.speed_swipe",
            title = R.string.player_settings_speed_swipe,
            keywords = R.string.settings_keywords_gestures,
            section = R.string.player_appearance_gestures_header,
            destination = page,
        )
    val speedSwipeStep =
        SettingEntry(
            key = "playback.speed_swipe_step",
            title = R.string.player_settings_speed_swipe_step,
            section = R.string.player_appearance_gestures_header,
            destination = page,
        )
    val seekGesture =
        SettingEntry(
            key = "playback.seek_gesture",
            title = R.string.player_appearance_seek_gesture_title,
            summary = R.string.player_appearance_seek_gesture_subtitle,
            keywords = R.string.settings_keywords_gestures,
            section = R.string.player_appearance_gestures_header,
            destination = page,
        )
    val haptics =
        SettingEntry(
            key = "playback.haptics",
            title = R.string.player_appearance_haptics_title,
            summary = R.string.player_appearance_haptics_subtitle,
            section = R.string.player_appearance_gestures_header,
            destination = page,
        )
    val controlsWhileLoading =
        SettingEntry(
            key = "playback.controls_while_loading",
            title = R.string.player_appearance_controls_while_loading_title,
            summary = R.string.player_appearance_controls_while_loading_subtitle,
            section = R.string.player_appearance_gestures_header,
            destination = page,
        )

    val all =
        listOf(
            doubleTapSeek,
            seekZoneWidth,
            brightnessGesture,
            rememberBrightness,
            volumeGesture,
            volumeBoost,
            speedSwipe,
            speedSwipeStep,
            seekGesture,
            haptics,
            controlsWhileLoading,
        )
}
