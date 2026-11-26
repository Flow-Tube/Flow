package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingAvailability
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
        availability: SettingAvailability = SettingAvailability.Always,
    ) = SettingEntry(
        key = "player_appearance.$key",
        title = title,
        summary = summary,
        section = section,
        revealVia = revealVia,
        destination = page,
        availability = availability,
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
    val lyricsOnCover =
        entry(
            "music_lyrics_on_cover",
            R.string.player_appearance_lyrics_on_cover_title,
            R.string.player_appearance_lyrics_on_cover_subtitle,
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
    val splitTransport =
        entry(
            "music_split_transport",
            R.string.player_appearance_split_transport_title,
            R.string.player_appearance_split_transport_subtitle,
            R.string.settings_section_music_player,
        )
    val sleepTimerButton =
        entry(
            "music_sleep_timer_button",
            R.string.player_appearance_sleep_timer_button_title,
            R.string.player_appearance_sleep_timer_button_subtitle,
            R.string.settings_section_music_player,
        )
    val miniCoverShape =
        entry(
            "mini_cover_shape",
            R.string.player_appearance_mini_cover_title,
            R.string.player_appearance_mini_cover_subtitle,
            R.string.settings_section_music_mini_player,
        )
    val miniCoverMotion =
        entry(
            "mini_cover_motion",
            R.string.player_appearance_mini_cover_motion_title,
            section = R.string.settings_section_music_mini_player,
            revealVia = miniCoverShape.key,
        )
    val miniCoverShapes =
        entry(
            "mini_cover_shapes",
            R.string.player_appearance_cover_shapes_title,
            section = R.string.settings_section_music_mini_player,
            revealVia = miniCoverShape.key,
        )
    val miniCoverPausedShape =
        entry(
            "mini_cover_paused_shape",
            R.string.player_appearance_cover_paused_title,
            section = R.string.settings_section_music_mini_player,
            revealVia = miniCoverShape.key,
        )
    val miniBarProgress =
        entry(
            "mini_bar_progress",
            R.string.player_appearance_bar_progress_title,
            R.string.player_appearance_bar_progress_subtitle,
            R.string.settings_section_music_mini_player,
        )
    val miniCoverRing =
        entry(
            "mini_cover_ring",
            R.string.player_appearance_cover_ring_title,
            R.string.player_appearance_cover_ring_subtitle,
            R.string.settings_section_music_mini_player,
        )
    val miniBackground =
        entry(
            "mini_background",
            R.string.player_appearance_mini_background_title,
            R.string.player_appearance_mini_background_subtitle,
            R.string.settings_section_music_mini_player,
        )
    val miniButtons =
        entry(
            "mini_buttons",
            R.string.player_appearance_mini_buttons_title,
            R.string.player_appearance_mini_buttons_subtitle,
            R.string.settings_section_music_mini_player,
        )
    val miniCoverTap =
        entry("mini_cover_tap", R.string.player_appearance_mini_cover_tap_title, section = R.string.settings_section_music_mini_player)
    val miniLongPress =
        entry("mini_long_press", R.string.player_appearance_mini_long_press_title, section = R.string.settings_section_music_mini_player)
    val queueSwipeLeft =
        entry(
            "queue_swipe_left",
            R.string.queue_swipe_left,
            R.string.queue_swipe_subtitle,
            R.string.settings_section_music_player,
            availability = SettingAvailability.NotOnTv,
        )
    val queueSwipeRight =
        entry(
            "queue_swipe_right",
            R.string.queue_swipe_right,
            R.string.queue_swipe_subtitle,
            R.string.settings_section_music_player,
            availability = SettingAvailability.NotOnTv,
        )
    val adaptiveSize =
        entry(
            "adaptive_size",
            R.string.player_adaptive_size_title,
            R.string.player_adaptive_size_subtitle,
            R.string.settings_section_video_player,
        )
    val fullscreenZoomToFill =
        entry(
            "fullscreen_zoom_to_fill",
            R.string.player_fullscreen_zoom_to_fill_title,
            R.string.player_fullscreen_zoom_to_fill_subtitle,
            R.string.settings_section_video_player,
        )
    val scrollAway =
        entry(
            "scroll_away",
            R.string.player_scroll_away_title,
            R.string.player_scroll_away_subtitle,
            R.string.settings_section_video_player,
        )
    val ambientMode =
        entry(
            "ambient_mode",
            R.string.player_settings_ambient_mode,
            R.string.player_settings_ambient_mode_subtitle,
            R.string.settings_section_video_player,
        )
    val ambientGlow =
        entry(
            "ambient_glow",
            R.string.player_settings_ambient_glow,
            R.string.player_settings_ambient_glow_subtitle,
            R.string.settings_section_video_player,
        )
    val immersiveGlow =
        entry(
            "immersive_glow",
            R.string.player_settings_immersive_glow,
            R.string.player_settings_immersive_glow_subtitle,
            R.string.settings_section_video_player,
        )
    val immersiveScrollDim =
        entry(
            "immersive_scroll_dim",
            R.string.player_settings_immersive_scroll_dim,
            R.string.player_settings_immersive_scroll_dim_subtitle,
            R.string.settings_section_video_player,
        )
    val glowInBatterySaver =
        entry(
            "glow_battery_saver",
            R.string.player_settings_glow_battery_saver,
            R.string.player_settings_glow_battery_saver_subtitle,
            R.string.settings_section_video_player,
        )
    val ambientMotion =
        entry(
            "ambient_motion",
            R.string.player_settings_ambient_motion,
            R.string.player_settings_ambient_motion_subtitle,
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
            availability = SettingAvailability.NotOnTv,
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
        entry(
            "mini_bar_swipe_left",
            R.string.mini_bar_swipe_left,
            R.string.mini_bar_swipe_subtitle,
            R.string.settings_section_mini_player,
            availability = SettingAvailability.NotOnTv,
        )
    val miniBarSwipeRight =
        entry(
            "mini_bar_swipe_right",
            R.string.mini_bar_swipe_right,
            R.string.mini_bar_swipe_subtitle,
            R.string.settings_section_mini_player,
            availability = SettingAvailability.NotOnTv,
        )

    val lyricsAnimation =
        entry("lyrics_animation", R.string.lyrics_animation, R.string.lyrics_animation_summary, R.string.settings_section_lyrics)
    val lyricsGlow =
        entry("lyrics_glow", R.string.lyrics_glow, R.string.lyrics_glow_summary, R.string.settings_section_lyrics)
    val lyricsBlurOtherLines =
        entry(
            "lyrics_blur_other_lines",
            R.string.lyrics_blur_other_lines,
            R.string.lyrics_blur_other_lines_summary,
            R.string.settings_section_lyrics,
        )
    val lyricsBackground =
        entry("lyrics_background", R.string.lyrics_background, section = R.string.settings_section_lyrics)
    val lyricsAlignment =
        entry("lyrics_alignment", R.string.lyrics_alignment, section = R.string.settings_section_lyrics)

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
            lyricsOnCover,
            hideMusicArtwork,
            animatedArtwork,
            animatedArtworkWifiOnly,
            artworkControlColors,
            plainControlColors,
            splitTransport,
            sleepTimerButton,
            miniCoverShape,
            miniCoverMotion,
            miniCoverShapes,
            miniCoverPausedShape,
            miniBarProgress,
            miniCoverRing,
            miniBackground,
            miniButtons,
            miniCoverTap,
            miniLongPress,
            queueSwipeLeft,
            queueSwipeRight,
            adaptiveSize,
            fullscreenZoomToFill,
            scrollAway,
            ambientMode,
            ambientGlow,
            immersiveGlow,
            immersiveScrollDim,
            glowInBatterySaver,
            ambientMotion,
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
            lyricsAnimation,
            lyricsGlow,
            lyricsBlurOtherLines,
            lyricsBackground,
            lyricsAlignment,
        )
}
