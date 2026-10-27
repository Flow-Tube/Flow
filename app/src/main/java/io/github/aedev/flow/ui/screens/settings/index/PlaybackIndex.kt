package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingAvailability
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination

internal object PlaybackIndex {
    private val page = SettingsDestination.PLAYBACK

    val backgroundPlay =
        SettingEntry(
            key = "playback.background_play",
            title = R.string.player_settings_background_play,
            summary = R.string.player_settings_background_play_subtitle,
            section = R.string.playback_header,
            destination = page,
        )
    val startPaused =
        SettingEntry(
            key = "playback.start_paused",
            title = R.string.player_settings_start_paused,
            summary = R.string.player_settings_start_paused_subtitle,
            keywords = R.string.settings_keywords_autoplay,
            section = R.string.playback_header,
            destination = page,
        )
    val openFullscreen =
        SettingEntry(
            key = "playback.open_fullscreen",
            title = R.string.player_settings_open_fullscreen,
            summary = R.string.player_settings_open_fullscreen_subtitle,
            section = R.string.playback_header,
            destination = page,
        )
    val tiltFullscreen =
        SettingEntry(
            key = "playback.tilt_fullscreen",
            title = R.string.player_settings_tilt_fullscreen,
            summary = R.string.player_settings_tilt_fullscreen_subtitle,
            keywords = R.string.settings_keywords_tilt_fullscreen,
            section = R.string.playback_header,
            destination = page,
            availability = SettingAvailability.NotOnTv,
        )
    val autoplay =
        SettingEntry(
            key = "playback.autoplay",
            title = R.string.player_settings_autoplay,
            summary = R.string.player_settings_autoplay_subtitle,
            keywords = R.string.settings_keywords_autoplay,
            section = R.string.playback_header,
            destination = page,
        )
    val queueAutoplay =
        SettingEntry(
            key = "playback.queue_autoplay",
            title = R.string.player_settings_queue_autoplay,
            summary = R.string.player_settings_queue_autoplay_subtitle,
            keywords = R.string.settings_keywords_autoplay,
            section = R.string.playback_header,
            destination = page,
        )
    val autoplayCountdown =
        SettingEntry(
            key = "playback.autoplay_countdown",
            title = R.string.player_settings_autoplay_countdown_title,
            keywords = R.string.settings_keywords_autoplay,
            section = R.string.playback_header,
            destination = page,
        )
    val loopAll =
        SettingEntry(
            key = "playback.loop_all",
            title = R.string.global_loop,
            summary = R.string.global_loop_subtitle,
            section = R.string.playback_header,
            destination = page,
        )
    val skipSilence =
        SettingEntry(
            key = "playback.skip_silence",
            title = R.string.player_settings_skip_silence,
            summary = R.string.player_settings_skip_silence_subtitle,
            section = R.string.playback_header,
            destination = page,
        )
    val playDuringCalls =
        SettingEntry(
            key = "playback.play_during_calls",
            title = R.string.player_settings_play_during_calls,
            summary = R.string.player_settings_play_during_calls_subtitle,
            section = R.string.playback_header,
            destination = page,
        )
    val rememberSpeed =
        SettingEntry(
            key = "playback.remember_speed",
            title = R.string.player_settings_remember_speed,
            summary = R.string.player_settings_remember_speed_subtitle,
            section = R.string.settings_section_speed,
            destination = page,
        )
    val musicNormalSpeed =
        SettingEntry(
            key = "playback.music_normal_speed",
            title = R.string.settings_music_normal_speed_title,
            summary = R.string.settings_music_normal_speed_summary,
            section = R.string.settings_section_speed,
            destination = page,
        )
    val speedPerChannel =
        SettingEntry(
            key = "playback.speed_per_channel",
            title = R.string.settings_speed_per_channel_title,
            summary = R.string.settings_speed_per_channel_summary,
            section = R.string.settings_section_speed,
            destination = page,
        )
    val customSpeeds =
        SettingEntry(
            key = "playback.custom_speeds",
            title = R.string.player_settings_custom_speeds_title,
            summary = R.string.player_settings_custom_speeds_subtitle,
            section = R.string.settings_section_speed,
            destination = page,
        )
    val speedPresets =
        SettingEntry(
            key = "playback.speed_presets",
            title = R.string.player_settings_custom_speeds_header,
            section = R.string.settings_section_speed,
            revealVia = "playback.custom_speeds",
            destination = page,
        )
    val speedSlider =
        SettingEntry(
            key = "playback.speed_slider",
            title = R.string.player_settings_speed_slider_title,
            summary = R.string.player_settings_speed_slider_subtitle,
            section = R.string.settings_section_speed,
            destination = page,
        )
    val longPressSpeed =
        SettingEntry(
            key = "playback.long_press_speed",
            title = R.string.player_appearance_long_press_speed_title,
            keywords = R.string.settings_keywords_gestures,
            section = R.string.settings_section_speed,
            destination = page,
        )
    val normalizeVolume =
        SettingEntry(
            key = "playback.normalize_volume",
            title = R.string.player_settings_normalize_volume,
            summary = R.string.player_settings_normalize_volume_summary,
            section = R.string.eq_settings_section,
            destination = page,
        )
    val castButton =
        SettingEntry(
            key = "playback.cast_button",
            title = R.string.player_settings_overlay_cast,
            summary = R.string.player_settings_overlay_cast_subtitle,
            section = R.string.settings_section_player_buttons,
            destination = page,
        )
    val captionsButton =
        SettingEntry(
            key = "playback.captions_button",
            title = R.string.player_settings_overlay_cc,
            summary = R.string.player_settings_overlay_cc_subtitle,
            keywords = R.string.settings_keywords_captions,
            section = R.string.settings_section_player_buttons,
            destination = page,
        )
    val pipButton =
        SettingEntry(
            key = "playback.pip_button",
            title = R.string.player_settings_overlay_pip,
            summary = R.string.player_settings_overlay_pip_subtitle,
            section = R.string.settings_section_player_buttons,
            destination = page,
        )
    val autoplayButton =
        SettingEntry(
            key = "playback.autoplay_button",
            title = R.string.player_settings_overlay_autoplay,
            summary = R.string.player_settings_overlay_autoplay_subtitle,
            section = R.string.settings_section_player_buttons,
            destination = page,
        )
    val sleepTimerButton =
        SettingEntry(
            key = "playback.sleep_timer_button",
            title = R.string.player_settings_overlay_sleep_timer,
            summary = R.string.player_settings_overlay_sleep_timer_subtitle,
            section = R.string.settings_section_player_buttons,
            destination = page,
        )
    val lockButton =
        SettingEntry(
            key = "playback.lock_button",
            title = R.string.player_settings_overlay_lock_mode,
            summary = R.string.player_settings_overlay_lock_mode_subtitle,
            section = R.string.settings_section_player_buttons,
            destination = page,
        )
    val speedIndicator =
        SettingEntry(
            key = "playback.speed_indicator",
            title = R.string.player_settings_overlay_speed_indicator,
            summary = R.string.player_settings_overlay_speed_indicator_subtitle,
            section = R.string.settings_section_player_buttons,
            destination = page,
        )
    val commentsButton =
        SettingEntry(
            key = "playback.comments_button",
            title = R.string.player_settings_overlay_comments,
            summary = R.string.player_settings_overlay_comments_subtitle,
            section = R.string.settings_section_player_buttons,
            destination = page,
        )
    val sponsorBlockButton =
        SettingEntry(
            key = "playback.sponsorblock_button",
            title = R.string.player_settings_overlay_sponsorblock,
            summary = R.string.player_settings_overlay_sponsorblock_subtitle,
            section = R.string.settings_section_player_buttons,
            destination = page,
        )
    val nextPrevButtons =
        SettingEntry(
            key = "playback.next_prev_buttons",
            title = R.string.player_settings_overlay_next_prev,
            summary = R.string.player_settings_overlay_next_prev_subtitle,
            keywords = R.string.settings_keywords_next_prev,
            section = R.string.settings_section_player_buttons,
            destination = page,
        )
    val autoPip =
        SettingEntry(
            key = "playback.auto_pip",
            title = R.string.player_settings_auto_pip_title,
            summary = R.string.player_settings_auto_pip_subtitle,
            section = R.string.settings_section_pip,
            destination = page,
        )
    val continueWatchingMiniPlayer =
        SettingEntry(
            key = "playback.continue_watching_mini_player",
            title = R.string.player_settings_mini_player_continue_watching_title,
            summary = R.string.player_settings_mini_player_continue_watching_subtitle,
            section = R.string.settings_section_pip,
            destination = page,
        )
    val restoreMusicMiniPlayer =
        SettingEntry(
            key = "playback.restore_music_mini_player",
            title = R.string.content_settings_restored_music_mini_player_title,
            summary = R.string.content_settings_restored_music_mini_player_subtitle,
            section = R.string.settings_section_pip,
            destination = page,
        )
    val openMusicPlayerOnPlay =
        SettingEntry(
            key = "playback.open_music_player_on_play",
            title = R.string.settings_open_music_player_on_play_title,
            summary = R.string.settings_open_music_player_on_play_summary,
            section = R.string.settings_section_pip,
            destination = page,
        )
    val audioLanguage =
        SettingEntry(
            key = "playback.audio_language",
            title = R.string.player_settings_audio_language,
            section = R.string.settings_section_language_captions,
            destination = page,
        )
    val subtitleLanguage =
        SettingEntry(
            key = "playback.subtitle_language",
            title = R.string.player_settings_subtitle_language,
            keywords = R.string.settings_keywords_captions,
            section = R.string.settings_section_language_captions,
            destination = page,
        )
    val autoCaptions =
        SettingEntry(
            key = "playback.auto_captions",
            title = R.string.player_settings_auto_enable_subtitles,
            summary = R.string.player_settings_auto_enable_subtitles_subtitle,
            keywords = R.string.settings_keywords_captions,
            section = R.string.settings_section_language_captions,
            destination = page,
        )
    val relatedVideos =
        SettingEntry(
            key = "playback.related_videos",
            title = R.string.settings_show_related_videos_title,
            summary = R.string.settings_show_related_videos_subtitle,
            section = R.string.settings_section_watch_page,
            destination = page,
        )
    val relatedCardStyle =
        SettingEntry(
            key = "playback.related_card_style",
            title = R.string.content_settings_related_card_style_title,
            summary = R.string.content_settings_related_card_style_subtitle,
            section = R.string.settings_section_watch_page,
            destination = page,
        )
    val titleLines =
        SettingEntry(
            key = "playback.title_lines",
            title = R.string.content_settings_video_title_lines_title,
            summary = R.string.content_settings_video_title_lines_subtitle,
            section = R.string.settings_section_watch_page,
            destination = page,
        )
    val comments =
        SettingEntry(
            key = "playback.comments",
            title = R.string.content_settings_comments_enabled_title,
            summary = R.string.content_settings_comments_enabled_subtitle,
            section = R.string.settings_section_watch_page,
            destination = page,
        )
    val commentsPreview =
        SettingEntry(
            key = "playback.comments_preview",
            title = R.string.content_settings_comments_preview_title,
            summary = R.string.content_settings_comments_preview_subtitle,
            section = R.string.settings_section_watch_page,
            destination = page,
        )
    val liveChat =
        SettingEntry(
            key = "playback.live_chat",
            title = R.string.content_settings_live_chat_enabled_title,
            summary = R.string.content_settings_live_chat_enabled_subtitle,
            section = R.string.settings_section_watch_page,
            destination = page,
        )
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
    val endlessRadio =
        SettingEntry(
            key = "playback.endless_radio",
            title = R.string.music_endless_radio_title,
            summary = R.string.music_endless_radio_desc,
            section = R.string.settings_section_music,
            destination = page,
        )
    val pauseMusicWhenMuted =
        SettingEntry(
            key = "playback.pause_music_when_muted",
            title = R.string.settings_pause_music_muted_title,
            summary = R.string.settings_pause_music_muted_summary,
            section = R.string.settings_section_music,
            destination = page,
        )
    val musicResume =
        SettingEntry(
            key = "playback.music_resume",
            title = R.string.settings_music_resume_title,
            summary = R.string.settings_music_resume_summary,
            section = R.string.settings_section_music,
            destination = page,
        )
    val resumeFromHeadphones =
        SettingEntry(
            key = "playback.resume_from_headphones",
            title = R.string.settings_resume_from_headphones_title,
            summary = R.string.settings_resume_from_headphones_summary,
            section = R.string.settings_section_music,
            destination = page,
        )
    val musicSideSwipes =
        SettingEntry(
            key = "playback.music_side_swipes",
            title = R.string.settings_music_side_swipes_title,
            summary = R.string.settings_music_side_swipes_summary,
            section = R.string.settings_section_music,
            destination = page,
        )
    val musicVideoSwitch =
        SettingEntry(
            key = "playback.music_video_switch",
            title = R.string.settings_music_video_switch_title,
            summary = R.string.settings_music_video_switch_summary,
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
            backgroundPlay,
            startPaused,
            openFullscreen,
            tiltFullscreen,
            autoplay,
            queueAutoplay,
            autoplayCountdown,
            loopAll,
            skipSilence,
            playDuringCalls,
            normalizeVolume,
            rememberSpeed,
            musicNormalSpeed,
            speedPerChannel,
            customSpeeds,
            speedPresets,
            speedSlider,
            longPressSpeed,
        ) +
            PlaybackGesturesIndex.all +
            listOf(
                castButton,
                captionsButton,
                pipButton,
                autoplayButton,
                sleepTimerButton,
                lockButton,
                speedIndicator,
                commentsButton,
                sponsorBlockButton,
                nextPrevButtons,
                autoPip,
                continueWatchingMiniPlayer,
                restoreMusicMiniPlayer,
                openMusicPlayerOnPlay,
                audioLanguage,
                subtitleLanguage,
                autoCaptions,
                relatedVideos,
                relatedCardStyle,
                titleLines,
                comments,
                commentsPreview,
                liveChat,
                disableShortsPlayer,
                shortsPlayerPrompt,
                shortsPlaybackMode,
                shortsBackgroundPlay,
                shortsPip,
                shortsContinueIntoFeed,
                endlessRadio,
                pauseMusicWhenMuted,
                musicResume,
                resumeFromHeadphones,
                musicSideSwipes,
                musicVideoSwitch,
                lyricsProviders,
            )
}
