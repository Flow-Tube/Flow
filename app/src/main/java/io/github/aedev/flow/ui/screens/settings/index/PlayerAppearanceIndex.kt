package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination

internal object PlayerAppearanceIndex {
    private val page = SettingsDestination.PLAYER_APPEARANCE

    private fun entry(
        key: String,
        title: Int,
        summary: Int? = null,
        section: Int,
        revealVia: String? = null,
    ) = SettingEntry(
        key = "player_appearance.$key",
        title = title,
        summary = summary,
        section = section,
        revealVia = revealVia,
        destination = page,
    )

    val sliderStyle =
        entry(
            "slider_style",
            R.string.player_appearance_style_title,
            R.string.player_appearance_style_subtitle,
            R.string.settings_section_seek_bar,
        )
    val portraitWidth = entry("portrait_width", R.string.player_portrait_seekbar_width_title, section = R.string.settings_section_seek_bar)
    val portraitCustom =
        entry(
            "portrait_custom",
            R.string.settings_seekbar_custom_width,
            section = R.string.settings_section_seek_bar,
            revealVia = "player_appearance.portrait_width",
        )
    val fullscreenWidth =
        entry("fullscreen_width", R.string.player_fullscreen_seekbar_width_title, section = R.string.settings_section_seek_bar)
    val fullscreenCustom =
        entry(
            "fullscreen_custom",
            R.string.settings_seekbar_custom_width,
            section = R.string.settings_section_seek_bar,
            revealVia = "player_appearance.fullscreen_width",
        )
    val idleProgressBar =
        entry(
            "idle_progress_bar",
            R.string.player_appearance_idle_progress_title,
            R.string.player_appearance_idle_progress_subtitle,
            R.string.settings_section_seek_bar,
        )
    val scrubPreview =
        entry(
            "scrub_preview",
            R.string.player_appearance_scrub_preview_title,
            R.string.player_appearance_scrub_preview_subtitle,
            R.string.settings_section_seek_bar,
        )
    val frameStep =
        entry(
            "frame_step",
            R.string.player_appearance_frame_step_title,
            R.string.player_appearance_frame_step_subtitle,
            R.string.settings_section_seek_bar,
        )
    val musicBackground =
        entry("music_background", R.string.player_background_style_title, section = R.string.settings_section_music_player)
    val followLightTheme =
        entry(
            "music_follow_light_theme",
            R.string.player_appearance_follow_light_theme_title,
            R.string.player_appearance_follow_light_theme_subtitle,
            R.string.settings_section_music_player,
            revealVia = musicBackground.key,
        )
    val hideMusicArtwork =
        entry(
            "hide_music_artwork",
            R.string.player_appearance_hide_music_artwork_title,
            R.string.player_appearance_hide_music_artwork_subtitle,
            R.string.settings_section_music_player,
        )
    val animatedArtwork =
        entry(
            "animated_artwork",
            R.string.player_appearance_animated_artwork_title,
            R.string.player_appearance_animated_artwork_subtitle,
            R.string.settings_section_music_player,
        )
    val animatedArtworkWifiOnly =
        entry(
            "animated_artwork_wifi_only",
            R.string.player_appearance_animated_artwork_wifi_title,
            R.string.player_appearance_animated_artwork_wifi_subtitle,
            R.string.settings_section_music_player,
            revealVia = animatedArtwork.key,
        )
    val artworkControlColors =
        entry(
            "artwork_control_colors",
            R.string.player_appearance_artwork_control_colors_title,
            R.string.player_appearance_artwork_control_colors_subtitle,
            R.string.settings_section_music_player,
        )
    val plainControlColors =
        entry(
            "plain_control_colors",
            R.string.player_appearance_plain_control_colors_title,
            section = R.string.settings_section_music_player,
            revealVia = artworkControlColors.key,
        )
    val sleepTimerButton =
        entry(
            "music_sleep_timer_button",
            R.string.player_appearance_sleep_timer_button_title,
            R.string.player_appearance_sleep_timer_button_subtitle,
            R.string.settings_section_music_player,
        )
    val queueSwipeLeft =
        entry("queue_swipe_left", R.string.queue_swipe_left, R.string.queue_swipe_subtitle, R.string.settings_section_music_player)
    val queueSwipeRight =
        entry("queue_swipe_right", R.string.queue_swipe_right, R.string.queue_swipe_subtitle, R.string.settings_section_music_player)
    val adaptiveSize =
        entry(
            "adaptive_size",
            R.string.player_adaptive_size_title,
            R.string.player_adaptive_size_subtitle,
            R.string.settings_section_video_player,
        )
    val ambientMode =
        entry(
            "ambient_mode",
            R.string.player_settings_ambient_mode,
            R.string.player_settings_ambient_mode_subtitle,
            R.string.settings_section_video_player,
        )
    val videoArtworkColors =
        entry(
            "video_artwork_colors",
            R.string.player_appearance_video_artwork_colors_title,
            R.string.player_appearance_video_artwork_colors_subtitle,
            R.string.settings_section_video_player,
        )
    val groupedQuality =
        entry(
            "grouped_quality",
            R.string.player_appearance_grouped_quality_selector_title,
            R.string.player_appearance_grouped_quality_selector_subtitle,
            R.string.settings_section_video_player,
        )
    val paidPromotion =
        entry(
            "paid_promotion",
            R.string.player_appearance_paid_promotion_title,
            R.string.player_appearance_paid_promotion_subtitle,
            R.string.settings_section_video_player,
        )
    val gestureOverlay =
        entry(
            "gesture_overlay",
            R.string.player_appearance_gesture_overlay_title,
            R.string.player_appearance_gesture_overlay_subtitle,
            R.string.settings_section_video_player,
        )
    val sidePanelWidth =
        entry(
            "side_panel_width",
            R.string.player_side_panel_width_title,
            R.string.player_side_panel_width_summary,
            R.string.settings_section_video_player,
        )
    val shortsUi =
        entry(
            "shorts_ui",
            R.string.player_appearance_shorts_ui_title,
            R.string.player_appearance_shorts_ui_subtitle,
            R.string.content_settings_header_shorts,
        )
    val miniPlayerSize = entry("mini_player_size", R.string.mini_player_size, section = R.string.settings_section_mini_player)
    val miniPlayerSkip =
        entry("mini_player_skip", R.string.skip_button_title, R.string.skip_button_subtitle, R.string.settings_section_mini_player)
    val miniPlayerNextPrev =
        entry(
            "mini_player_next_prev",
            R.string.player_nav_btn_title,
            R.string.player_nav_btn_subtitle,
            R.string.settings_section_mini_player,
        )

    val miniBarSwipeLeft =
        entry("mini_bar_swipe_left", R.string.mini_bar_swipe_left, R.string.mini_bar_swipe_subtitle, R.string.settings_section_mini_player)
    val miniBarSwipeRight =
        entry(
            "mini_bar_swipe_right",
            R.string.mini_bar_swipe_right,
            R.string.mini_bar_swipe_subtitle,
            R.string.settings_section_mini_player,
        )

    val all =
        listOf(
            sliderStyle,
            portraitWidth,
            portraitCustom,
            fullscreenWidth,
            fullscreenCustom,
            idleProgressBar,
            scrubPreview,
            frameStep,
            musicBackground,
            followLightTheme,
            hideMusicArtwork,
            animatedArtwork,
            animatedArtworkWifiOnly,
            artworkControlColors,
            plainControlColors,
            sleepTimerButton,
            queueSwipeLeft,
            queueSwipeRight,
            adaptiveSize,
            ambientMode,
            videoArtworkColors,
            groupedQuality,
            paidPromotion,
            gestureOverlay,
            sidePanelWidth,
            shortsUi,
            miniPlayerSize,
            miniPlayerSkip,
            miniPlayerNextPrev,
            miniBarSwipeLeft,
            miniBarSwipeRight,
        )
}
