package io.github.aedev.flow.data.local

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import io.github.aedev.flow.applock.AppLockSettings
import io.github.aedev.flow.applock.DEFAULT_APP_LOCK_DELAY_MINUTES
import io.github.aedev.flow.data.model.SponsorBlockCategories
import io.github.aedev.flow.data.playlist.PlaylistListOrder
import io.github.aedev.flow.data.profile.StoreScope
import io.github.aedev.flow.data.video.downloader.work.RetagResult
import io.github.aedev.flow.data.video.storage.DownloadLocation
import io.github.aedev.flow.network.AppProxyConfig
import io.github.aedev.flow.network.AppProxyType
import io.github.aedev.flow.notification.UpcomingReminder
import io.github.aedev.flow.player.stream.CaptionTrackResolver
import io.github.aedev.flow.ui.components.videoplayer.subtitle.SubtitleEdgeType
import io.github.aedev.flow.ui.components.videoplayer.subtitle.SubtitleStyle
import io.github.aedev.flow.utils.AppLanguageManager
import io.github.aedev.flow.utils.DateContextMode
import io.github.aedev.flow.utils.DateDisplayMode
import io.github.aedev.flow.utils.DateFormatStyle
import io.github.aedev.flow.utils.NetworkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun resolveMigratedHideWatchedPreference(
    splitValue: Boolean?,
    legacyValue: Boolean?,
): Boolean = splitValue ?: legacyValue ?: false

internal val Context.playerPreferencesDataStore: DataStore<Preferences> by safePreferencesDataStore(
    name = "player_preferences",
    scope = StoreScope.PROFILE_SETTINGS,
)

const val DEEP_FLOW_NEVER_EXPIRES_HOURS = 0
private const val PLAYLIST_SORT_SEPARATOR = "|"
private const val CHANNEL_SPEED_SEPARATOR = "|"
const val CONTENT_LANGUAGE_FOLLOW_APP = "app"
const val DEFAULT_PORTRAIT_SEEKBAR_PADDING_DP = 16
const val MAX_PORTRAIT_SEEKBAR_PADDING_DP = 64
const val DEFAULT_FULLSCREEN_SEEKBAR_PADDING_DP = 48
const val MAX_FULLSCREEN_SEEKBAR_PADDING_DP = 120
const val DEFAULT_CONCURRENT_DOWNLOADS = 3
const val MAX_CONCURRENT_DOWNLOADS = 5

/** On until turned off, matching NewPipe's default and the Background button (#1298). */
const val DEFAULT_BACKGROUND_PLAY_ENABLED = true

/** On until turned off: a swipe from recents has kept playback going since #1321. */
const val DEFAULT_KEEP_PLAYING_AFTER_SWIPE = true

val DEFAULT_NAV_TAB_ORDER = listOf(0, 1, 2, 3, 4, 5, 6, 7)

private const val MAX_UNPLAYABLE_VIDEO_IDS = 1_000

/**
 * Preferences that never leave the device in a backup: the proxy password, SponsorBlock's
 * submission id, which is a private identity for the user's votes and segments, and the app lock,
 * which a restored backup must not be able to turn on or off.
 */
private val BackupExcludedKeys =
    setOf(
        "proxy_password",
        "sb_user_id",
        "app_lock_enabled",
        "app_lock_delay_minutes",
        "app_lock_hide_in_recents",
    )

private val ReminderJson = Json { ignoreUnknownKeys = true }

private fun String?.decodeUnplayableIds(): Set<String> =
    if (isNullOrBlank()) emptySet() else splitToSequence('\n').filter { it.isNotBlank() }.toCollection(LinkedHashSet())

class PlayerPreferences(
    context: Context,
) {
    private val context: Context = context.applicationContext

    private object Keys {
        val BACKGROUND_PLAY_ENABLED = booleanPreferencesKey("background_play_enabled")
        val KEEP_PLAYING_AFTER_SWIPE = booleanPreferencesKey("keep_playing_after_swipe")
        val AUTOPLAY_ENABLED = booleanPreferencesKey("autoplay_enabled")
        val QUEUE_AUTOPLAY_ENABLED = booleanPreferencesKey("queue_autoplay_enabled")
        val START_VIDEOS_PAUSED = booleanPreferencesKey("start_videos_paused")
        val OPEN_VIDEOS_FULLSCREEN = booleanPreferencesKey("open_videos_fullscreen")
        val MUSIC_ENDLESS_RADIO_ENABLED = booleanPreferencesKey("music_endless_radio_enabled")
        val AUTOPLAY_COUNTDOWN_SECONDS = intPreferencesKey("autoplay_countdown_seconds")
        val VIDEO_LOOP_ENABLED = booleanPreferencesKey("video_loop_enabled")
        val VIDEO_AMBIENT_MODE_ENABLED = booleanPreferencesKey("video_ambient_mode_enabled")
        val PREFERRED_SUBTITLE_LANGUAGE = stringPreferencesKey("preferred_subtitle_language")
        val TRANSCRIPT_LANGUAGE = stringPreferencesKey("transcript_language")
        val SUBTITLE_FONT_SIZE = floatPreferencesKey("subtitle_font_size")
        val SUBTITLE_TEXT_COLOR = intPreferencesKey("subtitle_text_color")
        val SUBTITLE_BACKGROUND_COLOR = intPreferencesKey("subtitle_background_color")
        val SUBTITLE_BOLD = booleanPreferencesKey("subtitle_bold")
        val SUBTITLE_WINDOW_COLOR = intPreferencesKey("subtitle_window_color")
        val SUBTITLE_EDGE_TYPE = stringPreferencesKey("subtitle_edge_type")
        val SUBTITLE_EDGE_COLOR = intPreferencesKey("subtitle_edge_color")
        val SUBTITLE_BOTTOM_PADDING = floatPreferencesKey("subtitle_bottom_padding")
        val SUBTITLE_FULLSCREEN_BOTTOM_PADDING = floatPreferencesKey("subtitle_fullscreen_bottom_padding")
        val SUBTITLE_VERTICAL_FULLSCREEN_BOTTOM_PADDING = floatPreferencesKey("subtitle_vertical_fullscreen_bottom_padding")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val SLEEP_TIMER_CLOSE_APP_ON_EXPIRY = booleanPreferencesKey("sleep_timer_close_app_on_expiry")
        val ORIGINAL_TITLES = booleanPreferencesKey("original_titles")
        val MUSIC_LOUDNESS_NORMALIZATION_ENABLED = booleanPreferencesKey("music_loudness_normalization_enabled")
        val SKIP_SILENCE_ENABLED = booleanPreferencesKey("skip_silence_enabled")
        val SPONSOR_BLOCK_ENABLED = booleanPreferencesKey("sponsor_block_enabled")
        val AUTO_PIP_ENABLED = booleanPreferencesKey("auto_pip_enabled")
        val MANUAL_PIP_BUTTON_ENABLED = booleanPreferencesKey("manual_pip_button_enabled")
        val VIDEO_VOLUME_NORMALIZATION = booleanPreferencesKey("video_volume_normalization")

        // Audio track preference
        val PREFERRED_AUDIO_LANGUAGE = stringPreferencesKey("preferred_audio_language")

        // Subtitle preferences
        val AUTO_ENABLE_SUBTITLES = booleanPreferencesKey("auto_enable_subtitles")

        // UI preferences
        val GRID_ITEM_SIZE = stringPreferencesKey("grid_item_size")
        val SLIDER_STYLE = stringPreferencesKey("slider_style")
        val MUSIC_PLAYER_BACKGROUND_STYLE = stringPreferencesKey("music_player_background_style")
        val HIDE_MUSIC_PLAYER_ARTWORK = booleanPreferencesKey("hide_music_player_artwork")
        val MUSIC_ARTWORK_CONTROL_COLORS = booleanPreferencesKey("music_artwork_control_colors")
        val VIDEO_ARTWORK_COLORS = booleanPreferencesKey("video_artwork_colors")
        val MUSIC_PLAIN_CONTROL_COLORS = stringPreferencesKey("music_plain_control_colors")
        val MUSIC_SLEEP_TIMER_BUTTON = booleanPreferencesKey("music_sleep_timer_button")
        val MUSIC_PLAYER_FOLLOW_LIGHT_THEME = booleanPreferencesKey("music_player_follow_light_theme")
        val SHORTS_PLAYER_UI_MODE = stringPreferencesKey("shorts_player_ui_mode")
        val GESTURE_OVERLAY_STYLE = stringPreferencesKey("gesture_overlay_style")
        val GROUPED_QUALITY_SELECTOR_ENABLED = booleanPreferencesKey("grouped_quality_selector_enabled")
        val PAID_PROMOTION_LABEL = booleanPreferencesKey("paid_promotion_label")
        val SHORTS_CONTENT_ENABLED = booleanPreferencesKey("shorts_content_enabled")
        val CHANNEL_SHORTS_TAB_WHEN_HIDDEN = booleanPreferencesKey("channel_shorts_tab_when_hidden")
        val NOTES_ENABLED = booleanPreferencesKey("notes_enabled")
        val CHANNEL_NOTES_ENABLED = booleanPreferencesKey("channel_notes_enabled")
        val VIDEO_NOTES_ENABLED = booleanPreferencesKey("video_notes_enabled")
        val SHORTS_SHELF_ENABLED = booleanPreferencesKey("shorts_shelf_enabled")
        val LIBRARY_SHELF_PREVIEWS_ENABLED = booleanPreferencesKey("library_shelf_previews_enabled")
        val SEPARATE_PLAYLIST_KINDS = booleanPreferencesKey("separate_playlist_kinds")
        val PLAYLIST_LIST_ORDER = stringPreferencesKey("playlist_list_order")
        val PLAYLISTS_COMPACT_LAYOUT = booleanPreferencesKey("playlists_compact_layout")
        val PLAYLISTS_SHOW_MUSIC = booleanPreferencesKey("playlists_show_music")
        val HOME_SHORTS_SHELF_ENABLED = booleanPreferencesKey("home_shorts_shelf_enabled")
        val HOME_SUBSCRIPTIONS_ENABLED = booleanPreferencesKey("home_subscriptions_enabled")
        val HOME_SNAPSHOT_RECORDING = booleanPreferencesKey("home_snapshot_recording")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val SUBSCRIPTION_COLLABORATIONS_ENABLED = booleanPreferencesKey("subscription_collaborations_enabled")
        val NOTES_SORT = stringPreferencesKey("notes_sort")
        val SHOW_WATCH_PROGRESS = booleanPreferencesKey("show_watch_progress")
        val WATCH_HISTORY_PAUSED = booleanPreferencesKey("watch_history_paused")
        val HOME_NAVIGATION_ENABLED = booleanPreferencesKey("home_navigation_enabled")
        val SHORTS_NAVIGATION_ENABLED = booleanPreferencesKey("shorts_navigation_enabled")
        val BOTTOM_NAV_HIDE_ON_SCROLL = booleanPreferencesKey("bottom_nav_hide_on_scroll")
        val SEARCH_SHOWS_NAV_BAR = booleanPreferencesKey("search_shows_nav_bar")
        val INNER_PAGES_SHOW_NAV_BAR = booleanPreferencesKey("inner_pages_show_nav_bar")
        val MUSIC_NAVIGATION_ENABLED = booleanPreferencesKey("music_navigation_enabled")
        val SEARCH_NAV_TAB_ENABLED = booleanPreferencesKey("search_nav_tab_enabled")
        val CATEGORIES_NAV_TAB_ENABLED = booleanPreferencesKey("categories_nav_tab_enabled")
        val DOWNLOADS_NAV_TAB_ENABLED = booleanPreferencesKey("downloads_nav_tab_enabled")
        val PREFERRED_LYRICS_PROVIDER = stringPreferencesKey("preferred_lyrics_provider")
        val LYRICS_PROVIDER_ORDER = stringPreferencesKey("lyrics_provider_order")
        val LYRICS_TEXT_ALIGN = stringPreferencesKey("lyrics_text_align")
        val LYRICS_SHOW_TRANSLATION = booleanPreferencesKey("lyrics_show_translation")
        val LYRICS_SHOW_ROMANIZATION = booleanPreferencesKey("lyrics_show_romanization")
        val LYRICS_ON_LOCK_SCREEN = booleanPreferencesKey("lyrics_on_lock_screen")
        val LYRICS_ON_DEMAND = booleanPreferencesKey("lyrics_on_demand")
        val LYRICS_ON_LOCK_SCREEN_DELAY_MS = intPreferencesKey("lyrics_on_lock_screen_delay_ms")
        val LYRICS_SPONSORBLOCK_TIMING = booleanPreferencesKey("lyrics_sponsorblock_timing")
        val LYRICS_AUTO_ROMANIZE = booleanPreferencesKey("lyrics_auto_romanize")
        val LYRICS_ROMANIZATION_ONLY = booleanPreferencesKey("lyrics_romanization_only")
        val LYRICS_KEEP_SCREEN_ON = booleanPreferencesKey("lyrics_keep_screen_on")
        val LYRICS_BLUR_OTHER_LINES = booleanPreferencesKey("lyrics_blur_other_lines")
        val LYRICS_BACKGROUND = stringPreferencesKey("lyrics_background")
        val LYRICS_PROVIDER_ENABLED_BETTERLYRICS = booleanPreferencesKey("lyrics_provider_enabled_betterlyrics")
        val LYRICS_PROVIDER_ENABLED_SIMPMUSIC = booleanPreferencesKey("lyrics_provider_enabled_simpmusic")
        val LYRICS_PROVIDER_ENABLED_LYRICSPLUS = booleanPreferencesKey("lyrics_provider_enabled_lyricsplus")
        val LYRICS_PROVIDER_ENABLED_LRCLIB = booleanPreferencesKey("lyrics_provider_enabled_lrclib")
        val LYRICS_PROVIDER_ENABLED_YOUTUBE = booleanPreferencesKey("lyrics_provider_enabled_youtube")
        val LYRICS_PROVIDER_ENABLED_KUGOU = booleanPreferencesKey("lyrics_provider_enabled_kugou")
        val LYRICS_PROVIDER_ENABLED_PAXSENIX = booleanPreferencesKey("lyrics_provider_enabled_paxsenix")
        val LYRICS_PROVIDER_ENABLED_YOUTUBESUBTITLE = booleanPreferencesKey("lyrics_provider_enabled_youtubesubtitle")
        val LYRICS_PROVIDER_ENABLED_LRCRED = booleanPreferencesKey("lyrics_provider_enabled_lrcred")
        val LYRICS_PROVIDER_ENABLED_UNISON = booleanPreferencesKey("lyrics_provider_enabled_unison")
        val LYRICS_PROVIDER_ENABLED_NETEASE = booleanPreferencesKey("lyrics_provider_enabled_netease")
        val LYRICS_PROVIDER_ENABLED_AMLL = booleanPreferencesKey("lyrics_provider_enabled_amll")
        val CONTINUE_WATCHING_ENABLED = booleanPreferencesKey("continue_watching_enabled")
        val SHOW_RELATED_VIDEOS = booleanPreferencesKey("show_related_videos")
        val HOME_VIEW_MODE = stringPreferencesKey("home_view_mode")
        val HOME_FEED_COLUMNS = stringPreferencesKey("home_feed_columns")
        val HOME_FEED_ENABLED = booleanPreferencesKey("home_feed_enabled")
        val REFRESH_HOME_ON_RESELECT = booleanPreferencesKey("refresh_home_on_reselect")
        val RELATED_CARD_STYLE = stringPreferencesKey("related_card_style")

        // SponsorBlock per-category action keys
        val SB_ACTION_SPONSOR = stringPreferencesKey("sb_action_sponsor")
        val SB_ACTION_INTRO = stringPreferencesKey("sb_action_intro")
        val SB_ACTION_OUTRO = stringPreferencesKey("sb_action_outro")
        val SB_ACTION_SELFPROMO = stringPreferencesKey("sb_action_selfpromo")
        val SB_ACTION_INTERACTION = stringPreferencesKey("sb_action_interaction")
        val SB_ACTION_MUSIC_OFFTOPIC = stringPreferencesKey("sb_action_music_offtopic")
        val SB_ACTION_FILLER = stringPreferencesKey("sb_action_filler")
        val SB_ACTION_PREVIEW = stringPreferencesKey("sb_action_preview")
        val SB_ACTION_EXCLUSIVE_ACCESS = stringPreferencesKey("sb_action_exclusive_access")
        val SB_ACTION_HOOK = stringPreferencesKey("sb_action_hook")
        val SB_ACTION_HIGHLIGHT = stringPreferencesKey("sb_action_poi_highlight")

        // SponsorBlock per-category color keys
        val SB_COLOR_SPONSOR = intPreferencesKey("sb_color_sponsor")
        val SB_COLOR_INTRO = intPreferencesKey("sb_color_intro")
        val SB_COLOR_OUTRO = intPreferencesKey("sb_color_outro")
        val SB_COLOR_SELFPROMO = intPreferencesKey("sb_color_selfpromo")
        val SB_COLOR_INTERACTION = intPreferencesKey("sb_color_interaction")
        val SB_COLOR_MUSIC_OFFTOPIC = intPreferencesKey("sb_color_music_offtopic")
        val SB_COLOR_FILLER = intPreferencesKey("sb_color_filler")
        val SB_COLOR_PREVIEW = intPreferencesKey("sb_color_preview")
        val SB_COLOR_EXCLUSIVE_ACCESS = intPreferencesKey("sb_color_exclusive_access")
        val SB_COLOR_HOOK = intPreferencesKey("sb_color_hook")
        val SB_COLOR_HIGHLIGHT = intPreferencesKey("sb_color_poi_highlight")

        // SponsorBlock submit
        val SB_SUBMIT_ENABLED = booleanPreferencesKey("sb_submit_enabled")
        val SB_USER_ID = stringPreferencesKey("sb_user_id")
        val SB_SKIP_NOTICE_ENABLED = booleanPreferencesKey("sb_skip_notice_enabled")
        val SB_SKIP_BUTTON_STYLE = stringPreferencesKey("sb_skip_button_style")

        // DeArrow
        val DEARROW_ENABLED = booleanPreferencesKey("dearrow_enabled")
        val DEARROW_BADGE_ENABLED = booleanPreferencesKey("dearrow_badge_enabled")
        val DEARROW_RANDOM_FRAME = booleanPreferencesKey("dearrow_random_frame")

        // Notification preferences
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val NOTIF_NEW_VIDEOS_ENABLED = booleanPreferencesKey("notif_new_videos_enabled")
        val NOTIF_SHORTS_ENABLED = booleanPreferencesKey("notif_shorts_enabled")
        val NOTIF_REMINDERS_ENABLED = booleanPreferencesKey("notif_reminders_enabled")
        val NOTIF_GENERAL_ENABLED = booleanPreferencesKey("notif_general_enabled")

        // Fullscreen Player
        val ADAPTIVE_PLAYER_SIZE_ENABLED = booleanPreferencesKey("adaptive_player_size_enabled")
        val SCRUB_PREVIEW_STYLE = stringPreferencesKey("scrub_preview_style")

        // Mini Player Customizations
        val MINI_PLAYER_SCALE = floatPreferencesKey("mini_player_scale")
        val MINI_PLAYER_SHOW_SKIP_CONTROLS = booleanPreferencesKey("mini_player_show_skip_controls")
        val MINI_PLAYER_SHOW_NEXT_PREV_CONTROLS = booleanPreferencesKey("mini_player_show_next_prev_controls")
        val MINI_PLAYER_CONTINUE_WATCHING_ENABLED = booleanPreferencesKey("mini_player_continue_watching_enabled")
        val MINIMIZE_TO_AUDIO_BAR = booleanPreferencesKey("minimize_to_audio_bar")
        val SHOW_RESTORED_MUSIC_MINI_PLAYER = booleanPreferencesKey("show_restored_music_mini_player")
        val OPEN_MUSIC_PLAYER_ON_PLAY = booleanPreferencesKey("open_music_player_on_play")

        // Subscriptions feed view mode
        val SUBS_FULL_WIDTH_VIEW = booleanPreferencesKey("subs_full_width_view")
        val SUBS_SORT_MODE = stringPreferencesKey("subs_sort_mode")
        val SUBS_SELECTED_GROUP = stringPreferencesKey("subs_selected_group")
        val SUBS_REFRESH_ON_STARTUP = booleanPreferencesKey("subs_refresh_on_startup")
        val SUBS_LAST_REFRESH_TIME = longPreferencesKey("subs_last_refresh_time")
        val SUBS_LAST_REFRESHED_COUNT = intPreferencesKey("subs_last_refreshed_count")
        val SUBS_SHOW_CHECKED_VIDEO_COUNT = booleanPreferencesKey("subs_show_checked_video_count")
        val SHOW_CHANNEL_GROUP_BADGES = booleanPreferencesKey("show_channel_group_badges")

        // Navigation tab preferences
        val NAV_TAB_ORDER = stringPreferencesKey("nav_tab_order")
        val DEFAULT_NAV_TAB_INDEX = intPreferencesKey("default_nav_tab_index")

        // Remember playback speed
        val REMEMBER_PLAYBACK_SPEED = booleanPreferencesKey("remember_playback_speed")
        val MUSIC_AT_NORMAL_SPEED = booleanPreferencesKey("music_at_normal_speed")
        val MUSIC_VIDEO_SWITCH = booleanPreferencesKey("music_video_switch")
        val ANIMATED_ARTWORK = booleanPreferencesKey("animated_artwork")
        val MUSIC_REPEAT_MODE = intPreferencesKey("music_repeat_mode")
        val MUSIC_RESUME_LONG_TRACKS = booleanPreferencesKey("music_resume_long_tracks")
        val MUSIC_CROSSFADE_SECONDS = intPreferencesKey("music_crossfade_seconds")
        val MUSIC_CROSSFADE_ENABLED = booleanPreferencesKey("music_crossfade_enabled")
        val MUSIC_CROSSFADE_MS = intPreferencesKey("music_crossfade_ms")
        val MUSIC_CROSSFADE_KEEP_ALBUMS = booleanPreferencesKey("music_crossfade_keep_albums")
        val MUSIC_CROSSFADE_CURVE = stringPreferencesKey("music_crossfade_curve")
        val MUSIC_CROSSFADE_AUTO_ADVANCE = booleanPreferencesKey("music_crossfade_auto_advance")
        val MUSIC_CROSSFADE_MANUAL_SKIP = booleanPreferencesKey("music_crossfade_manual_skip")
        val RECAP_SOURCE = stringPreferencesKey("recap_source")
        val HIDDEN_MUSIC_HOME_SHELVES = stringSetPreferencesKey("hidden_music_home_shelves")
        val HIDDEN_LIBRARY_SECTIONS = stringSetPreferencesKey("hidden_library_sections")
        val SPEED_PER_CHANNEL = booleanPreferencesKey("speed_per_channel")
        val CHANNEL_PLAYBACK_SPEEDS = stringSetPreferencesKey("channel_playback_speeds")

        // Subscription check interval
        val SUBSCRIPTION_CHECK_INTERVAL_MINUTES = intPreferencesKey("subscription_check_interval_minutes")

        // Custom playback speeds
        val CUSTOM_SPEEDS_ENABLED = booleanPreferencesKey("custom_speeds_enabled")
        val CUSTOM_SPEED_PRESETS = stringPreferencesKey("custom_speed_presets")
        val SPEED_SLIDER_ENABLED = booleanPreferencesKey("speed_slider_enabled")

        // Content filtering
        val HIDE_WATCHED_VIDEOS = booleanPreferencesKey("hide_watched_videos")
        val HIDE_WATCHED_HOME_FEED = booleanPreferencesKey("hide_watched_home_feed")
        val HIDE_WATCHED_SUBSCRIPTIONS = booleanPreferencesKey("hide_watched_subscriptions")
        val HIDE_WATCHED_SHORTS = booleanPreferencesKey("hide_watched_shorts")
        val WATCHED_THRESHOLD = stringPreferencesKey("watched_threshold")
        val SHARED_LINK_ACTION = stringPreferencesKey("shared_link_action")
        val DISABLE_SHORTS_PLAYER = booleanPreferencesKey("disable_shorts_player")
        val SHOW_SHORTS_PLAYER_PROMPT = booleanPreferencesKey("show_shorts_player_prompt")
        val SHARE_WITHOUT_TEXT = booleanPreferencesKey("share_without_text")
        val REMOVE_WATCHED_FROM_WATCH_LATER = booleanPreferencesKey("remove_watched_from_watch_later")

        val SHORTS_PIP_ENABLED = booleanPreferencesKey("shorts_pip_enabled")

        // Shorts background playback
        val SHORTS_BACKGROUND_PLAY = booleanPreferencesKey("shorts_background_play")

        // Shorts playback mode: "loop" (default), "auto_next", or "auto_interval"
        val SHORTS_PLAYBACK_MODE = stringPreferencesKey("shorts_playback_mode")
        val SHORTS_AUTO_SCROLL_SECONDS = intPreferencesKey("shorts_auto_scroll_seconds")
        val SHORTS_QUEUE_CONTINUE_INTO_FEED = booleanPreferencesKey("shorts_queue_continue_into_feed")

        // App icon — stores the component suffix of the currently selected launcher icon
        val PLAYLIST_SORT_ORDER = stringPreferencesKey("playlist_sort_order")
        val PLAYLIST_SORT_ORDERS = stringSetPreferencesKey("playlist_sort_orders")

        // Video title display — max lines in the player info section (0 = no limit)
        val VIDEO_TITLE_MAX_LINES = intPreferencesKey("video_title_max_lines")

        // Screen-level view mode toggles
        val SEARCH_IS_GRID_MODE = booleanPreferencesKey("search_is_grid_mode")
        val CHANNEL_IS_GRID_VIEW = booleanPreferencesKey("channel_is_grid_view")
        val CATEGORIES_IS_LIST_VIEW = booleanPreferencesKey("categories_is_list_view")

        // Video card inline like/dislike action buttons
        val VIDEO_CARD_ACTIONS_ENABLED = booleanPreferencesKey("video_card_actions_enabled")

        // Video card mark-as-watched quick actions
        val VIDEO_CARD_MARK_WATCHED_ENABLED = booleanPreferencesKey("video_card_mark_watched_enabled")

        // Show app logo icon in home screen top bar
        val SHOW_APP_LOGO_ICON = booleanPreferencesKey("show_app_logo_icon")

        // Player comments preview
        val COMMENTS_ENABLED = booleanPreferencesKey("comments_enabled")
        val LIVE_CHAT_ENABLED = booleanPreferencesKey("live_chat_enabled")
        val COMMENTS_PREVIEW_ENABLED = booleanPreferencesKey("comments_preview_enabled")
        val TIMED_COMMENTS_FOLLOW = booleanPreferencesKey("timed_comments_follow")

        val SUBSCRIPTION_SHOW_VIDEOS = booleanPreferencesKey("subscription_show_videos")
        val SUBSCRIPTION_SHOW_SHORTS = booleanPreferencesKey("subscription_show_shorts")
        val SUBSCRIPTION_SHOW_LIVE = booleanPreferencesKey("subscription_show_live")
        val SUBSCRIPTION_SHORTS_EXCLUDED_CHANNELS = stringSetPreferencesKey("subscription_shorts_excluded_channels")
        val UPCOMING_VIDEO_REMINDER_IDS = stringSetPreferencesKey("upcoming_video_reminder_ids")
        val UPCOMING_VIDEO_REMINDERS = stringPreferencesKey("upcoming_video_reminders")

        // Newest-first, newline-delimited so the list can be trimmed to a bounded size.
        val HIDE_UNPLAYABLE_SUBSCRIPTIONS = booleanPreferencesKey("hide_unplayable_subscriptions")

        // Deep Flow (Incognito / No-Engine) mode
        val DEEP_FLOW_ACTIVE = booleanPreferencesKey("deep_flow_active")
        val DEEP_FLOW_ACTIVATED_AT = longPreferencesKey("deep_flow_activated_at")
        val DEEP_FLOW_EXPIRE_HOURS = intPreferencesKey("deep_flow_expire_hours")
        val DEEP_FLOW_SAVE_HISTORY = booleanPreferencesKey("deep_flow_save_history")
        val DEEP_FLOW_SCROBBLE = booleanPreferencesKey("deep_flow_scrobble")
        val DEEP_FLOW_ON_SEARCH = booleanPreferencesKey("deep_flow_on_search")

        // Home subscription feed rotation cursor
        val HOME_SUBS_ROTATION_CURSOR = intPreferencesKey("home_subs_rotation_cursor")

        // Return YouTube Dislikes
        val RYTD_ENABLED = booleanPreferencesKey("rytd_enabled")

        // Shorts playback speed: remembered across sessions
        val SHORTS_PLAYBACK_SPEED = floatPreferencesKey("shorts_playback_speed")

        // Date & time display
        val DATE_DISPLAY_MODE = stringPreferencesKey("date_display_mode")
        val DATE_FORMAT_STYLE = stringPreferencesKey("date_format_style")
        val DATE_MODE_LISTS = stringPreferencesKey("date_mode_lists")
        val DATE_MODE_WATCH = stringPreferencesKey("date_mode_watch")
        val DATE_MODE_DESCRIPTION = stringPreferencesKey("date_mode_description")
    }

    /** Keys kept in the device store and shared by every profile; see DevicePreferencesStore.kt. */
    private object DeviceKeys {
        private val names = mutableSetOf<String>()

        private fun <T> device(key: Preferences.Key<T>): Preferences.Key<T> = key.also { names += it.name }

        val DEFAULT_QUALITY_WIFI = device(stringPreferencesKey("default_quality_wifi"))
        val DEFAULT_QUALITY_CELLULAR = device(stringPreferencesKey("default_quality_cellular"))
        val DEFAULT_VIDEO_CODEC = device(stringPreferencesKey("default_video_codec"))
        val FALLBACK_VIDEO_CODEC = device(stringPreferencesKey("fallback_video_codec"))
        val TILT_FULLSCREEN_ROTATION_LOCKED = device(booleanPreferencesKey("tilt_fullscreen_rotation_locked"))
        val RESUME_FROM_HEADPHONES = device(booleanPreferencesKey("resume_from_headphones"))
        val SHOW_CONTROLS_WHILE_LOADING = device(booleanPreferencesKey("show_controls_while_loading"))
        val TRENDING_REGION = device(stringPreferencesKey("trending_region"))
        val APP_LANGUAGE = device(stringPreferencesKey("app_language"))
        val CONTENT_LANGUAGE = device(stringPreferencesKey("content_language"))
        val MUSIC_AUDIO_OFFLOAD_ENABLED = device(booleanPreferencesKey("music_audio_offload_enabled"))

        // Buffer settings
        val MIN_BUFFER_MS = device(intPreferencesKey("min_buffer_ms"))
        val MAX_BUFFER_MS = device(intPreferencesKey("max_buffer_ms"))
        val BUFFER_FOR_PLAYBACK_MS = device(intPreferencesKey("buffer_for_playback_ms"))
        val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = device(intPreferencesKey("buffer_for_playback_after_rebuffer_ms"))

        // Buffer profiles
        val BUFFER_PROFILE = device(stringPreferencesKey("buffer_profile"))

        // Download settings
        val DOWNLOAD_THREADS = device(intPreferencesKey("download_threads"))
        val CONCURRENT_DOWNLOADS = device(intPreferencesKey("concurrent_downloads"))
        val DOWNLOAD_OVER_WIFI_ONLY = device(booleanPreferencesKey("download_over_wifi_only"))
        val DEFAULT_DOWNLOAD_QUALITY = device(stringPreferencesKey("default_download_quality"))
        val DEFAULT_DOWNLOAD_CODEC = device(stringPreferencesKey("default_download_codec"))
        val MUSIC_DOWNLOAD_QUALITY = device(stringPreferencesKey("music_download_quality"))
        val DOWNLOAD_RETAG_RESULT = device(stringPreferencesKey("download_retag_result"))
        val DOWNLOAD_LOCATION = device(stringPreferencesKey("download_location"))
        val MUSIC_DOWNLOAD_LOCATION = device(stringPreferencesKey("music_download_location"))
        val DOWNLOAD_LOCATION_TREE = device(stringPreferencesKey("download_location_tree"))
        val MUSIC_DOWNLOAD_LOCATION_TREE = device(stringPreferencesKey("music_download_location_tree"))

        // Download dialog style + remembered last-used download options (compact dialog)
        val DOWNLOAD_DIALOG_STYLE = device(stringPreferencesKey("download_dialog_style"))
        val DOWNLOAD_ASK_EVERY_TIME = device(booleanPreferencesKey("download_ask_every_time"))
        val DOWNLOADS_STORAGE_CARD = device(booleanPreferencesKey("downloads_storage_card"))
        val DOWNLOADS_CONFIRM_DELETE = device(booleanPreferencesKey("downloads_confirm_delete"))
        val LAST_DOWNLOAD_TYPE = device(stringPreferencesKey("last_download_type"))
        val LAST_DOWNLOAD_HEIGHT = device(intPreferencesKey("last_download_height"))
        val LAST_DOWNLOAD_CODEC = device(stringPreferencesKey("last_download_codec"))
        val LAST_DOWNLOAD_AUDIO_LABEL = device(stringPreferencesKey("last_download_audio_label"))
        val LAST_DOWNLOAD_SUBTITLE_LANGUAGE = device(stringPreferencesKey("last_download_subtitle_language"))
        val DOWNLOAD_SUBTITLE_FILE = device(booleanPreferencesKey("download_subtitle_file"))
        val AUTO_DOWNLOAD_OPENED_VIDEOS = device(stringPreferencesKey("auto_download_opened_videos"))
        val PROXY_ENABLED = device(booleanPreferencesKey("proxy_enabled"))
        val PROXY_TYPE = device(stringPreferencesKey("proxy_type"))
        val PROXY_HOST = device(stringPreferencesKey("proxy_host"))
        val PROXY_PORT = device(intPreferencesKey("proxy_port"))
        val PROXY_USERNAME = device(stringPreferencesKey("proxy_username"))
        val PROXY_PASSWORD = device(stringPreferencesKey("proxy_password"))
        val PROXY_BYPASS_ON_VPN = device(booleanPreferencesKey("proxy_bypass_on_vpn"))
        val SURFACE_READY_TIMEOUT_MS = device(longPreferencesKey("surface_ready_timeout_ms"))

        // Audio track preference
        val MUSIC_AUDIO_QUALITY = device(stringPreferencesKey("music_audio_quality"))

        // Shorts quality preferences
        val SHORTS_QUALITY_WIFI = device(stringPreferencesKey("shorts_quality_wifi"))
        val SHORTS_QUALITY_CELLULAR = device(stringPreferencesKey("shorts_quality_cellular"))
        val THUMBNAIL_QUALITY_WIFI = device(stringPreferencesKey("thumbnail_quality_wifi"))
        val THUMBNAIL_QUALITY_CELLULAR = device(stringPreferencesKey("thumbnail_quality_cellular"))

        // UI preferences
        val PLAYER_HAPTICS_ENABLED = device(booleanPreferencesKey("player_haptics_enabled"))
        val SWIPE_GESTURES_ENABLED = device(booleanPreferencesKey("swipe_gestures_enabled"))
        val BRIGHTNESS_SWIPE_GESTURES_ENABLED = device(booleanPreferencesKey("brightness_swipe_gestures_enabled"))
        val REMEMBER_BRIGHTNESS_ENABLED = device(booleanPreferencesKey("remember_brightness_enabled"))
        val REMEMBERED_BRIGHTNESS_LEVEL = device(floatPreferencesKey("remembered_brightness_level"))
        val VOLUME_SWIPE_GESTURES_ENABLED = device(booleanPreferencesKey("volume_swipe_gestures_enabled"))
        val SEEK_SWIPE_GESTURES_ENABLED = device(booleanPreferencesKey("seek_swipe_gestures_enabled"))
        val MORE_VIDEOS_SWIPE_ENABLED = device(booleanPreferencesKey("more_videos_swipe_enabled"))
        val DOUBLE_TAP_SEEK_SECONDS = device(intPreferencesKey("double_tap_seek_seconds"))
        val DOUBLE_TAP_SEEK_ZONE = device(stringPreferencesKey("double_tap_seek_zone"))

        // Notification preferences
        val NOTIF_DOWNLOADS_ENABLED = device(booleanPreferencesKey("notif_downloads_enabled"))
        val NOTIF_UPDATES_ENABLED = device(booleanPreferencesKey("notif_updates_enabled"))

        // Overlay Controls preferences
        val OVERLAY_CAST_ENABLED = device(booleanPreferencesKey("overlay_cast_enabled"))
        val OVERLAY_CC_ENABLED = device(booleanPreferencesKey("overlay_cc_enabled"))
        val OVERLAY_PIP_ENABLED = device(booleanPreferencesKey("overlay_pip_enabled"))
        val OVERLAY_AUTOPLAY_ENABLED = device(booleanPreferencesKey("overlay_autoplay_enabled"))
        val OVERLAY_SLEEPTIMER_ENABLED = device(booleanPreferencesKey("overlay_sleeptimer_enabled"))
        val OVERLAY_LOCK_MODE_ENABLED = device(booleanPreferencesKey("overlay_lock_mode_enabled"))
        val OVERLAY_SPEED_INDICATOR_ENABLED = device(booleanPreferencesKey("overlay_speed_indicator_enabled"))
        val OVERLAY_COMMENTS_ENABLED = device(booleanPreferencesKey("overlay_comments_enabled"))
        val OVERLAY_SPONSORBLOCK_ENABLED = device(booleanPreferencesKey("overlay_sponsorblock_enabled"))
        val OVERLAY_NEXT_PREV_ENABLED = device(booleanPreferencesKey("overlay_next_prev_enabled"))

        // Fullscreen Player
        val PORTRAIT_SEEKBAR_PADDING_MODE = device(stringPreferencesKey("portrait_seekbar_padding_mode"))
        val PORTRAIT_SEEKBAR_CUSTOM_PADDING_DP = device(intPreferencesKey("portrait_seekbar_custom_padding_dp"))
        val PORTRAIT_IDLE_PROGRESS_BAR = device(stringPreferencesKey("portrait_idle_progress_bar"))
        val FULLSCREEN_SEEKBAR_PADDING_MODE = device(stringPreferencesKey("fullscreen_seekbar_padding_mode"))
        val FULLSCREEN_SEEKBAR_CUSTOM_PADDING_DP = device(intPreferencesKey("fullscreen_seekbar_custom_padding_dp"))
        val FULLSCREEN_SIDE_PANEL_WIDTH = device(stringPreferencesKey("fullscreen_side_panel_width"))
        val FRAME_STEP_BUTTONS_ENABLED = device(booleanPreferencesKey("frame_step_buttons_enabled"))

        // Mini Player Customizations
        val MINI_BAR_SWIPE_LEFT_ACTION = device(stringPreferencesKey("mini_bar_swipe_left_action"))
        val MINI_BAR_SWIPE_RIGHT_ACTION = device(stringPreferencesKey("mini_bar_swipe_right_action"))
        val QUEUE_SWIPE_LEFT_ACTION = device(stringPreferencesKey("queue_swipe_left_action"))
        val QUEUE_SWIPE_RIGHT_ACTION = device(stringPreferencesKey("queue_swipe_right_action"))

        // Audio focus during calls
        val PLAY_DURING_CALLS = device(booleanPreferencesKey("play_during_calls"))

        // Donation / support prompt
        val DONATION_FIRST_LAUNCH_TIME = device(longPreferencesKey("donation_first_launch_time"))
        val DONATION_PROMPT_LAST_SHOWN = device(longPreferencesKey("donation_prompt_last_shown"))
        val DONATION_PROMPT_DISABLED = device(booleanPreferencesKey("donation_prompt_disabled"))

        // Remember playback speed
        val PAUSE_MUSIC_WHEN_MUTED = device(booleanPreferencesKey("pause_music_when_muted"))
        val MUSIC_SIDE_SWIPES = device(booleanPreferencesKey("music_side_swipes"))
        val LOCK_SCREEN_PLAYER = device(booleanPreferencesKey("lock_screen_player"))
        val APP_LOCK_ENABLED = device(booleanPreferencesKey("app_lock_enabled"))
        val APP_LOCK_DELAY_MINUTES = device(intPreferencesKey("app_lock_delay_minutes"))
        val APP_LOCK_HIDE_IN_RECENTS = device(booleanPreferencesKey("app_lock_hide_in_recents"))
        val ANIMATED_ARTWORK_WIFI_ONLY = device(booleanPreferencesKey("animated_artwork_wifi_only"))
        val AUTO_DOWNLOAD_LIKED_MUSIC = device(booleanPreferencesKey("auto_download_liked_music"))

        // Custom playback speeds
        val LONG_PRESS_PLAYBACK_SPEED = device(floatPreferencesKey("long_press_playback_speed"))
        val SPEED_SWIPE_SIDE = device(stringPreferencesKey("speed_swipe_side"))
        val SPEED_SWIPE_STEP = device(floatPreferencesKey("speed_swipe_step"))

        // Cache size
        val MEDIA_CACHE_SIZE_MB = device(intPreferencesKey("media_cache_size_mb"))
        val MUSIC_CACHE_SIZE_MB = device(intPreferencesKey("music_cache_size_mb"))
        val ARTWORK_CACHE_SIZE_MB = device(intPreferencesKey("artwork_cache_size_mb"))

        // App icon — stores the component suffix of the currently selected launcher icon
        val APP_ICON_SUFFIX = device(stringPreferencesKey("app_icon_suffix"))

        // Newest-first, newline-delimited so the list can be trimmed to a bounded size.
        val UNPLAYABLE_VIDEO_IDS = device(stringPreferencesKey("unplayable_video_ids"))

        // Auto-backup settings
        val AUTO_BACKUP_FREQUENCY = device(stringPreferencesKey("auto_backup_frequency"))
        val AUTO_BACKUP_FOLDER_URI = device(stringPreferencesKey("auto_backup_folder_uri"))
        val AUTO_BACKUP_TYPE = device(stringPreferencesKey("auto_backup_type"))

        // Volume boost: opt-in, default off
        val ALLOW_VOLUME_BOOST = device(booleanPreferencesKey("allow_volume_boost"))

        // New settings (#1676): when each settings key was first seen, and how long it stays marked
        val SETTINGS_FIRST_SEEN = device(stringPreferencesKey("settings_first_seen"))
        val NEW_SETTINGS_HIGHLIGHT = device(booleanPreferencesKey("new_settings_highlight"))
        val NEW_SETTINGS_HIGHLIGHT_DAYS = device(intPreferencesKey("new_settings_highlight_days"))

        val NAMES: Set<String> = names
    }

    companion object {
        /** Names of the keys that moved to the device store, for the one-time copy. */
        internal val DEVICE_KEY_NAMES: Set<String> get() = DeviceKeys.NAMES
    }

    // Grid item size preference
    val gridItemSize: Flow<String> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.GRID_ITEM_SIZE] ?: "BIG"
            }

    suspend fun setGridItemSize(size: String) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.GRID_ITEM_SIZE] = size
        }
    }

    // ── Volume boost (#491): opt-in, default OFF ──
    val allowVolumeBoost: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.ALLOW_VOLUME_BOOST] ?: false
            }

    suspend fun setAllowVolumeBoost(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.ALLOW_VOLUME_BOOST] = enabled
        }
    }

    /** Settings key to the epoch day it was first seen on this device; null before Settings first opened. */
    val settingsFirstSeen: Flow<Map<String, Long>?> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.SETTINGS_FIRST_SEEN]?.let(::decodeFirstSeen) }
            .distinctUntilChanged()

    /** Rewrites the first-seen map with [update], which gets null the first time. */
    suspend fun updateSettingsFirstSeen(update: (Map<String, Long>?) -> Map<String, Long>) {
        context.devicePreferencesDataStore.edit { preferences ->
            val current = preferences[DeviceKeys.SETTINGS_FIRST_SEEN]?.let(::decodeFirstSeen)
            val next = update(current)
            if (next != current) preferences[DeviceKeys.SETTINGS_FIRST_SEEN] = Json.encodeToString(FirstSeenSerializer, next)
        }
    }

    /** Whether settings added in an update get a dot; off unless the user turns it on. */
    val newSettingsHighlight: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.NEW_SETTINGS_HIGHLIGHT] ?: DEFAULT_NEW_SETTINGS_HIGHLIGHT }
            .distinctUntilChanged()

    suspend fun setNewSettingsHighlight(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.NEW_SETTINGS_HIGHLIGHT] = enabled
        }
    }

    /** How many days a new setting keeps its dot while [newSettingsHighlight] is on. */
    val newSettingsHighlightDays: Flow<Int> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.NEW_SETTINGS_HIGHLIGHT_DAYS] ?: DEFAULT_NEW_SETTINGS_HIGHLIGHT_DAYS }
            .distinctUntilChanged()

    suspend fun setNewSettingsHighlightDays(days: Int) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.NEW_SETTINGS_HIGHLIGHT_DAYS] = days
        }
    }

    private fun decodeFirstSeen(raw: String): Map<String, Long>? =
        runCatching { Json.decodeFromString(FirstSeenSerializer, raw) }.getOrNull()

    // ── Shorts playback speed (#496): remembered across sessions ──
    val shortsPlaybackSpeed: Flow<Float> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SHORTS_PLAYBACK_SPEED] ?: 1.0f
            }

    suspend fun setShortsPlaybackSpeed(speed: Float) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SHORTS_PLAYBACK_SPEED] = speed
        }
    }

    // ── Date & time display──
    val dateDisplayMode: Flow<DateDisplayMode> =
        context.playerPreferencesDataStore.data
            .map { preferences -> DateDisplayMode.fromString(preferences[Keys.DATE_DISPLAY_MODE]) }

    suspend fun setDateDisplayMode(mode: DateDisplayMode) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.DATE_DISPLAY_MODE] = mode.name
        }
    }

    val dateFormatStyle: Flow<DateFormatStyle> =
        context.playerPreferencesDataStore.data
            .map { preferences -> DateFormatStyle.fromString(preferences[Keys.DATE_FORMAT_STYLE]) }

    suspend fun setDateFormatStyle(style: DateFormatStyle) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.DATE_FORMAT_STYLE] = style.name
        }
    }

    val dateModeLists: Flow<DateContextMode> =
        context.playerPreferencesDataStore.data
            .map { DateContextMode.fromString(it[Keys.DATE_MODE_LISTS]) }
    val dateModeWatch: Flow<DateContextMode> =
        context.playerPreferencesDataStore.data
            .map { DateContextMode.fromString(it[Keys.DATE_MODE_WATCH]) }
    val dateModeDescription: Flow<DateContextMode> =
        context.playerPreferencesDataStore.data
            .map { DateContextMode.fromString(it[Keys.DATE_MODE_DESCRIPTION]) }

    suspend fun setDateModeLists(mode: DateContextMode) {
        context.playerPreferencesDataStore.edit { it[Keys.DATE_MODE_LISTS] = mode.name }
    }

    suspend fun setDateModeWatch(mode: DateContextMode) {
        context.playerPreferencesDataStore.edit { it[Keys.DATE_MODE_WATCH] = mode.name }
    }

    suspend fun setDateModeDescription(mode: DateContextMode) {
        context.playerPreferencesDataStore.edit { it[Keys.DATE_MODE_DESCRIPTION] = mode.name }
    }

    // Swipe gestures (brightness/volume) enabled preference
    val swipeGesturesEnabled: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.SWIPE_GESTURES_ENABLED] ?: true
            }

    val brightnessSwipeGesturesEnabled: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.BRIGHTNESS_SWIPE_GESTURES_ENABLED]
                    ?: preferences[DeviceKeys.SWIPE_GESTURES_ENABLED]
                    ?: true
            }

    val rememberBrightnessEnabled: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.REMEMBER_BRIGHTNESS_ENABLED] ?: false
            }

    val rememberedBrightnessLevel: Flow<Float> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.REMEMBERED_BRIGHTNESS_LEVEL] ?: -1f
            }

    val volumeSwipeGesturesEnabled: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.VOLUME_SWIPE_GESTURES_ENABLED]
                    ?: preferences[DeviceKeys.SWIPE_GESTURES_ENABLED]
                    ?: true
            }

    val seekSwipeGesturesEnabled: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.SEEK_SWIPE_GESTURES_ENABLED]
                    ?: preferences[DeviceKeys.SWIPE_GESTURES_ENABLED]
                    ?: true
            }

    suspend fun setSwipeGesturesEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.SWIPE_GESTURES_ENABLED] = enabled
        }
    }

    suspend fun setBrightnessSwipeGesturesEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.BRIGHTNESS_SWIPE_GESTURES_ENABLED] = enabled
        }
    }

    suspend fun setRememberBrightnessEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.REMEMBER_BRIGHTNESS_ENABLED] = enabled
        }
    }

    suspend fun setRememberedBrightnessLevel(level: Float) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.REMEMBERED_BRIGHTNESS_LEVEL] = if (level < 0f) -1f else level.coerceIn(0f, 1f)
        }
    }

    suspend fun setVolumeSwipeGesturesEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.VOLUME_SWIPE_GESTURES_ENABLED] = enabled
        }
    }

    suspend fun setSeekSwipeGesturesEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.SEEK_SWIPE_GESTURES_ENABLED] = enabled
        }
    }

    val moreVideosSwipeEnabled: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.MORE_VIDEOS_SWIPE_ENABLED] ?: true }

    suspend fun setMoreVideosSwipeEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.MORE_VIDEOS_SWIPE_ENABLED] = enabled
        }
    }

    // SponsorBlock per-category action preferences
    fun sbActionForCategory(category: String): Flow<SponsorBlockAction> {
        val key =
            when (category) {
                "sponsor" -> Keys.SB_ACTION_SPONSOR
                "intro" -> Keys.SB_ACTION_INTRO
                "outro" -> Keys.SB_ACTION_OUTRO
                "selfpromo" -> Keys.SB_ACTION_SELFPROMO
                "interaction" -> Keys.SB_ACTION_INTERACTION
                "music_offtopic" -> Keys.SB_ACTION_MUSIC_OFFTOPIC
                "filler" -> Keys.SB_ACTION_FILLER
                "preview" -> Keys.SB_ACTION_PREVIEW
                "exclusive_access" -> Keys.SB_ACTION_EXCLUSIVE_ACCESS
                "hook" -> Keys.SB_ACTION_HOOK
                "poi_highlight" -> Keys.SB_ACTION_HIGHLIGHT
                else -> Keys.SB_ACTION_SPONSOR
            }
        return context.playerPreferencesDataStore.data.map { preferences ->
            preferences[key]?.let(SponsorBlockAction::fromString) ?: SponsorBlockCategories.defaultAction(category)
        }
    }

    suspend fun setSbActionForCategory(
        category: String,
        action: SponsorBlockAction,
    ) {
        val key =
            when (category) {
                "sponsor" -> Keys.SB_ACTION_SPONSOR
                "intro" -> Keys.SB_ACTION_INTRO
                "outro" -> Keys.SB_ACTION_OUTRO
                "selfpromo" -> Keys.SB_ACTION_SELFPROMO
                "interaction" -> Keys.SB_ACTION_INTERACTION
                "music_offtopic" -> Keys.SB_ACTION_MUSIC_OFFTOPIC
                "filler" -> Keys.SB_ACTION_FILLER
                "preview" -> Keys.SB_ACTION_PREVIEW
                "exclusive_access" -> Keys.SB_ACTION_EXCLUSIVE_ACCESS
                "hook" -> Keys.SB_ACTION_HOOK
                "poi_highlight" -> Keys.SB_ACTION_HIGHLIGHT
                else -> Keys.SB_ACTION_SPONSOR
            }
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[key] = action.name
        }
    }

    // SponsorBlock per-category color preferences (stored as ARGB Int)
    private val sbColorKeys: Map<String, Preferences.Key<Int>> =
        mapOf(
            "sponsor" to Keys.SB_COLOR_SPONSOR,
            "intro" to Keys.SB_COLOR_INTRO,
            "outro" to Keys.SB_COLOR_OUTRO,
            "selfpromo" to Keys.SB_COLOR_SELFPROMO,
            "interaction" to Keys.SB_COLOR_INTERACTION,
            "music_offtopic" to Keys.SB_COLOR_MUSIC_OFFTOPIC,
            "filler" to Keys.SB_COLOR_FILLER,
            "preview" to Keys.SB_COLOR_PREVIEW,
            "exclusive_access" to Keys.SB_COLOR_EXCLUSIVE_ACCESS,
            "hook" to Keys.SB_COLOR_HOOK,
            "poi_highlight" to Keys.SB_COLOR_HIGHLIGHT,
        )

    private fun sbColorKey(category: String): Preferences.Key<Int> = sbColorKeys[category] ?: Keys.SB_COLOR_SPONSOR

    fun sbColorForCategory(category: String): Flow<Int?> {
        val key = sbColorKey(category)
        return context.playerPreferencesDataStore.data.map { prefs -> prefs[key] }
    }

    /**
     * Every user-chosen category colour in a single read. The seek bar paints all categories at
     * once, so collecting [sbColorForCategory] per category there would mean nine separate
     * collectors over the same DataStore.
     */
    private fun Preferences.readSponsorCategoryColors(): Map<String, Int> {
        val colors = mutableMapOf<String, Int>()
        sbColorKeys.forEach { (category, key) -> this[key]?.let { colors[category] = it } }
        return colors
    }

    private val overlayDefaults = PlayerOverlayPreferences()

    private fun toOverlayPreferences(
        profile: Preferences,
        device: Preferences,
    ): PlayerOverlayPreferences {
        val fullscreenPaddingMode =
            device[DeviceKeys.FULLSCREEN_SEEKBAR_PADDING_MODE]
                ?.let { storedMode -> runCatching { SeekbarPaddingMode.valueOf(storedMode) }.getOrNull() }
                ?: SeekbarPaddingMode.DEFAULT

        return PlayerOverlayPreferences(
            castEnabled = device[DeviceKeys.OVERLAY_CAST_ENABLED] ?: overlayDefaults.castEnabled,
            captionsEnabled = device[DeviceKeys.OVERLAY_CC_ENABLED] ?: overlayDefaults.captionsEnabled,
            pipEnabled = device[DeviceKeys.OVERLAY_PIP_ENABLED] ?: overlayDefaults.pipEnabled,
            autoplayEnabled = device[DeviceKeys.OVERLAY_AUTOPLAY_ENABLED] ?: overlayDefaults.autoplayEnabled,
            sleepTimerEnabled = device[DeviceKeys.OVERLAY_SLEEPTIMER_ENABLED] ?: overlayDefaults.sleepTimerEnabled,
            speedIndicatorEnabled =
                device[DeviceKeys.OVERLAY_SPEED_INDICATOR_ENABLED] ?: overlayDefaults.speedIndicatorEnabled,
            commentsEnabled = device[DeviceKeys.OVERLAY_COMMENTS_ENABLED] ?: overlayDefaults.commentsEnabled,
            sponsorBlockEnabled = device[DeviceKeys.OVERLAY_SPONSORBLOCK_ENABLED] ?: overlayDefaults.sponsorBlockEnabled,
            nextPrevButtonsEnabled = device[DeviceKeys.OVERLAY_NEXT_PREV_ENABLED] ?: overlayDefaults.nextPrevButtonsEnabled,
            showControlsWhileLoading =
                device[DeviceKeys.SHOW_CONTROLS_WHILE_LOADING] ?: overlayDefaults.showControlsWhileLoading,
            fullscreenSeekbarHorizontalPaddingDp =
                resolveSeekbarHorizontalPaddingDp(
                    mode = fullscreenPaddingMode,
                    customPaddingDp =
                        (device[DeviceKeys.FULLSCREEN_SEEKBAR_CUSTOM_PADDING_DP] ?: DEFAULT_FULLSCREEN_SEEKBAR_PADDING_DP)
                            .coerceIn(0, MAX_FULLSCREEN_SEEKBAR_PADDING_DP),
                    defaultPaddingDp = DEFAULT_FULLSCREEN_SEEKBAR_PADDING_DP,
                    maxPaddingDp = MAX_FULLSCREEN_SEEKBAR_PADDING_DP,
                ),
            portraitSeekbarHorizontalPaddingDp =
                resolveSeekbarHorizontalPaddingDp(
                    mode = resolvePortraitSeekbarPaddingMode(device[DeviceKeys.PORTRAIT_SEEKBAR_PADDING_MODE]),
                    customPaddingDp =
                        (device[DeviceKeys.PORTRAIT_SEEKBAR_CUSTOM_PADDING_DP] ?: DEFAULT_PORTRAIT_SEEKBAR_PADDING_DP)
                            .coerceIn(0, MAX_PORTRAIT_SEEKBAR_PADDING_DP),
                    defaultPaddingDp = DEFAULT_PORTRAIT_SEEKBAR_PADDING_DP,
                    maxPaddingDp = MAX_PORTRAIT_SEEKBAR_PADDING_DP,
                ),
            idleProgressBar = resolveIdleProgressBar(device[DeviceKeys.PORTRAIT_IDLE_PROGRESS_BAR]),
            scrubPreviewStyle = resolveScrubPreviewStyle(profile[Keys.SCRUB_PREVIEW_STYLE]),
            frameStepButtonsEnabled =
                device[DeviceKeys.FRAME_STEP_BUTTONS_ENABLED] ?: overlayDefaults.frameStepButtonsEnabled,
            sponsorCategoryColors = profile.readSponsorCategoryColors(),
        )
    }

    val overlayPreferences: Flow<PlayerOverlayPreferences> =
        combine(context.playerPreferencesDataStore.data, context.devicePreferencesDataStore.data, ::toOverlayPreferences)

    suspend fun setSbColorForCategory(
        category: String,
        colorArgb: Int?,
    ) {
        val key = sbColorKey(category)
        context.playerPreferencesDataStore.edit { prefs ->
            if (colorArgb != null) prefs[key] = colorArgb else prefs.remove(key)
        }
    }

    // Flow for reading the stored SB User ID (may be null)
    val sbUserId: Flow<String?> =
        context.playerPreferencesDataStore.data
            .map { prefs -> prefs[Keys.SB_USER_ID]?.takeIf { it.isNotBlank() } }

    suspend fun setSbUserId(id: String) {
        context.playerPreferencesDataStore.edit { prefs ->
            prefs[Keys.SB_USER_ID] = id
        }
    }

    val sbSubmitEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.SB_SUBMIT_ENABLED] ?: false }

    suspend fun setSbSubmitEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SB_SUBMIT_ENABLED] = enabled
        }
    }

    /** Whether an automatic skip shows a message. The Notify action has its own message and ignores this. */
    val sbSkipNoticeEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.SB_SKIP_NOTICE_ENABLED] ?: true }

    suspend fun setSbSkipNoticeEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SB_SKIP_NOTICE_ENABLED] = enabled
        }
    }

    val sbSkipButtonStyle: Flow<SponsorBlockSkipButtonStyle> =
        context.playerPreferencesDataStore.data
            .map { preferences -> SponsorBlockSkipButtonStyle.fromStored(preferences[Keys.SB_SKIP_BUTTON_STYLE]) }

    suspend fun setSbSkipButtonStyle(style: SponsorBlockSkipButtonStyle) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SB_SKIP_BUTTON_STYLE] = style.name
        }
    }

    /** Returns the stored SponsorBlock user ID, generating a new UUID if not set. */
    suspend fun getOrCreateSbUserId(): String {
        val prefs = context.playerPreferencesDataStore.data.first()
        val existing = prefs[Keys.SB_USER_ID]
        if (!existing.isNullOrBlank()) return existing
        val newId =
            java.util.UUID
                .randomUUID()
                .toString()
                .replace("-", "")
        context.playerPreferencesDataStore.edit { it[Keys.SB_USER_ID] = newId }
        return newId
    }

    // Slider Style preference.
    val sliderStyle: Flow<SliderStyle> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                when (val stored = preferences[Keys.SLIDER_STYLE]) {
                    null -> SliderStyle.COMPACT
                    "METROLIST" -> SliderStyle.THICK
                    "METROLIST_SLIM" -> SliderStyle.COMPACT
                    else -> runCatching { SliderStyle.valueOf(stored) }.getOrDefault(SliderStyle.COMPACT)
                }
            }

    suspend fun setSliderStyle(style: SliderStyle) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SLIDER_STYLE] = style.name
        }
    }

    val musicPlayerBackgroundStyle: Flow<MusicPlayerBackgroundStyle> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                runCatching {
                    MusicPlayerBackgroundStyle.valueOf(
                        preferences[Keys.MUSIC_PLAYER_BACKGROUND_STYLE]
                            ?: MusicPlayerBackgroundStyle.BLUR_GRADIENT.name,
                    )
                }.getOrDefault(MusicPlayerBackgroundStyle.BLUR_GRADIENT)
            }

    suspend fun setMusicPlayerBackgroundStyle(style: MusicPlayerBackgroundStyle) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MUSIC_PLAYER_BACKGROUND_STYLE] = style.name
        }
    }

    /** Whether the music player's buttons and seek bar take the artwork's colors. */
    val musicArtworkControlColors: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.MUSIC_ARTWORK_CONTROL_COLORS] ?: true }

    suspend fun setMusicArtworkControlColors(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MUSIC_ARTWORK_CONTROL_COLORS] = enabled
        }
    }

    /** Whether the video description, comments, chapters and transcript sheets take the thumbnail's colors. */
    val videoArtworkColors: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.VIDEO_ARTWORK_COLORS] ?: true }

    suspend fun setVideoArtworkColors(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.VIDEO_ARTWORK_COLORS] = enabled
        }
    }

    /** Whether the artwork backgrounds turn light while the app theme is light. */
    val musicPlayerFollowLightTheme: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.MUSIC_PLAYER_FOLLOW_LIGHT_THEME] ?: false }

    suspend fun setMusicPlayerFollowLightTheme(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MUSIC_PLAYER_FOLLOW_LIGHT_THEME] = enabled
        }
    }

    /** Whether the music player's action row shows a sleep timer button. */
    val musicSleepTimerButton: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.MUSIC_SLEEP_TIMER_BUTTON] ?: false }

    suspend fun setMusicSleepTimerButton(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MUSIC_SLEEP_TIMER_BUTTON] = enabled
        }
    }

    /** The colors the controls use when artwork colors are off. */
    val musicPlainControlColors: Flow<MusicPlainControlColors> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                runCatching {
                    MusicPlainControlColors.valueOf(
                        preferences[Keys.MUSIC_PLAIN_CONTROL_COLORS] ?: MusicPlainControlColors.MONOCHROME.name,
                    )
                }.getOrDefault(MusicPlainControlColors.MONOCHROME)
            }

    suspend fun setMusicPlainControlColors(colors: MusicPlainControlColors) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MUSIC_PLAIN_CONTROL_COLORS] = colors.name
        }
    }

    val hideMusicPlayerArtwork: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.HIDE_MUSIC_PLAYER_ARTWORK] ?: false
            }

    suspend fun setHideMusicPlayerArtwork(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.HIDE_MUSIC_PLAYER_ARTWORK] = enabled
        }
    }

    val shortsPlayerUiMode: Flow<ShortsPlayerUiMode> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SHORTS_PLAYER_UI_MODE]
                    ?.let { storedMode -> runCatching { ShortsPlayerUiMode.valueOf(storedMode) }.getOrNull() }
                    ?: ShortsPlayerUiMode.DEFAULT
            }

    suspend fun setShortsPlayerUiMode(mode: ShortsPlayerUiMode) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SHORTS_PLAYER_UI_MODE] = mode.name
        }
    }

    val gestureOverlayStyle: Flow<GestureOverlayStyle> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.GESTURE_OVERLAY_STYLE]
                    ?.let { stored -> runCatching { GestureOverlayStyle.valueOf(stored) }.getOrNull() }
                    ?: GestureOverlayStyle.CIRCULAR
            }

    val hapticsEnabled: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.PLAYER_HAPTICS_ENABLED] ?: true }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.PLAYER_HAPTICS_ENABLED] = enabled
        }
    }

    suspend fun setGestureOverlayStyle(style: GestureOverlayStyle) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.GESTURE_OVERLAY_STYLE] = style.name
        }
    }

    val groupedQualitySelectorEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.GROUPED_QUALITY_SELECTOR_ENABLED] ?: false
            }

    suspend fun setGroupedQualitySelectorEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.GROUPED_QUALITY_SELECTOR_ENABLED] = enabled
        }
    }

    val paidPromotionLabel: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.PAID_PROMOTION_LABEL] ?: false }

    suspend fun setPaidPromotionLabel(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.PAID_PROMOTION_LABEL] = enabled
        }
    }

    // The notes master switch. The two surface switches below are ANDed with it, so turning this off
    // hides both without clearing either of their own settings.
    val notesEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.NOTES_ENABLED] ?: true }

    suspend fun setNotesEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences -> preferences[Keys.NOTES_ENABLED] = enabled }
    }

    val channelNotesEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.CHANNEL_NOTES_ENABLED] ?: true }

    suspend fun setChannelNotesEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences -> preferences[Keys.CHANNEL_NOTES_ENABLED] = enabled }
    }

    val videoNotesEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.VIDEO_NOTES_ENABLED] ?: true }

    suspend fun setVideoNotesEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences -> preferences[Keys.VIDEO_NOTES_ENABLED] = enabled }
    }

    val effectiveChannelNotesEnabled: Flow<Boolean> =
        combine(notesEnabled, channelNotesEnabled) { master, own -> master && own }

    val effectiveVideoNotesEnabled: Flow<Boolean> =
        combine(notesEnabled, videoNotesEnabled) { master, own -> master && own }

    /**
     * Master switch for Shorts (reels) as content. When OFF the app hides every reel surface and the
     * five granular Shorts toggles below are overridden — read the `effective*` flows, never the raw
     * ones, or that surface will silently ignore the master switch.
     *
     * Hiding only. Saved Shorts and Shorts watch history stay in the database untouched.
     */
    val shortsContentEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SHORTS_CONTENT_ENABLED] ?: true
            }

    suspend fun setShortsContentEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SHORTS_CONTENT_ENABLED] = enabled
        }
    }

    /** Keeps a channel's Shorts tab while the master switch hides Shorts everywhere else. */
    val channelShortsTabWhenHidden: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.CHANNEL_SHORTS_TAB_WHEN_HIDDEN] ?: false
            }

    val effectiveChannelShortsTabEnabled: Flow<Boolean> =
        combine(shortsContentEnabled, channelShortsTabWhenHidden) { master, keep -> master || keep }

    suspend fun setChannelShortsTabWhenHidden(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.CHANNEL_SHORTS_TAB_WHEN_HIDDEN] = enabled
        }
    }

    /**
     * When OFF the Library screen drops the horizontal preview shelves and lists each section as a
     * single navigation row with its item count.
     */
    val libraryShelfPreviewsEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.LIBRARY_SHELF_PREVIEWS_ENABLED] ?: true
            }

    suspend fun setLibraryShelfPreviewsEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.LIBRARY_SHELF_PREVIEWS_ENABLED] = enabled
        }
    }

    /** Library lists video and music playlists as two separate entries instead of one. */
    val separatePlaylistKinds: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.SEPARATE_PLAYLIST_KINDS] ?: false }
            .distinctUntilChanged()

    suspend fun setSeparatePlaylistKinds(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SEPARATE_PLAYLIST_KINDS] = enabled
        }
    }

    val playlistListOrder: Flow<PlaylistListOrder> =
        context.playerPreferencesDataStore.data
            .map { preferences -> PlaylistListOrder.fromStorageValue(preferences[Keys.PLAYLIST_LIST_ORDER]) }
            .distinctUntilChanged()

    suspend fun setPlaylistListOrder(order: PlaylistListOrder) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.PLAYLIST_LIST_ORDER] = order.storageValue
        }
    }

    val playlistsCompactLayout: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.PLAYLISTS_COMPACT_LAYOUT] ?: false }
            .distinctUntilChanged()

    suspend fun setPlaylistsCompactLayout(compact: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.PLAYLISTS_COMPACT_LAYOUT] = compact
        }
    }

    /** The Videos/Music choice on the playlists page, kept between visits. */
    val playlistsShowMusic: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.PLAYLISTS_SHOW_MUSIC] ?: false }
            .distinctUntilChanged()

    suspend fun setPlaylistsShowMusic(showMusic: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.PLAYLISTS_SHOW_MUSIC] = showMusic
        }
    }

    // Shorts shelf enabled preference
    val shortsShelfEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SHORTS_SHELF_ENABLED] ?: true
            }

    val effectiveShortsShelfEnabled: Flow<Boolean> =
        combine(shortsContentEnabled, shortsShelfEnabled) { master, own -> master && own }

    suspend fun setShortsShelfEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SHORTS_SHELF_ENABLED] = enabled
        }
    }

    // Home Shorts shelf enabled preference
    val homeShortsShelfEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.HOME_SHORTS_SHELF_ENABLED] ?: true
            }

    val effectiveHomeShortsShelfEnabled: Flow<Boolean> =
        combine(shortsContentEnabled, homeShortsShelfEnabled) { master, own -> master && own }

    suspend fun setHomeShortsShelfEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.HOME_SHORTS_SHELF_ENABLED] = enabled
        }
    }

    val homeSubscriptionsEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.HOME_SUBSCRIPTIONS_ENABLED] ?: true }

    suspend fun setHomeSubscriptionsEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.HOME_SUBSCRIPTIONS_ENABLED] = enabled
        }
    }

    /**
     * The engine's last answer that this viewer needs no onboarding, so a cold start can pick its
     * first screen without waiting for the recommendation engine to load.
     */
    val onboardingDone: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.ONBOARDING_DONE] ?: false }

    suspend fun setOnboardingDone(done: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.ONBOARDING_DONE] = done
        }
    }

    /** Keeps a copy of each Home load on the device so it can be exported for a recommendation report. */
    val homeSnapshotRecording: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.HOME_SNAPSHOT_RECORDING] ?: false }

    suspend fun setHomeSnapshotRecording(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.HOME_SNAPSHOT_RECORDING] = enabled
        }
    }

    /** Collaborations of followed channels that someone else uploaded, in Subscriptions (#840). */
    val subscriptionCollaborationsEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.SUBSCRIPTION_COLLABORATIONS_ENABLED] ?: true }
            .distinctUntilChanged()

    suspend fun setSubscriptionCollaborationsEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SUBSCRIPTION_COLLABORATIONS_ENABLED] = enabled
        }
    }

    /** The Notes page's sort, by enum name; kept so a hand-made order is still there next visit. */
    val notesSort: Flow<String?> = context.playerPreferencesDataStore.data.map { preferences -> preferences[Keys.NOTES_SORT] }

    suspend fun setNotesSort(name: String) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.NOTES_SORT] = name
        }
    }

    val showWatchProgress: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.SHOW_WATCH_PROGRESS] ?: true }

    suspend fun setShowWatchProgress(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SHOW_WATCH_PROGRESS] = enabled
        }
    }

    val watchHistoryPaused: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.WATCH_HISTORY_PAUSED] ?: false }

    suspend fun setWatchHistoryPaused(paused: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.WATCH_HISTORY_PAUSED] = paused
        }
    }

    val homeNavigationEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.HOME_NAVIGATION_ENABLED] ?: true }

    suspend fun setHomeNavigationEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.HOME_NAVIGATION_ENABLED] = enabled
        }
    }

    // Shorts navigation enabled preference
    val shortsNavigationEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SHORTS_NAVIGATION_ENABLED] ?: true
            }

    val effectiveShortsNavigationEnabled: Flow<Boolean> =
        combine(shortsContentEnabled, shortsNavigationEnabled) { master, own -> master && own }

    suspend fun setShortsNavigationEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SHORTS_NAVIGATION_ENABLED] = enabled
        }
    }

    // When OFF, the bottom navigation bar stays pinned instead of hiding/showing on scroll.
    val bottomNavHideOnScroll: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.BOTTOM_NAV_HIDE_ON_SCROLL] ?: true
            }

    suspend fun setBottomNavHideOnScroll(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.BOTTOM_NAV_HIDE_ON_SCROLL] = enabled
        }
    }

    // Search keeps its full-screen layout unless this is on.
    val searchShowsNavBar: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SEARCH_SHOWS_NAV_BAR] ?: false
            }

    // Channels, playlists and the other pages inside a tab keep the bar only when this is on (#1489).
    val innerPagesShowNavBar: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.INNER_PAGES_SHOW_NAV_BAR] ?: false }
            .distinctUntilChanged()

    suspend fun setInnerPagesShowNavBar(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.INNER_PAGES_SHOW_NAV_BAR] = enabled
        }
    }

    suspend fun setSearchShowsNavBar(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SEARCH_SHOWS_NAV_BAR] = enabled
        }
    }

    // Music navigation enabled preference
    val musicNavigationEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.MUSIC_NAVIGATION_ENABLED] ?: true
            }

    suspend fun setMusicNavigationEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MUSIC_NAVIGATION_ENABLED] = enabled
        }
    }

    // Search nav tab enabled preference
    val searchNavigationEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SEARCH_NAV_TAB_ENABLED] ?: false
            }

    suspend fun setSearchNavigationEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SEARCH_NAV_TAB_ENABLED] = enabled
        }
    }

    // Categories nav tab enabled preference
    val categoriesNavigationEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.CATEGORIES_NAV_TAB_ENABLED] ?: false
            }

    suspend fun setCategoriesNavigationEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.CATEGORIES_NAV_TAB_ENABLED] = enabled
        }
    }

    val downloadsNavigationEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.DOWNLOADS_NAV_TAB_ENABLED] ?: false }

    suspend fun setDownloadsNavigationEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.DOWNLOADS_NAV_TAB_ENABLED] = enabled
        }
    }

    // Continue Watching shelf enabled preference
    val continueWatchingEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.CONTINUE_WATCHING_ENABLED] ?: true
            }

    suspend fun setContinueWatchingEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.CONTINUE_WATCHING_ENABLED] = enabled
        }
    }

    // Show related videos preference
    val showRelatedVideos: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SHOW_RELATED_VIDEOS] ?: true
            }

    suspend fun setShowRelatedVideos(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SHOW_RELATED_VIDEOS] = enabled
        }
    }

    /** Seconds a double tap on either side jumps; 0 turns double-tap seek off. */
    val doubleTapSeekSeconds: Flow<Int> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                (preferences[DeviceKeys.DOUBLE_TAP_SEEK_SECONDS] ?: 10).coerceAtLeast(0)
            }

    suspend fun setDoubleTapSeekSeconds(seconds: Int) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.DOUBLE_TAP_SEEK_SECONDS] = seconds.coerceAtLeast(0)
        }
    }

    val doubleTapSeekZone: Flow<DoubleTapSeekZone> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.DOUBLE_TAP_SEEK_ZONE]
                    ?.let { stored -> runCatching { DoubleTapSeekZone.valueOf(stored) }.getOrNull() }
                    ?: DoubleTapSeekZone.NORMAL
            }

    suspend fun setDoubleTapSeekZone(zone: DoubleTapSeekZone) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.DOUBLE_TAP_SEEK_ZONE] = zone.name
        }
    }

    // Home view mode preference
    val homeViewMode: Flow<HomeViewMode> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                HomeViewMode.valueOf(preferences[Keys.HOME_VIEW_MODE] ?: HomeViewMode.GRID.name)
            }

    suspend fun setHomeViewMode(mode: HomeViewMode) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.HOME_VIEW_MODE] = mode.name
        }
    }

    val homeFeedColumns: Flow<HomeFeedColumns> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                runCatching {
                    HomeFeedColumns.valueOf(preferences[Keys.HOME_FEED_COLUMNS] ?: HomeFeedColumns.AUTO.name)
                }.getOrDefault(HomeFeedColumns.AUTO)
            }

    suspend fun setHomeFeedColumns(columns: HomeFeedColumns) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.HOME_FEED_COLUMNS] = columns.name
        }
    }

    // Home feed enabled preference
    val homeFeedEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.HOME_FEED_ENABLED] ?: true
            }

    suspend fun setHomeFeedEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.HOME_FEED_ENABLED] = enabled
        }
    }

    val refreshHomeOnReselect: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.REFRESH_HOME_ON_RESELECT] ?: true }

    suspend fun setRefreshHomeOnReselect(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.REFRESH_HOME_ON_RESELECT] = enabled
        }
    }

    // Home subscription rotation cursor
    val homeSubsRotationCursor: Flow<Int> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.HOME_SUBS_ROTATION_CURSOR] ?: 0
            }

    suspend fun setHomeSubsRotationCursor(cursor: Int) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.HOME_SUBS_ROTATION_CURSOR] = cursor.coerceAtLeast(0)
        }
    }

    // Related video card style preference (tablet/player panel)
    val playerRelatedCardStyle: Flow<PlayerRelatedCardStyle> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                try {
                    PlayerRelatedCardStyle.valueOf(preferences[Keys.RELATED_CARD_STYLE] ?: PlayerRelatedCardStyle.FULL_WIDTH.name)
                } catch (_: IllegalArgumentException) {
                    PlayerRelatedCardStyle.FULL_WIDTH
                }
            }

    suspend fun setPlayerRelatedCardStyle(style: PlayerRelatedCardStyle) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.RELATED_CARD_STYLE] = style.name
        }
    }

    val trendingRegion: Flow<String> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.TRENDING_REGION] ?: "US"
            }

    suspend fun setTrendingRegion(region: String) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.TRENDING_REGION] = region
        }
    }

    val appLanguage: Flow<String> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.APP_LANGUAGE] ?: "system"
            }

    suspend fun setAppLanguage(languageTag: String) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.APP_LANGUAGE] = languageTag
        }
    }

    val contentLanguage: Flow<String> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.CONTENT_LANGUAGE] ?: CONTENT_LANGUAGE_FOLLOW_APP
            }

    suspend fun setContentLanguage(languageTag: String) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.CONTENT_LANGUAGE] = languageTag
        }
    }

    /** Titles and descriptions in the video's own language instead of YouTube's translation (#1230). */
    val originalTitles: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.ORIGINAL_TITLES] ?: false }
            .distinctUntilChanged()

    suspend fun setOriginalTitles(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.ORIGINAL_TITLES] = enabled
        }
    }

    val musicLoudnessNormalizationEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.MUSIC_LOUDNESS_NORMALIZATION_ENABLED] ?: true
            }

    suspend fun setMusicLoudnessNormalizationEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MUSIC_LOUDNESS_NORMALIZATION_ENABLED] = enabled
        }
    }

    val musicAudioOffloadEnabled: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.MUSIC_AUDIO_OFFLOAD_ENABLED] ?: false
            }

    suspend fun setMusicAudioOffloadEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.MUSIC_AUDIO_OFFLOAD_ENABLED] = enabled
        }
    }

    // Quality preferences
    val defaultQualityWifi: Flow<VideoQuality> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                VideoQuality.fromString(preferences[DeviceKeys.DEFAULT_QUALITY_WIFI] ?: "1080p")
            }

    val defaultQualityCellular: Flow<VideoQuality> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                VideoQuality.fromString(preferences[DeviceKeys.DEFAULT_QUALITY_CELLULAR] ?: "480p")
            }

    suspend fun setDefaultQualityWifi(quality: VideoQuality) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.DEFAULT_QUALITY_WIFI] = quality.label
        }
    }

    suspend fun setDefaultQualityCellular(quality: VideoQuality) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.DEFAULT_QUALITY_CELLULAR] = quality.label
        }
    }

    val defaultVideoCodec: Flow<VideoCodec> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                VideoCodec.fromString(preferences[DeviceKeys.DEFAULT_VIDEO_CODEC] ?: VideoCodec.H264.label)
            }

    suspend fun setDefaultVideoCodec(codec: VideoCodec) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.DEFAULT_VIDEO_CODEC] = codec.label
        }
    }

    /** Codec to use when a video carries no [defaultVideoCodec] variant. AUTO keeps the built-in order. */
    val fallbackVideoCodec: Flow<VideoCodec> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                VideoCodec.fromString(preferences[DeviceKeys.FALLBACK_VIDEO_CODEC] ?: VideoCodec.AUTO.label)
            }

    suspend fun setFallbackVideoCodec(codec: VideoCodec) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.FALLBACK_VIDEO_CODEC] = codec.label
        }
    }

    /**
     * Both codec preferences as one comma-separated priority string ("av1,vp9"), which is what every
     * stream selector ranks against. "auto" means no preference, leaving the built-in codec order.
     * A fallback without a preferred codec is meaningless, so AUTO on the primary wins outright.
     */
    val videoCodecPriority: Flow<String> =
        combine(defaultVideoCodec, fallbackVideoCodec) { preferred, fallback ->
            if (preferred == VideoCodec.AUTO) {
                VideoCodec.AUTO.codecKey
            } else {
                listOf(preferred, fallback)
                    .filter { it != VideoCodec.AUTO }
                    .map { it.codecKey }
                    .distinct()
                    .joinToString(",")
            }
        }.distinctUntilChanged()

    // Shorts quality preferences (default to 720p WiFi, 480p Cellular)
    val shortsQualityWifi: Flow<VideoQuality> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                VideoQuality.fromString(preferences[DeviceKeys.SHORTS_QUALITY_WIFI] ?: "720p")
            }

    val shortsQualityCellular: Flow<VideoQuality> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                VideoQuality.fromString(preferences[DeviceKeys.SHORTS_QUALITY_CELLULAR] ?: "480p")
            }

    suspend fun setShortsQualityWifi(quality: VideoQuality) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.SHORTS_QUALITY_WIFI] = quality.label
        }
    }

    suspend fun setShortsQualityCellular(quality: VideoQuality) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.SHORTS_QUALITY_CELLULAR] = quality.label
        }
    }

    val thumbnailQualityWifi: Flow<ThumbnailQuality> =
        context.devicePreferencesDataStore.data
            .map { preferences -> ThumbnailQuality.fromName(preferences[DeviceKeys.THUMBNAIL_QUALITY_WIFI]) }

    val thumbnailQualityCellular: Flow<ThumbnailQuality> =
        context.devicePreferencesDataStore.data
            .map { preferences -> ThumbnailQuality.fromName(preferences[DeviceKeys.THUMBNAIL_QUALITY_CELLULAR]) }

    suspend fun setThumbnailQualityWifi(quality: ThumbnailQuality) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.THUMBNAIL_QUALITY_WIFI] = quality.name
        }
    }

    suspend fun setThumbnailQualityCellular(quality: ThumbnailQuality) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.THUMBNAIL_QUALITY_CELLULAR] = quality.name
        }
    }

    /** The level for the network the device is on now, for work that has no composition to read it from. */
    suspend fun currentThumbnailQuality(): ThumbnailQuality =
        ThumbnailQuality.effective(NetworkState.isOnWifi(context), thumbnailQualityWifi.first(), thumbnailQualityCellular.first())

    val musicAudioQuality: Flow<MusicAudioQuality> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                MusicAudioQuality.fromString(preferences[DeviceKeys.MUSIC_AUDIO_QUALITY] ?: MusicAudioQuality.AUTO.label)
            }

    suspend fun setMusicAudioQuality(quality: MusicAudioQuality) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.MUSIC_AUDIO_QUALITY] = quality.label
        }
    }

    // Background play
    val backgroundPlayEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.BACKGROUND_PLAY_ENABLED] ?: DEFAULT_BACKGROUND_PLAY_ENABLED
            }

    suspend fun setBackgroundPlayEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.BACKGROUND_PLAY_ENABLED] = enabled
        }
    }

    /** Whether swiping Flow away from recent apps leaves a playing video or song going (#1468). */
    val keepPlayingAfterSwipe: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.KEEP_PLAYING_AFTER_SWIPE] ?: DEFAULT_KEEP_PLAYING_AFTER_SWIPE
            }

    suspend fun setKeepPlayingAfterSwipe(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.KEEP_PLAYING_AFTER_SWIPE] = enabled
        }
    }

    // Autoplay
    val autoplayEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.AUTOPLAY_ENABLED] ?: true
            }

    suspend fun setAutoplayEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.AUTOPLAY_ENABLED] = enabled
        }
    }

    /** A video the viewer opens loads and waits for play; what plays after it is the autoplay settings' call. */
    val startVideosPaused: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.START_VIDEOS_PAUSED] ?: false }

    suspend fun setStartVideosPaused(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.START_VIDEOS_PAUSED] = enabled
        }
    }

    /** A video the viewer opens goes straight to fullscreen; what plays after it stays where the viewer is. */
    val openVideosFullscreen: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.OPEN_VIDEOS_FULLSCREEN] ?: false }

    suspend fun setOpenVideosFullscreen(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.OPEN_VIDEOS_FULLSCREEN] = enabled
        }
    }

    /** Turning the phone sideways enters fullscreen even while the system rotation lock is on. */
    val tiltFullscreenRotationLocked: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.TILT_FULLSCREEN_ROTATION_LOCKED] ?: false }

    suspend fun setTiltFullscreenRotationLocked(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.TILT_FULLSCREEN_ROTATION_LOCKED] = enabled
        }
    }

    /** A Play button on headphones may start the last songs after Flow was closed. */
    val resumeFromHeadphones: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.RESUME_FROM_HEADPHONES] ?: true }

    suspend fun setResumeFromHeadphones(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.RESUME_FROM_HEADPHONES] = enabled
        }
    }

    // Music endless radio — its own switch, independent of the VIDEO autoplay toggles.
    val musicEndlessRadioEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.MUSIC_ENDLESS_RADIO_ENABLED] ?: true
            }

    suspend fun setMusicEndlessRadioEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MUSIC_ENDLESS_RADIO_ENABLED] = enabled
        }
    }

    // Autoplay for the playback queue (playlists / watch later) — independent of related-video autoplay.
    val queueAutoplayEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.QUEUE_AUTOPLAY_ENABLED] ?: true
            }

    suspend fun setQueueAutoplayEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.QUEUE_AUTOPLAY_ENABLED] = enabled
        }
    }

    val autoplayCountdownSeconds: Flow<Int> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                (preferences[Keys.AUTOPLAY_COUNTDOWN_SECONDS] ?: 0).coerceIn(0, 30)
            }

    suspend fun setAutoplayCountdownSeconds(seconds: Int) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.AUTOPLAY_COUNTDOWN_SECONDS] = seconds.coerceIn(0, 30)
        }
    }

    val showControlsWhileLoading: Flow<Boolean> =
        overlayPreferences.map { it.showControlsWhileLoading }.distinctUntilChanged()

    suspend fun setShowControlsWhileLoading(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.SHOW_CONTROLS_WHILE_LOADING] = enabled
        }
    }

    // Video Ambient Mode
    val videoAmbientModeEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.VIDEO_AMBIENT_MODE_ENABLED] ?: false
            }

    suspend fun setVideoAmbientModeEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.VIDEO_AMBIENT_MODE_ENABLED] = enabled
        }
    }

    // Video Loop
    val videoLoopEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.VIDEO_LOOP_ENABLED] ?: false
            }

    suspend fun setVideoLoopEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.VIDEO_LOOP_ENABLED] = enabled
            if (enabled) {
                preferences[Keys.AUTOPLAY_ENABLED] = false
            }
        }
    }

    // Skip Silence
    val skipSilenceEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SKIP_SILENCE_ENABLED] ?: false
            }

    suspend fun setSkipSilenceEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SKIP_SILENCE_ENABLED] = enabled
        }
    }

    /** Videos and Shorts are turned down to YouTube's loudness level, as on youtube.com, once chosen (#1641). */
    val videoVolumeNormalizationEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.VIDEO_VOLUME_NORMALIZATION] ?: DEFAULT_VIDEO_VOLUME_NORMALIZATION }
            .distinctUntilChanged()

    suspend fun setVideoVolumeNormalizationEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.VIDEO_VOLUME_NORMALIZATION] = enabled
        }
    }

    // SponsorBlock
    val sponsorBlockEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SPONSOR_BLOCK_ENABLED] ?: false
            }

    suspend fun setSponsorBlockEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SPONSOR_BLOCK_ENABLED] = enabled
        }
    }

    // DeArrow
    val deArrowEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.DEARROW_ENABLED] ?: false
            }

    suspend fun setDeArrowEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.DEARROW_ENABLED] = enabled
        }
    }

    val deArrowBadgeEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.DEARROW_BADGE_ENABLED] ?: false }

    suspend fun setDeArrowBadgeEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.DEARROW_BADGE_ENABLED] = enabled
        }
    }

    /** DeArrow's own default: a video with no thumbnail submission shows a frame from the video. */
    val deArrowRandomFrame: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.DEARROW_RANDOM_FRAME] ?: true }

    suspend fun setDeArrowRandomFrame(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.DEARROW_RANDOM_FRAME] = enabled
        }
    }

    // ========== NOTIFICATION PREFERENCES ==========

    val notificationsEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.NOTIFICATIONS_ENABLED] ?: true }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.NOTIFICATIONS_ENABLED] = enabled
        }
    }

    val notifNewVideosEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.NOTIF_NEW_VIDEOS_ENABLED] ?: true }

    suspend fun setNotifNewVideosEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.NOTIF_NEW_VIDEOS_ENABLED] = enabled
        }
    }

    val notifShortsEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.NOTIF_SHORTS_ENABLED] ?: true }

    suspend fun setNotifShortsEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.NOTIF_SHORTS_ENABLED] = enabled
        }
    }

    val notifDownloadsEnabled: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.NOTIF_DOWNLOADS_ENABLED] ?: true }

    suspend fun setNotifDownloadsEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.NOTIF_DOWNLOADS_ENABLED] = enabled
        }
    }

    val notifRemindersEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.NOTIF_REMINDERS_ENABLED] ?: true }

    suspend fun setNotifRemindersEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.NOTIF_REMINDERS_ENABLED] = enabled
        }
    }

    val notifUpdatesEnabled: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.NOTIF_UPDATES_ENABLED] ?: true }

    suspend fun setNotifUpdatesEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.NOTIF_UPDATES_ENABLED] = enabled
        }
    }

    val notifGeneralEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.NOTIF_GENERAL_ENABLED] ?: true }

    suspend fun setNotifGeneralEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.NOTIF_GENERAL_ENABLED] = enabled
        }
    }

    // ========== OVERLAY CONTROLS PREFERENCES ==========

    val overlayCastEnabled: Flow<Boolean> =
        overlayPreferences.map { it.castEnabled }.distinctUntilChanged()

    suspend fun setOverlayCastEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.OVERLAY_CAST_ENABLED] = enabled
        }
    }

    val overlayCommentsEnabled: Flow<Boolean> =
        overlayPreferences.map { it.commentsEnabled }.distinctUntilChanged()

    suspend fun setOverlayCommentsEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.OVERLAY_COMMENTS_ENABLED] = enabled
        }
    }

    val overlaySponsorBlockEnabled: Flow<Boolean> =
        overlayPreferences.map { it.sponsorBlockEnabled }.distinctUntilChanged()

    suspend fun setOverlaySponsorBlockEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.OVERLAY_SPONSORBLOCK_ENABLED] = enabled
        }
    }

    val overlayNextPrevEnabled: Flow<Boolean> =
        overlayPreferences.map { it.nextPrevButtonsEnabled }.distinctUntilChanged()

    suspend fun setOverlayNextPrevEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.OVERLAY_NEXT_PREV_ENABLED] = enabled
        }
    }

    val overlayCcEnabled: Flow<Boolean> =
        overlayPreferences.map { it.captionsEnabled }.distinctUntilChanged()

    suspend fun setOverlayCcEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.OVERLAY_CC_ENABLED] = enabled
        }
    }

    val overlayPipEnabled: Flow<Boolean> =
        overlayPreferences.map { it.pipEnabled }.distinctUntilChanged()

    suspend fun setOverlayPipEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.OVERLAY_PIP_ENABLED] = enabled
        }
    }

    val overlayAutoplayEnabled: Flow<Boolean> =
        overlayPreferences.map { it.autoplayEnabled }.distinctUntilChanged()

    suspend fun setOverlayAutoplayEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.OVERLAY_AUTOPLAY_ENABLED] = enabled
        }
    }

    val overlaySleepTimerEnabled: Flow<Boolean> =
        overlayPreferences.map { it.sleepTimerEnabled }.distinctUntilChanged()

    suspend fun setOverlaySleepTimerEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.OVERLAY_SLEEPTIMER_ENABLED] = enabled
        }
    }

    val overlayLockModeEnabled: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.OVERLAY_LOCK_MODE_ENABLED] ?: false }

    suspend fun setOverlayLockModeEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.OVERLAY_LOCK_MODE_ENABLED] = enabled
        }
    }

    val overlaySpeedIndicatorEnabled: Flow<Boolean> =
        overlayPreferences.map { it.speedIndicatorEnabled }.distinctUntilChanged()

    suspend fun setOverlaySpeedIndicatorEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.OVERLAY_SPEED_INDICATOR_ENABLED] = enabled
        }
    }

    val adaptivePlayerSizeEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.ADAPTIVE_PLAYER_SIZE_ENABLED] ?: true }

    suspend fun setAdaptivePlayerSizeEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.ADAPTIVE_PLAYER_SIZE_ENABLED] = enabled
        }
    }

    val portraitSeekbarPaddingMode: Flow<SeekbarPaddingMode> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                resolvePortraitSeekbarPaddingMode(preferences[DeviceKeys.PORTRAIT_SEEKBAR_PADDING_MODE])
            }

    suspend fun setPortraitSeekbarPaddingMode(mode: SeekbarPaddingMode) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.PORTRAIT_SEEKBAR_PADDING_MODE] = mode.name
        }
    }

    val portraitSeekbarCustomPaddingDp: Flow<Int> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                (preferences[DeviceKeys.PORTRAIT_SEEKBAR_CUSTOM_PADDING_DP] ?: DEFAULT_PORTRAIT_SEEKBAR_PADDING_DP)
                    .coerceIn(0, MAX_PORTRAIT_SEEKBAR_PADDING_DP)
            }

    suspend fun setPortraitSeekbarCustomPaddingDp(paddingDp: Int) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.PORTRAIT_SEEKBAR_CUSTOM_PADDING_DP] =
                paddingDp.coerceIn(0, MAX_PORTRAIT_SEEKBAR_PADDING_DP)
        }
    }

    val idleProgressBar: Flow<IdleProgressBar> =
        context.devicePreferencesDataStore.data
            .map { preferences -> resolveIdleProgressBar(preferences[DeviceKeys.PORTRAIT_IDLE_PROGRESS_BAR]) }

    suspend fun setIdleProgressBar(position: IdleProgressBar) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.PORTRAIT_IDLE_PROGRESS_BAR] = position.name
        }
    }

    val fullscreenSeekbarPaddingMode: Flow<SeekbarPaddingMode> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.FULLSCREEN_SEEKBAR_PADDING_MODE]
                    ?.let { storedMode -> runCatching { SeekbarPaddingMode.valueOf(storedMode) }.getOrNull() }
                    ?: SeekbarPaddingMode.DEFAULT
            }

    suspend fun setFullscreenSeekbarPaddingMode(mode: SeekbarPaddingMode) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.FULLSCREEN_SEEKBAR_PADDING_MODE] = mode.name
        }
    }

    val fullscreenSeekbarCustomPaddingDp: Flow<Int> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                (preferences[DeviceKeys.FULLSCREEN_SEEKBAR_CUSTOM_PADDING_DP] ?: DEFAULT_FULLSCREEN_SEEKBAR_PADDING_DP)
                    .coerceIn(0, MAX_FULLSCREEN_SEEKBAR_PADDING_DP)
            }

    suspend fun setFullscreenSeekbarCustomPaddingDp(paddingDp: Int) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.FULLSCREEN_SEEKBAR_CUSTOM_PADDING_DP] =
                paddingDp.coerceIn(0, MAX_FULLSCREEN_SEEKBAR_PADDING_DP)
        }
    }

    val fullscreenSidePanelWidth: Flow<FullscreenSidePanelWidth> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.FULLSCREEN_SIDE_PANEL_WIDTH]
                    ?.let { stored -> runCatching { FullscreenSidePanelWidth.valueOf(stored) }.getOrNull() }
                    ?: FullscreenSidePanelWidth.DEFAULT
            }

    suspend fun setFullscreenSidePanelWidth(width: FullscreenSidePanelWidth) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.FULLSCREEN_SIDE_PANEL_WIDTH] = width.name
        }
    }

    val scrubPreviewStyle: Flow<ScrubPreviewStyle> =
        context.playerPreferencesDataStore.data
            .map { preferences -> resolveScrubPreviewStyle(preferences[Keys.SCRUB_PREVIEW_STYLE]) }

    suspend fun setScrubPreviewStyle(style: ScrubPreviewStyle) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SCRUB_PREVIEW_STYLE] = style.name
        }
    }

    val frameStepButtonsEnabled: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.FRAME_STEP_BUTTONS_ENABLED] ?: false }

    suspend fun setFrameStepButtonsEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.FRAME_STEP_BUTTONS_ENABLED] = enabled
        }
    }

    // Subtitles
    val subtitleStyle: Flow<SubtitleStyle> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                val bottomPadding = preferences[Keys.SUBTITLE_BOTTOM_PADDING] ?: SubtitleStyle.DEFAULT_BOTTOM_PADDING
                val fullscreenBottomPadding = preferences[Keys.SUBTITLE_FULLSCREEN_BOTTOM_PADDING] ?: bottomPadding
                SubtitleStyle(
                    fontSize = preferences[Keys.SUBTITLE_FONT_SIZE] ?: 14f,
                    textColor = Color(preferences[Keys.SUBTITLE_TEXT_COLOR] ?: Color.White.toArgb()),
                    backgroundColor =
                        Color(
                            preferences[Keys.SUBTITLE_BACKGROUND_COLOR]
                                ?: Color.Black.copy(alpha = 0.6f).toArgb(),
                        ),
                    windowColor = preferences[Keys.SUBTITLE_WINDOW_COLOR]?.let(::Color) ?: Color.Transparent,
                    edgeType =
                        preferences[Keys.SUBTITLE_EDGE_TYPE]
                            ?.let { stored -> SubtitleEdgeType.entries.firstOrNull { it.name == stored } }
                            ?: SubtitleEdgeType.NONE,
                    edgeColor = preferences[Keys.SUBTITLE_EDGE_COLOR]?.let(::Color) ?: Color.Black,
                    isBold = preferences[Keys.SUBTITLE_BOLD] ?: true,
                    bottomPadding = bottomPadding,
                    // Each starts at the value it would have used, so nobody's captions move until they set it.
                    fullscreenBottomPadding = fullscreenBottomPadding,
                    verticalFullscreenBottomPadding =
                        preferences[Keys.SUBTITLE_VERTICAL_FULLSCREEN_BOTTOM_PADDING] ?: fullscreenBottomPadding,
                )
            }

    suspend fun setSubtitleStyle(style: SubtitleStyle) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SUBTITLE_FONT_SIZE] = style.fontSize
            preferences[Keys.SUBTITLE_TEXT_COLOR] = style.textColor.toArgb()
            preferences[Keys.SUBTITLE_BACKGROUND_COLOR] = style.backgroundColor.toArgb()
            preferences[Keys.SUBTITLE_BOLD] = style.isBold
            preferences[Keys.SUBTITLE_WINDOW_COLOR] = style.windowColor.toArgb()
            preferences[Keys.SUBTITLE_EDGE_TYPE] = style.edgeType.name
            preferences[Keys.SUBTITLE_EDGE_COLOR] = style.edgeColor.toArgb()
            preferences[Keys.SUBTITLE_BOTTOM_PADDING] = style.bottomPadding
            preferences[Keys.SUBTITLE_FULLSCREEN_BOTTOM_PADDING] = style.fullscreenBottomPadding
            preferences[Keys.SUBTITLE_VERTICAL_FULLSCREEN_BOTTOM_PADDING] = style.verticalFullscreenBottomPadding
        }
    }

    // Audio Language Preference
    val preferredAudioLanguage: Flow<String> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.PREFERRED_AUDIO_LANGUAGE] ?: "original" // Default to original/native
            }

    suspend fun setPreferredAudioLanguage(language: String) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.PREFERRED_AUDIO_LANGUAGE] = language
        }
    }

    /**
     * Caption language the player reaches for, as a BCP-47 tag or
     * [CaptionTrackResolver.NO_PREFERRED_LANGUAGE]. Written from the player whenever a caption
     * track is picked, so turning captions off and on again returns to the same language.
     */
    val preferredSubtitleLanguage: Flow<String> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.PREFERRED_SUBTITLE_LANGUAGE] ?: CaptionTrackResolver.NO_PREFERRED_LANGUAGE
            }.distinctUntilChanged()

    suspend fun setPreferredSubtitleLanguage(language: String) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.PREFERRED_SUBTITLE_LANGUAGE] = language
        }
    }

    /**
     * Base language of the transcript track picked last, or
     * [CaptionTrackResolver.NO_PREFERRED_LANGUAGE]. Kept apart from the subtitle language, which
     * also decides which caption tracks load and when captions turn on.
     */
    val transcriptLanguage: Flow<String> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.TRANSCRIPT_LANGUAGE] ?: CaptionTrackResolver.NO_PREFERRED_LANGUAGE
            }.distinctUntilChanged()

    suspend fun setTranscriptLanguage(language: String) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.TRANSCRIPT_LANGUAGE] = language
        }
    }

    /** Turn captions on by themselves whenever the preferred language is available. */
    val autoEnableSubtitles: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.AUTO_ENABLE_SUBTITLES] ?: false
            }.distinctUntilChanged()

    suspend fun setAutoEnableSubtitles(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.AUTO_ENABLE_SUBTITLES] = enabled
        }
    }

    // Playback speed
    val playbackSpeed: Flow<Float> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.PLAYBACK_SPEED] ?: 1.0f
            }

    suspend fun setPlaybackSpeed(speed: Float) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            context.playerPreferencesDataStore.edit { preferences ->
                preferences[Keys.PLAYBACK_SPEED] = speed
            }
        }
    }

    val sleepTimerCloseAppOnExpiry: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SLEEP_TIMER_CLOSE_APP_ON_EXPIRY] ?: false
            }

    suspend fun setSleepTimerCloseAppOnExpiry(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SLEEP_TIMER_CLOSE_APP_ON_EXPIRY] = enabled
        }
    }

    // Remember playback speed
    val rememberPlaybackSpeed: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.REMEMBER_PLAYBACK_SPEED] ?: false
            }

    suspend fun setRememberPlaybackSpeed(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.REMEMBER_PLAYBACK_SPEED] = enabled
        }
    }

    val hiddenMusicHomeShelves: Flow<Set<String>> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.HIDDEN_MUSIC_HOME_SHELVES].orEmpty() }
            .distinctUntilChanged()

    suspend fun setMusicHomeShelfHidden(
        shelf: String,
        hidden: Boolean,
    ) {
        context.playerPreferencesDataStore.edit { preferences ->
            val current = preferences[Keys.HIDDEN_MUSIC_HOME_SHELVES].orEmpty()
            preferences[Keys.HIDDEN_MUSIC_HOME_SHELVES] = if (hidden) current + shelf else current - shelf
        }
    }

    val hiddenLibrarySections: Flow<Set<String>> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.HIDDEN_LIBRARY_SECTIONS].orEmpty() }
            .distinctUntilChanged()

    suspend fun setLibrarySectionHidden(
        section: String,
        hidden: Boolean,
    ) {
        context.playerPreferencesDataStore.edit { preferences ->
            val current = preferences[Keys.HIDDEN_LIBRARY_SECTIONS].orEmpty()
            preferences[Keys.HIDDEN_LIBRARY_SECTIONS] = if (hidden) current + section else current - section
        }
    }

    /** The Everything, Videos or Music tab the recap was last left on. */
    val recapSource: Flow<String?> = context.playerPreferencesDataStore.data.map { preferences -> preferences[Keys.RECAP_SOURCE] }

    suspend fun setRecapSource(source: String) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.RECAP_SOURCE] = source
        }
    }

    val autoDownloadLikedMusic: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.AUTO_DOWNLOAD_LIKED_MUSIC] ?: false }
            .distinctUntilChanged()

    suspend fun setAutoDownloadLikedMusic(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.AUTO_DOWNLOAD_LIKED_MUSIC] = enabled
        }
    }

    /** Music videos start at 1x whatever speed would otherwise apply. */
    val musicAtNormalSpeed: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.MUSIC_AT_NORMAL_SPEED] ?: false }
            .distinctUntilChanged()

    suspend fun setMusicAtNormalSpeed(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MUSIC_AT_NORMAL_SPEED] = enabled
        }
    }

    val musicVideoSwitch: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.MUSIC_VIDEO_SWITCH] ?: false }
            .distinctUntilChanged()

    suspend fun setMusicVideoSwitch(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MUSIC_VIDEO_SWITCH] = enabled
        }
    }

    val pauseMusicWhenMuted: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.PAUSE_MUSIC_WHEN_MUTED] ?: false }
            .distinctUntilChanged()

    suspend fun setPauseMusicWhenMuted(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.PAUSE_MUSIC_WHEN_MUTED] = enabled
        }
    }

    /** Whether a song blends into the next (#1675). Before the switch, a length above 0 seconds meant on. */
    val musicCrossfadeEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.MUSIC_CROSSFADE_ENABLED] ?: ((preferences[Keys.MUSIC_CROSSFADE_SECONDS] ?: 0) > 0)
            }.distinctUntilChanged()

    suspend fun setMusicCrossfadeEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MUSIC_CROSSFADE_ENABLED] = enabled
        }
    }

    /** How long a crossfade lasts, carried over from the length in seconds that settings saved before. */
    val musicCrossfadeMs: Flow<Int> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.MUSIC_CROSSFADE_MS]
                    ?: preferences[Keys.MUSIC_CROSSFADE_SECONDS]?.takeIf { it > 0 }?.times(MS_PER_CROSSFADE_SECOND)
                    ?: DEFAULT_CROSSFADE_MS
            }.distinctUntilChanged()

    suspend fun setMusicCrossfadeMs(ms: Int) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MUSIC_CROSSFADE_MS] = ms
        }
    }

    /** Tracks of one album play gapless even with crossfade on. */
    val musicCrossfadeKeepAlbums: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.MUSIC_CROSSFADE_KEEP_ALBUMS] ?: true }
            .distinctUntilChanged()

    suspend fun setMusicCrossfadeKeepAlbums(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MUSIC_CROSSFADE_KEEP_ALBUMS] = enabled
        }
    }

    /** Whether a song blends into the next when it ends by itself. */
    val musicCrossfadeOnAutoAdvance: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.MUSIC_CROSSFADE_AUTO_ADVANCE] ?: true }
            .distinctUntilChanged()

    suspend fun setMusicCrossfadeOnAutoAdvance(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MUSIC_CROSSFADE_AUTO_ADVANCE] = enabled
        }
    }

    /** Whether a song blends into the one the user skips to; off keeps Next immediate. */
    val musicCrossfadeOnManualSkip: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.MUSIC_CROSSFADE_MANUAL_SKIP] ?: true }
            .distinctUntilChanged()

    suspend fun setMusicCrossfadeOnManualSkip(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MUSIC_CROSSFADE_MANUAL_SKIP] = enabled
        }
    }

    /** How the two songs' volumes move during a crossfade. */
    val musicCrossfadeCurve: Flow<CrossfadeCurve> =
        context.playerPreferencesDataStore.data
            .map { preferences -> CrossfadeCurve.fromString(preferences[Keys.MUSIC_CROSSFADE_CURVE]) }
            .distinctUntilChanged()

    suspend fun setMusicCrossfadeCurve(curve: CrossfadeCurve) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MUSIC_CROSSFADE_CURVE] = curve.name
        }
    }

    /** Podcasts and other long tracks pick up where they stopped when played again. */
    val musicResumeLongTracks: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.MUSIC_RESUME_LONG_TRACKS] ?: false }
            .distinctUntilChanged()

    suspend fun setMusicResumeLongTracks(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MUSIC_RESUME_LONG_TRACKS] = enabled
        }
    }

    /** Vertical swipes on the music player's left and right thirds change speed and volume. */
    val musicSideSwipes: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.MUSIC_SIDE_SWIPES] ?: false }
            .distinctUntilChanged()

    suspend fun setMusicSideSwipes(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.MUSIC_SIDE_SWIPES] = enabled
        }
    }

    /** Whether a player with the cover and controls opens over the lock screen when the screen turns off during music. */
    val lockScreenPlayer: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.LOCK_SCREEN_PLAYER] ?: false }
            .distinctUntilChanged()

    suspend fun setLockScreenPlayer(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.LOCK_SCREEN_PLAYER] = enabled
        }
    }

    /** The app lock, for the whole device: a profile switch must not be a way past it. */
    val appLockSettings: Flow<AppLockSettings> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                AppLockSettings(
                    enabled = preferences[DeviceKeys.APP_LOCK_ENABLED] ?: false,
                    delayMinutes = preferences[DeviceKeys.APP_LOCK_DELAY_MINUTES] ?: DEFAULT_APP_LOCK_DELAY_MINUTES,
                    hideInRecents = preferences[DeviceKeys.APP_LOCK_HIDE_IN_RECENTS] ?: false,
                )
            }.distinctUntilChanged()

    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.APP_LOCK_ENABLED] = enabled
        }
    }

    suspend fun setAppLockDelayMinutes(minutes: Int) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.APP_LOCK_DELAY_MINUTES] = minutes
        }
    }

    suspend fun setAppLockHideInRecents(hide: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.APP_LOCK_HIDE_IN_RECENTS] = hide
        }
    }

    /** The music player's repeat mode, as Media3's own Player.REPEAT_MODE_* value. */
    val musicRepeatMode: Flow<Int> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.MUSIC_REPEAT_MODE] ?: 0 }
            .distinctUntilChanged()

    suspend fun setMusicRepeatMode(mode: Int) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MUSIC_REPEAT_MODE] = mode
        }
    }

    val animatedArtwork: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.ANIMATED_ARTWORK] ?: false }
            .distinctUntilChanged()

    suspend fun setAnimatedArtwork(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.ANIMATED_ARTWORK] = enabled
        }
    }

    val animatedArtworkWifiOnly: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.ANIMATED_ARTWORK_WIFI_ONLY] ?: true }
            .distinctUntilChanged()

    suspend fun setAnimatedArtworkWifiOnly(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.ANIMATED_ARTWORK_WIFI_ONLY] = enabled
        }
    }

    val speedPerChannel: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.SPEED_PER_CHANNEL] ?: false }
            .distinctUntilChanged()

    suspend fun setSpeedPerChannel(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SPEED_PER_CHANNEL] = enabled
        }
    }

    suspend fun channelPlaybackSpeed(channelId: String): Float? =
        context.playerPreferencesDataStore.data
            .first()[Keys.CHANNEL_PLAYBACK_SPEEDS]
            .orEmpty()
            .firstOrNull { it.startsWith("$channelId$CHANNEL_SPEED_SEPARATOR") }
            ?.substringAfter(CHANNEL_SPEED_SEPARATOR)
            ?.toFloatOrNull()

    suspend fun setChannelPlaybackSpeed(
        channelId: String,
        speed: Float,
    ) {
        withContext(kotlinx.coroutines.NonCancellable) {
            context.playerPreferencesDataStore.edit { preferences ->
                val others =
                    preferences[Keys.CHANNEL_PLAYBACK_SPEEDS]
                        .orEmpty()
                        .filterNot { it.startsWith("$channelId$CHANNEL_SPEED_SEPARATOR") }
                preferences[Keys.CHANNEL_PLAYBACK_SPEEDS] = others.toSet() + "$channelId$CHANNEL_SPEED_SEPARATOR$speed"
            }
        }
    }

    // Subscription check interval (default: 360 minutes / 6 hours)
    val subscriptionCheckIntervalMinutes: Flow<Int> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SUBSCRIPTION_CHECK_INTERVAL_MINUTES] ?: 360
            }

    suspend fun setSubscriptionCheckIntervalMinutes(minutes: Int) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SUBSCRIPTION_CHECK_INTERVAL_MINUTES] = minutes
        }
    }

    // Custom playback speeds
    val customSpeedsEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.CUSTOM_SPEEDS_ENABLED] ?: false
            }

    suspend fun setCustomSpeedsEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.CUSTOM_SPEEDS_ENABLED] = enabled
        }
    }

    val customSpeedPresets: Flow<String> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.CUSTOM_SPEED_PRESETS] ?: ""
            }

    suspend fun setCustomSpeedPresets(presets: String) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.CUSTOM_SPEED_PRESETS] = presets
        }
    }

    val speedSliderEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SPEED_SLIDER_ENABLED] ?: false
            }

    suspend fun setSpeedSliderEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SPEED_SLIDER_ENABLED] = enabled
        }
    }

    val longPressPlaybackSpeed: Flow<Float> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                (preferences[DeviceKeys.LONG_PRESS_PLAYBACK_SPEED] ?: 2.0f)
                    .let { if (it <= 0f) 0f else it.coerceIn(0.1f, 4.0f) }
            }

    suspend fun setLongPressPlaybackSpeed(speed: Float) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.LONG_PRESS_PLAYBACK_SPEED] =
                if (speed <= 0f) 0f else speed.coerceIn(0.1f, 4.0f)
        }
    }

    val speedSwipeSide: Flow<SpeedSwipeSide> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.SPEED_SWIPE_SIDE]
                    ?.let { stored -> runCatching { SpeedSwipeSide.valueOf(stored) }.getOrNull() }
                    ?: SpeedSwipeSide.OFF
            }

    suspend fun setSpeedSwipeSide(side: SpeedSwipeSide) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.SPEED_SWIPE_SIDE] = side.name
        }
    }

    val speedSwipeStep: Flow<Float> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.SPEED_SWIPE_STEP]?.takeIf { it in SPEED_SWIPE_STEPS } ?: DEFAULT_SPEED_SWIPE_STEP
            }

    suspend fun setSpeedSwipeStep(step: Float) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.SPEED_SWIPE_STEP] = step.takeIf { it in SPEED_SWIPE_STEPS } ?: DEFAULT_SPEED_SWIPE_STEP
        }
    }

    // Subscriptions feed view mode
    val subsFullWidthView: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SUBS_FULL_WIDTH_VIEW] ?: false
            }

    suspend fun setSubsFullWidthView(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SUBS_FULL_WIDTH_VIEW] = enabled
        }
    }

    // Subscriptions channel sort mode (persisted as the enum name)
    val subsSortMode: Flow<String> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.SUBS_SORT_MODE] ?: "" }

    suspend fun setSubsSortMode(mode: String) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SUBS_SORT_MODE] = mode
        }
    }

    val selectedSubscriptionGroup: Flow<String?> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.SUBS_SELECTED_GROUP]?.takeIf { it.isNotBlank() } }

    suspend fun setSelectedSubscriptionGroup(groupName: String?) {
        context.playerPreferencesDataStore.edit { preferences ->
            if (groupName.isNullOrBlank()) {
                preferences.remove(Keys.SUBS_SELECTED_GROUP)
            } else {
                preferences[Keys.SUBS_SELECTED_GROUP] = groupName
            }
        }
    }

    val subscriptionRefreshOnStartup: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.SUBS_REFRESH_ON_STARTUP] ?: false }

    suspend fun setSubscriptionRefreshOnStartup(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SUBS_REFRESH_ON_STARTUP] = enabled
        }
    }

    val subscriptionLastRefreshTime: Flow<Long> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.SUBS_LAST_REFRESH_TIME] ?: 0L }

    val subscriptionLastRefreshedCount: Flow<Int> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.SUBS_LAST_REFRESHED_COUNT] ?: 0 }

    val showChannelGroupBadges: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.SHOW_CHANNEL_GROUP_BADGES] ?: false }

    suspend fun setShowChannelGroupBadges(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SHOW_CHANNEL_GROUP_BADGES] = enabled
        }
    }

    val subscriptionShowCheckedVideoCount: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.SUBS_SHOW_CHECKED_VIDEO_COUNT] ?: true }

    suspend fun setSubscriptionShowCheckedVideoCount(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SUBS_SHOW_CHECKED_VIDEO_COUNT] = enabled
        }
    }

    suspend fun setSubscriptionLastRefresh(
        timeMillis: Long,
        count: Int,
    ) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SUBS_LAST_REFRESH_TIME] = timeMillis
            preferences[Keys.SUBS_LAST_REFRESHED_COUNT] = count
        }
    }

    // ── Donation / support prompt ───────────────────────────────────────────
    val donationFirstLaunchTime: Flow<Long> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.DONATION_FIRST_LAUNCH_TIME] ?: 0L }

    val donationPromptLastShownTime: Flow<Long> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.DONATION_PROMPT_LAST_SHOWN] ?: 0L }

    val donationPromptDisabled: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.DONATION_PROMPT_DISABLED] ?: false }

    suspend fun setDonationFirstLaunchTime(timeMillis: Long) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.DONATION_FIRST_LAUNCH_TIME] = timeMillis
        }
    }

    suspend fun setDonationPromptShown(timeMillis: Long) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.DONATION_PROMPT_LAST_SHOWN] = timeMillis
        }
    }

    suspend fun setDonationPromptDisabled(disabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.DONATION_PROMPT_DISABLED] = disabled
        }
    }

    val navTabOrder: Flow<List<Int>> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.NAV_TAB_ORDER]
                    ?.split(",")
                    ?.mapNotNull { it.toIntOrNull() }
                    ?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_NAV_TAB_ORDER
            }

    suspend fun setNavTabOrder(order: List<Int>) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.NAV_TAB_ORDER] = order.distinct().joinToString(",")
        }
    }

    val defaultNavTabIndex: Flow<Int> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.DEFAULT_NAV_TAB_INDEX] ?: 0 }

    suspend fun setDefaultNavTabIndex(index: Int) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.DEFAULT_NAV_TAB_INDEX] = index
        }
    }

    val commentsPreviewEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.COMMENTS_PREVIEW_ENABLED] ?: true }

    suspend fun setCommentsPreviewEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.COMMENTS_PREVIEW_ENABLED] = enabled
        }
    }

    val timedCommentsFollow: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.TIMED_COMMENTS_FOLLOW] ?: true }

    suspend fun setTimedCommentsFollow(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.TIMED_COMMENTS_FOLLOW] = enabled
        }
    }

    val commentsEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.COMMENTS_ENABLED] ?: true }

    suspend fun setCommentsEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.COMMENTS_ENABLED] = enabled
        }
    }

    val liveChatEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.LIVE_CHAT_ENABLED] ?: true }
            .distinctUntilChanged()

    suspend fun setLiveChatEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.LIVE_CHAT_ENABLED] = enabled
        }
    }

    val subscriptionShowVideos: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.SUBSCRIPTION_SHOW_VIDEOS] ?: true }

    suspend fun setSubscriptionShowVideos(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SUBSCRIPTION_SHOW_VIDEOS] = enabled
        }
    }

    val subscriptionShowShorts: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.SUBSCRIPTION_SHOW_SHORTS] ?: true }

    val effectiveSubscriptionShowShorts: Flow<Boolean> =
        combine(shortsContentEnabled, subscriptionShowShorts) { master, own -> master && own }

    suspend fun setSubscriptionShowShorts(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SUBSCRIPTION_SHOW_SHORTS] = enabled
        }
    }

    val subscriptionShowLive: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.SUBSCRIPTION_SHOW_LIVE] ?: true }

    suspend fun setSubscriptionShowLive(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SUBSCRIPTION_SHOW_LIVE] = enabled
        }
    }

    // PiP Preferences
    val autoPipEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.AUTO_PIP_ENABLED] ?: true
            }

    suspend fun setAutoPipEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.AUTO_PIP_ENABLED] = enabled
        }
    }

    val manualPipButtonEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.MANUAL_PIP_BUTTON_ENABLED] ?: true // Default ON
            }

    suspend fun setManualPipButtonEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MANUAL_PIP_BUTTON_ENABLED] = enabled
        }
    }

    // Content filtering
    val hideWatchedVideosFromHome: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                resolveMigratedHideWatchedPreference(
                    splitValue = preferences[Keys.HIDE_WATCHED_HOME_FEED],
                    legacyValue = preferences[Keys.HIDE_WATCHED_VIDEOS],
                )
            }

    suspend fun setHideWatchedVideosFromHome(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.HIDE_WATCHED_HOME_FEED] = enabled
        }
    }

    val hideWatchedVideosFromSubscriptions: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                resolveMigratedHideWatchedPreference(
                    splitValue = preferences[Keys.HIDE_WATCHED_SUBSCRIPTIONS],
                    legacyValue = preferences[Keys.HIDE_WATCHED_VIDEOS],
                )
            }

    suspend fun setHideWatchedVideosFromSubscriptions(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.HIDE_WATCHED_SUBSCRIPTIONS] = enabled
        }
    }

    /** Watched Shorts leave the Home and Subscriptions shelves and every Shorts queue, whatever the video settings say. */
    val hideWatchedShorts: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.HIDE_WATCHED_SHORTS] ?: true }

    suspend fun setHideWatchedShorts(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.HIDE_WATCHED_SHORTS] = enabled
        }
    }

    // Defaults to ALMOST_FINISHED so long videos only disappear in their final minute instead of at a flat 90%.
    val watchedThreshold: Flow<WatchedThreshold> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                runCatching { WatchedThreshold.valueOf(preferences[Keys.WATCHED_THRESHOLD] ?: WatchedThreshold.ALMOST_FINISHED.name) }
                    .getOrDefault(WatchedThreshold.ALMOST_FINISHED)
            }

    suspend fun setWatchedThreshold(threshold: WatchedThreshold) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.WATCHED_THRESHOLD] = threshold.name
        }
    }

    /** What a video link shared to Flow from another app does; links tapped elsewhere always play. */
    val sharedLinkAction: Flow<SharedLinkAction> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                runCatching { SharedLinkAction.valueOf(preferences[Keys.SHARED_LINK_ACTION] ?: SharedLinkAction.ASK_WHILE_PLAYING.name) }
                    .getOrDefault(SharedLinkAction.ASK_WHILE_PLAYING)
            }

    suspend fun setSharedLinkAction(action: SharedLinkAction) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SHARED_LINK_ACTION] = action.name
        }
    }

    /** When ON, sharing a video link sends only the bare URL, without the "Check out this video" text. */
    val shareWithoutText: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SHARE_WITHOUT_TEXT] ?: false
            }

    suspend fun setShareWithoutText(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SHARE_WITHOUT_TEXT] = enabled
        }
    }

    /** When ON, a video leaves Watch later once it counts as watched under [watchedThreshold]. */
    val removeWatchedFromWatchLater: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.REMOVE_WATCHED_FROM_WATCH_LATER] ?: false
            }

    suspend fun setRemoveWatchedFromWatchLater(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.REMOVE_WATCHED_FROM_WATCH_LATER] = enabled
        }
    }

    val disableShortsPlayer: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.DISABLE_SHORTS_PLAYER] ?: false
            }

    val effectiveDisableShortsPlayer: Flow<Boolean> =
        combine(shortsContentEnabled, disableShortsPlayer) { master, own -> !master || own }

    suspend fun setDisableShortsPlayer(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.DISABLE_SHORTS_PLAYER] = enabled
        }
    }

    val showShortsPlayerPrompt: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SHOW_SHORTS_PLAYER_PROMPT] ?: true
            }

    suspend fun setShowShortsPlayerPrompt(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SHOW_SHORTS_PLAYER_PROMPT] = enabled
        }
    }

    // Shorts background playback (default OFF — pauses when app goes to background)
    val shortsBackgroundPlay: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SHORTS_BACKGROUND_PLAY] ?: false
            }

    suspend fun setShortsBackgroundPlay(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SHORTS_BACKGROUND_PLAY] = enabled
        }
    }

    val shortsPipEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SHORTS_PIP_ENABLED] ?: false
            }

    suspend fun setShortsPipEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SHORTS_PIP_ENABLED] = enabled
        }
    }

    // Shorts playback mode (default LOOP — repeats the current short)
    val shortsPlaybackMode: Flow<String> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SHORTS_PLAYBACK_MODE] ?: "loop"
            }

    suspend fun setShortsPlaybackMode(mode: String) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SHORTS_PLAYBACK_MODE] = mode
        }
    }

    val shortsAutoScrollSeconds: Flow<Int> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                (preferences[Keys.SHORTS_AUTO_SCROLL_SECONDS] ?: 10).coerceIn(5, 20)
            }

    suspend fun setShortsAutoScrollSeconds(seconds: Int) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SHORTS_AUTO_SCROLL_SECONDS] = seconds.coerceIn(5, 20)
        }
    }

    val shortsQueueContinuesIntoFeed: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SHORTS_QUEUE_CONTINUE_INTO_FEED] ?: true
            }

    suspend fun setShortsQueueContinuesIntoFeed(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SHORTS_QUEUE_CONTINUE_INTO_FEED] = enabled
        }
    }

    val subscriptionShortsExcludedChannels: Flow<Set<String>> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SUBSCRIPTION_SHORTS_EXCLUDED_CHANNELS].orEmpty()
            }

    suspend fun setSubscriptionShortsChannelExcluded(
        channelId: String,
        excluded: Boolean,
    ) {
        if (channelId.isBlank()) return
        context.playerPreferencesDataStore.edit { preferences ->
            val current = preferences[Keys.SUBSCRIPTION_SHORTS_EXCLUDED_CHANNELS].orEmpty()
            preferences[Keys.SUBSCRIPTION_SHORTS_EXCLUDED_CHANNELS] =
                if (excluded) current + channelId else current - channelId
        }
    }

    val upcomingVideoReminderIds: Flow<Set<String>> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.UPCOMING_VIDEO_REMINDER_IDS].orEmpty()
            }

    suspend fun setUpcomingVideoReminder(
        videoId: String,
        enabled: Boolean,
    ) {
        if (videoId.isBlank()) return
        context.playerPreferencesDataStore.edit { preferences ->
            val current = preferences[Keys.UPCOMING_VIDEO_REMINDER_IDS].orEmpty()
            preferences[Keys.UPCOMING_VIDEO_REMINDER_IDS] =
                if (enabled) current + videoId else current - videoId
            if (!enabled) preferences.writeReminders(preferences.readReminders().filterNot { it.videoId == videoId })
        }
    }

    /** The premiere reminders this profile set, with what it takes to queue them again. */
    val upcomingReminders: Flow<List<UpcomingReminder>> =
        context.playerPreferencesDataStore.data.map { preferences -> preferences.readReminders() }

    suspend fun saveUpcomingReminder(reminder: UpcomingReminder) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences.writeReminders(preferences.readReminders().filterNot { it.videoId == reminder.videoId } + reminder)
        }
    }

    private fun Preferences.readReminders(): List<UpcomingReminder> =
        this[Keys.UPCOMING_VIDEO_REMINDERS]
            ?.let { runCatching { ReminderJson.decodeFromString<List<UpcomingReminder>>(it) }.getOrNull() }
            .orEmpty()

    private fun MutablePreferences.writeReminders(reminders: List<UpcomingReminder>) {
        if (reminders.isEmpty()) {
            remove(Keys.UPCOMING_VIDEO_REMINDERS)
        } else {
            this[Keys.UPCOMING_VIDEO_REMINDERS] =
                ReminderJson.encodeToString(reminders)
        }
    }

    /** Opt-in: off by default, so the feed never hides anything unless the user asks for it. */
    val hideUnplayableVideosFromSubscriptions: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.HIDE_UNPLAYABLE_SUBSCRIPTIONS] ?: false
            }

    suspend fun setHideUnplayableVideosFromSubscriptions(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.HIDE_UNPLAYABLE_SUBSCRIPTIONS] = enabled
        }
    }

    /**
     * Videos the player has permanently given up on (restricted, removed, or otherwise
     * unplayable). Recorded regardless of the filter toggle so that enabling
     * [hideUnplayableVideosFromSubscriptions] takes effect retroactively.
     */
    val unplayableVideoIds: Flow<Set<String>> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.UNPLAYABLE_VIDEO_IDS].decodeUnplayableIds()
            }

    suspend fun markVideoUnplayable(videoId: String) {
        if (videoId.isBlank()) return
        context.devicePreferencesDataStore.edit { preferences ->
            val current = preferences[DeviceKeys.UNPLAYABLE_VIDEO_IDS].decodeUnplayableIds()
            if (current.firstOrNull() == videoId) return@edit
            val updated =
                LinkedHashSet<String>(current.size + 1)
                    .apply {
                        add(videoId)
                        addAll(current)
                    }.take(MAX_UNPLAYABLE_VIDEO_IDS)
            preferences[DeviceKeys.UNPLAYABLE_VIDEO_IDS] = updated.joinToString("\n")
        }
    }

    /**
     * Clears the flag when a video turns out to play fine after all. Reads before writing so the
     * common case (video was never flagged) costs no disk write — this runs on every playback start.
     */
    suspend fun clearVideoUnplayable(videoId: String) {
        if (videoId.isBlank()) return
        val current =
            context.devicePreferencesDataStore.data
                .first()[DeviceKeys.UNPLAYABLE_VIDEO_IDS]
                .decodeUnplayableIds()
        if (videoId !in current) return
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.UNPLAYABLE_VIDEO_IDS] =
                current.asSequence().filter { it != videoId }.joinToString("\n")
        }
    }

    /** The video and Shorts cache size in MB; 0 means unlimited. Stored under the original single-cache key. */
    val mediaCacheSizeMb: Flow<Int> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.MEDIA_CACHE_SIZE_MB] ?: MediaCacheSizes.DEFAULT_MEDIA_MB
            }

    suspend fun setMediaCacheSizeMb(sizeMb: Int) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.MEDIA_CACHE_SIZE_MB] = sizeMb
        }
    }

    /** The song cache size in MB; 0 means unlimited. */
    val musicCacheSizeMb: Flow<Int> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.MUSIC_CACHE_SIZE_MB] ?: MediaCacheSizes.DEFAULT_MEDIA_MB }

    suspend fun setMusicCacheSizeMb(sizeMb: Int) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.MUSIC_CACHE_SIZE_MB] = sizeMb
        }
    }

    /** The image cache size in MB; [MediaCacheSizes.ARTWORK_AUTOMATIC_MB] leaves it to Coil. */
    val artworkCacheSizeMb: Flow<Int> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.ARTWORK_CACHE_SIZE_MB] ?: MediaCacheSizes.ARTWORK_AUTOMATIC_MB }

    suspend fun setArtworkCacheSizeMb(sizeMb: Int) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.ARTWORK_CACHE_SIZE_MB] = sizeMb
        }
    }

    // Show region picker globe icon in CategoriesScreen top bar
    // Selected app icon — component suffix string saved on each icon switch so it can be backed up/restored
    val selectedAppIcon: Flow<String?> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.APP_ICON_SUFFIX]
            }

    suspend fun setSelectedAppIcon(suffix: String) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.APP_ICON_SUFFIX] = suffix
        }
    }

    // Video title max lines in the player info section — 0 means no limit (Int.MAX_VALUE)

    /**
     * [playlistId]'s own sort order. A playlist never sorted falls back to the single order older
     * versions kept for every playlist, so nothing changes until the person picks one.
     */
    fun playlistSortOrder(playlistId: String): Flow<String> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.PLAYLIST_SORT_ORDERS]
                    .orEmpty()
                    .firstOrNull { it.startsWith("$playlistId$PLAYLIST_SORT_SEPARATOR") }
                    ?.substringAfter(PLAYLIST_SORT_SEPARATOR)
                    ?: preferences[Keys.PLAYLIST_SORT_ORDER]
                    ?: "manual"
            }.distinctUntilChanged()

    suspend fun setPlaylistSortOrder(
        playlistId: String,
        order: String,
    ) {
        context.playerPreferencesDataStore.edit { preferences ->
            val others =
                preferences[Keys.PLAYLIST_SORT_ORDERS]
                    .orEmpty()
                    .filterNot { it.startsWith("$playlistId$PLAYLIST_SORT_SEPARATOR") }
            preferences[Keys.PLAYLIST_SORT_ORDERS] = others.toSet() + "$playlistId$PLAYLIST_SORT_SEPARATOR$order"
        }
    }

    val videoTitleMaxLines: Flow<Int> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.VIDEO_TITLE_MAX_LINES] ?: 1
            }

    suspend fun setVideoTitleMaxLines(lines: Int) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.VIDEO_TITLE_MAX_LINES] = lines
        }
    }

    // Video card inline like/dislike action buttons (default off)
    val videoCardActionsEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.VIDEO_CARD_ACTIONS_ENABLED] ?: false }

    suspend fun setVideoCardActionsEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.VIDEO_CARD_ACTIONS_ENABLED] = enabled
        }
    }

    // Video card inline mark-as-watched action controls (default off)
    val videoCardMarkWatchedEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.VIDEO_CARD_MARK_WATCHED_ENABLED] ?: false }

    suspend fun setVideoCardMarkWatchedEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.VIDEO_CARD_MARK_WATCHED_ENABLED] = enabled
        }
    }

    // Show app logo icon in home screen top bar (default on)
    val showAppLogoIcon: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.SHOW_APP_LOGO_ICON] ?: true }

    suspend fun setShowAppLogoIcon(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SHOW_APP_LOGO_ICON] = enabled
        }
    }

    // Screen-level view mode toggles
    val searchIsGridMode: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.SEARCH_IS_GRID_MODE] ?: false }

    suspend fun setSearchIsGridMode(isGrid: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SEARCH_IS_GRID_MODE] = isGrid
        }
    }

    val channelIsGridView: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.CHANNEL_IS_GRID_VIEW] ?: false }

    suspend fun setChannelIsGridView(isGrid: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.CHANNEL_IS_GRID_VIEW] = isGrid
        }
    }

    val categoriesIsListView: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.CATEGORIES_IS_LIST_VIEW] ?: false }

    suspend fun setCategoriesIsListView(isList: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.CATEGORIES_IS_LIST_VIEW] = isList
        }
    }

    // Buffer Preferences - Optimized for fast startup while maintaining stability
    // These are the defaults that balance quick playback start with smooth streaming
    val minBufferMs: Flow<Int> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.MIN_BUFFER_MS] ?: BufferProfile.STABLE.minBuffer
            }

    suspend fun setMinBufferMs(ms: Int) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.MIN_BUFFER_MS] = ms
        }
    }

    val maxBufferMs: Flow<Int> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.MAX_BUFFER_MS] ?: BufferProfile.STABLE.maxBuffer
            }

    suspend fun setMaxBufferMs(ms: Int) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.MAX_BUFFER_MS] = ms
        }
    }

    val bufferForPlaybackMs: Flow<Int> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.BUFFER_FOR_PLAYBACK_MS] ?: BufferProfile.STABLE.playbackBuffer
            }

    suspend fun setBufferForPlaybackMs(ms: Int) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.BUFFER_FOR_PLAYBACK_MS] = ms
        }
    }

    val bufferForPlaybackAfterRebufferMs: Flow<Int> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS] ?: BufferProfile.STABLE.rebufferBuffer
            }

    suspend fun setBufferForPlaybackAfterRebufferMs(ms: Int) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS] = ms
        }
    }

    val bufferProfile: Flow<BufferProfile> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                BufferProfile.fromString(preferences[DeviceKeys.BUFFER_PROFILE] ?: "STABLE")
            }

    suspend fun setBufferProfile(profile: BufferProfile) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.BUFFER_PROFILE] = profile.name

            // If not custom, apply the profile values immediately
            if (profile != BufferProfile.CUSTOM) {
                preferences[DeviceKeys.MIN_BUFFER_MS] = profile.minBuffer
                preferences[DeviceKeys.MAX_BUFFER_MS] = profile.maxBuffer
                preferences[DeviceKeys.BUFFER_FOR_PLAYBACK_MS] = profile.playbackBuffer
                preferences[DeviceKeys.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS] = profile.rebufferBuffer
            }
        }
    }

    // Download Preferences
    val downloadThreads: Flow<Int> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.DOWNLOAD_THREADS] ?: 3
            }

    suspend fun setDownloadThreads(threads: Int) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.DOWNLOAD_THREADS] = threads
        }
    }

    /** How many downloads transfer at the same time. */
    val concurrentDownloads: Flow<Int> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                (preferences[DeviceKeys.CONCURRENT_DOWNLOADS] ?: DEFAULT_CONCURRENT_DOWNLOADS).coerceIn(1, MAX_CONCURRENT_DOWNLOADS)
            }

    suspend fun setConcurrentDownloads(count: Int) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.CONCURRENT_DOWNLOADS] = count.coerceIn(1, MAX_CONCURRENT_DOWNLOADS)
        }
    }

    // Download dialog style (Classic full dialog vs new Compact dialog)
    val downloadDialogStyle: Flow<DownloadDialogStyle> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                runCatching { DownloadDialogStyle.valueOf(preferences[DeviceKeys.DOWNLOAD_DIALOG_STYLE] ?: DownloadDialogStyle.FULL.name) }
                    .getOrDefault(DownloadDialogStyle.FULL)
            }

    suspend fun setDownloadDialogStyle(style: DownloadDialogStyle) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.DOWNLOAD_DIALOG_STYLE] = style.name
        }
    }

    /** Download opens the download menu; off, it downloads the default choice at once. */
    val downloadAskEveryTime: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.DOWNLOAD_ASK_EVERY_TIME] ?: true }

    suspend fun setDownloadAskEveryTime(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.DOWNLOAD_ASK_EVERY_TIME] = enabled
        }
    }

    /** The space used and free, shown at the top of the Downloads page. */
    val downloadsStorageCard: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.DOWNLOADS_STORAGE_CARD] ?: true }

    suspend fun setDownloadsStorageCard(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.DOWNLOADS_STORAGE_CARD] = enabled
        }
    }

    /** Delete in the Downloads menus asks first; a swipe never does, its snackbar can undo it. */
    val downloadsConfirmDelete: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.DOWNLOADS_CONFIRM_DELETE] ?: true }

    suspend fun setDownloadsConfirmDelete(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.DOWNLOADS_CONFIRM_DELETE] = enabled
        }
    }

    /** Whether a video the viewer opens is also saved for offline, and on which networks. */
    val autoDownloadOpenedVideos: Flow<AutoDownloadMode> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                runCatching { AutoDownloadMode.valueOf(preferences[DeviceKeys.AUTO_DOWNLOAD_OPENED_VIDEOS] ?: AutoDownloadMode.OFF.name) }
                    .getOrDefault(AutoDownloadMode.OFF)
            }.distinctUntilChanged()

    suspend fun setAutoDownloadOpenedVideos(mode: AutoDownloadMode) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.AUTO_DOWNLOAD_OPENED_VIDEOS] = mode.name
        }
    }

    // Remembered last-used download options (used by the compact dialog to preselect).
    val lastDownloadType: Flow<String?> =
        context.devicePreferencesDataStore.data
            .map { it[DeviceKeys.LAST_DOWNLOAD_TYPE] }
    val lastDownloadHeight: Flow<Int?> =
        context.devicePreferencesDataStore.data
            .map { it[DeviceKeys.LAST_DOWNLOAD_HEIGHT] }
    val lastDownloadCodec: Flow<String?> =
        context.devicePreferencesDataStore.data
            .map { it[DeviceKeys.LAST_DOWNLOAD_CODEC] }
    val lastDownloadAudioLabel: Flow<String?> =
        context.devicePreferencesDataStore.data
            .map { it[DeviceKeys.LAST_DOWNLOAD_AUDIO_LABEL] }

    suspend fun setLastDownloadVideoChoice(
        height: Int,
        codec: String,
    ) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.LAST_DOWNLOAD_TYPE] = "VIDEO"
            preferences[DeviceKeys.LAST_DOWNLOAD_HEIGHT] = height
            preferences[DeviceKeys.LAST_DOWNLOAD_CODEC] = codec
        }
    }

    suspend fun setLastDownloadAudioChoice(audioLabel: String) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.LAST_DOWNLOAD_TYPE] = "AUDIO"
            preferences[DeviceKeys.LAST_DOWNLOAD_AUDIO_LABEL] = audioLabel
        }
    }

    /** The subtitle language last chosen in the download dialog; blank for none, null if never chosen. */
    val lastDownloadSubtitleLanguage: Flow<String?> =
        context.devicePreferencesDataStore.data
            .map { it[DeviceKeys.LAST_DOWNLOAD_SUBTITLE_LANGUAGE] }

    suspend fun setLastDownloadSubtitleLanguage(languageTag: String) {
        context.devicePreferencesDataStore.edit { it[DeviceKeys.LAST_DOWNLOAD_SUBTITLE_LANGUAGE] = languageTag }
    }

    /** Whether downloads started without the dialog save a subtitle file in the preferred language. */
    val downloadSubtitleFile: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { it[DeviceKeys.DOWNLOAD_SUBTITLE_FILE] ?: false }

    suspend fun setDownloadSubtitleFile(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { it[DeviceKeys.DOWNLOAD_SUBTITLE_FILE] = enabled }
    }

    val downloadOverWifiOnly: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.DOWNLOAD_OVER_WIFI_ONLY] ?: false
            }

    suspend fun setDownloadOverWifiOnly(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.DOWNLOAD_OVER_WIFI_ONLY] = enabled
        }
    }

    val defaultDownloadQuality: Flow<VideoQuality> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                VideoQuality.fromString(preferences[DeviceKeys.DEFAULT_DOWNLOAD_QUALITY] ?: "720p")
            }

    suspend fun setDefaultDownloadQuality(quality: VideoQuality) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.DEFAULT_DOWNLOAD_QUALITY] = quality.label
        }
    }

    val defaultDownloadCodec: Flow<VideoCodec> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                VideoCodec.fromString(preferences[DeviceKeys.DEFAULT_DOWNLOAD_CODEC] ?: VideoCodec.AUTO.label)
            }

    suspend fun setDefaultDownloadCodec(codec: VideoCodec) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.DEFAULT_DOWNLOAD_CODEC] = codec.label
        }
    }

    /** The audio quality songs are downloaded in, apart from the streaming one; there is no Auto. */
    val musicDownloadQuality: Flow<MusicAudioQuality> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.MUSIC_DOWNLOAD_QUALITY]
                    ?.let(MusicAudioQuality::fromString)
                    ?.takeIf { it != MusicAudioQuality.AUTO }
                    ?: MusicAudioQuality.HIGH
            }

    suspend fun setMusicDownloadQuality(quality: MusicAudioQuality) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.MUSIC_DOWNLOAD_QUALITY] = quality.label
        }
    }

    /** What the one pass that tagged older downloads did, once it has run; null before. */
    val downloadRetagResult: Flow<RetagResult?> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.DOWNLOAD_RETAG_RESULT]
                    ?.split(',')
                    ?.mapNotNull(String::toIntOrNull)
                    ?.takeIf { it.size == 2 }
                    ?.let { (tagged, skipped) -> RetagResult(tagged, skipped) }
            }

    suspend fun hasDownloadRetagResult(): Boolean = downloadRetagResult.first() != null

    suspend fun setDownloadRetagResult(result: RetagResult) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.DOWNLOAD_RETAG_RESULT] = "${result.tagged},${result.skipped}"
        }
    }

    /** The folder video downloads go to; unset means the default folder. */
    val downloadLocation: Flow<DownloadLocation> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                DownloadLocation(preferences[DeviceKeys.DOWNLOAD_LOCATION], preferences[DeviceKeys.DOWNLOAD_LOCATION_TREE])
            }

    suspend fun setDownloadLocation(location: DownloadLocation) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences.putOrRemove(DeviceKeys.DOWNLOAD_LOCATION, location.path)
            preferences.putOrRemove(DeviceKeys.DOWNLOAD_LOCATION_TREE, location.treeUri)
        }
    }

    /** The folder music downloads go to; unset means they follow [downloadLocation]. */
    val musicDownloadLocation: Flow<DownloadLocation> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                DownloadLocation(preferences[DeviceKeys.MUSIC_DOWNLOAD_LOCATION], preferences[DeviceKeys.MUSIC_DOWNLOAD_LOCATION_TREE])
            }

    suspend fun setMusicDownloadLocation(location: DownloadLocation) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences.putOrRemove(DeviceKeys.MUSIC_DOWNLOAD_LOCATION, location.path)
            preferences.putOrRemove(DeviceKeys.MUSIC_DOWNLOAD_LOCATION_TREE, location.treeUri)
        }
    }

    private fun MutablePreferences.putOrRemove(
        key: Preferences.Key<String>,
        value: String?,
    ) {
        if (value.isNullOrBlank()) remove(key) else this[key] = value
    }

    val proxyEnabled: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.PROXY_ENABLED] ?: false
            }

    suspend fun setProxyEnabled(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.PROXY_ENABLED] = enabled
        }
    }

    val proxyType: Flow<AppProxyType> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                AppProxyType.fromStorageValue(preferences[DeviceKeys.PROXY_TYPE])
            }

    suspend fun setProxyType(type: AppProxyType) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.PROXY_TYPE] = type.storageValue
        }
    }

    val proxyHost: Flow<String> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.PROXY_HOST].orEmpty()
            }

    suspend fun setProxyHost(host: String) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.PROXY_HOST] = host.trim()
        }
    }

    val proxyPort: Flow<Int> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.PROXY_PORT] ?: 8080
            }

    suspend fun setProxyPort(port: Int) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.PROXY_PORT] = port
        }
    }

    val proxyUsername: Flow<String> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.PROXY_USERNAME].orEmpty()
            }

    suspend fun setProxyUsername(username: String) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.PROXY_USERNAME] = username.trim()
        }
    }

    val proxyConfig: Flow<AppProxyConfig> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                AppProxyConfig(
                    enabled = preferences[DeviceKeys.PROXY_ENABLED] ?: false,
                    type = AppProxyType.fromStorageValue(preferences[DeviceKeys.PROXY_TYPE]),
                    host = preferences[DeviceKeys.PROXY_HOST].orEmpty(),
                    port = preferences[DeviceKeys.PROXY_PORT] ?: 8080,
                    username = preferences[DeviceKeys.PROXY_USERNAME].orEmpty(),
                    password = KeystoreSecretBox.open(preferences[DeviceKeys.PROXY_PASSWORD]),
                    bypassOnVpn = preferences[DeviceKeys.PROXY_BYPASS_ON_VPN] ?: false,
                )
            }.flowOn(Dispatchers.IO)

    suspend fun getProxyConfig(): AppProxyConfig = proxyConfig.first()

    suspend fun setProxyConfig(config: AppProxyConfig) {
        val sealed = withContext(Dispatchers.IO) { KeystoreSecretBox.seal(config.password) }
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.PROXY_ENABLED] = config.enabled
            preferences[DeviceKeys.PROXY_TYPE] = config.type.storageValue
            preferences[DeviceKeys.PROXY_HOST] = config.host.trim()
            preferences[DeviceKeys.PROXY_PORT] = config.port
            preferences[DeviceKeys.PROXY_USERNAME] = config.username.trim()
            preferences[DeviceKeys.PROXY_BYPASS_ON_VPN] = config.bypassOnVpn
            if (config.password.isEmpty()) {
                preferences.remove(DeviceKeys.PROXY_PASSWORD)
            } else {
                preferences[DeviceKeys.PROXY_PASSWORD] = sealed
            }
        }
    }

    // Return YouTube Dislikes
    val rytdEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.RYTD_ENABLED] ?: true
            }

    suspend fun setRytdEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.RYTD_ENABLED] = enabled
        }
    }

    // Surface timeout
    val surfaceReadyTimeoutMs: Flow<Long> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.SURFACE_READY_TIMEOUT_MS] ?: 1500L // Default 1.5s
            }

    suspend fun setSurfaceReadyTimeoutMs(timeoutMs: Long) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.SURFACE_READY_TIMEOUT_MS] = timeoutMs
        }
    }

    // Lyrics Provider ordering and enable/disable
    val lyricsProviderOrder: Flow<String> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.LYRICS_PROVIDER_ORDER] ?: ""
            }

    suspend fun setLyricsProviderOrder(order: String) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.LYRICS_PROVIDER_ORDER] = order
        }
    }

    private val providerEnabledKeys =
        mapOf(
            "BetterLyrics" to Keys.LYRICS_PROVIDER_ENABLED_BETTERLYRICS,
            "SimpMusic" to Keys.LYRICS_PROVIDER_ENABLED_SIMPMUSIC,
            "LyricsPlus" to Keys.LYRICS_PROVIDER_ENABLED_LYRICSPLUS,
            "LrcLib" to Keys.LYRICS_PROVIDER_ENABLED_LRCLIB,
            "YouTube" to Keys.LYRICS_PROVIDER_ENABLED_YOUTUBE,
            "KuGou" to Keys.LYRICS_PROVIDER_ENABLED_KUGOU,
            "Paxsenix" to Keys.LYRICS_PROVIDER_ENABLED_PAXSENIX,
            "YouTubeSubtitle" to Keys.LYRICS_PROVIDER_ENABLED_YOUTUBESUBTITLE,
            "LrcRed" to Keys.LYRICS_PROVIDER_ENABLED_LRCRED,
            "Unison" to Keys.LYRICS_PROVIDER_ENABLED_UNISON,
            "NetEase" to Keys.LYRICS_PROVIDER_ENABLED_NETEASE,
            "AMLL" to Keys.LYRICS_PROVIDER_ENABLED_AMLL,
        )

    fun isLyricsProviderEnabled(providerName: String): Flow<Boolean> =
        context.playerPreferencesDataStore.data.map { preferences ->
            val key = providerEnabledKeys[providerName] ?: return@map true
            preferences[key] ?: true
        }

    suspend fun setLyricsProviderEnabled(
        providerName: String,
        enabled: Boolean,
    ) {
        val key = providerEnabledKeys[providerName] ?: return
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[key] = enabled
        }
    }

    fun allLyricsProviderEnabledStates(): Flow<Map<String, Boolean>> =
        context.playerPreferencesDataStore.data.map { preferences ->
            providerEnabledKeys.mapValues { (_, key) -> preferences[key] ?: true }
        }

    val lyricsTextAlign: Flow<String> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.LYRICS_TEXT_ALIGN] ?: LYRICS_ALIGN_CENTER
            }

    suspend fun setLyricsTextAlign(align: String) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.LYRICS_TEXT_ALIGN] = align
        }
    }

    val lyricsShowTranslation: Flow<Boolean> =
        context.playerPreferencesDataStore.data.map { it[Keys.LYRICS_SHOW_TRANSLATION] ?: true }

    suspend fun setLyricsShowTranslation(show: Boolean) {
        context.playerPreferencesDataStore.edit { it[Keys.LYRICS_SHOW_TRANSLATION] = show }
    }

    val lyricsShowRomanization: Flow<Boolean> =
        context.playerPreferencesDataStore.data.map { it[Keys.LYRICS_SHOW_ROMANIZATION] ?: true }

    suspend fun setLyricsShowRomanization(show: Boolean) {
        context.playerPreferencesDataStore.edit { it[Keys.LYRICS_SHOW_ROMANIZATION] = show }
    }

    val lyricsOnLockScreen: Flow<Boolean> =
        context.playerPreferencesDataStore.data.map { it[Keys.LYRICS_ON_LOCK_SCREEN] ?: false }

    suspend fun setLyricsOnLockScreen(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { it[Keys.LYRICS_ON_LOCK_SCREEN] = enabled }
    }

    /** Whether lyrics are looked up only when the player or its lyrics open, or for lock screen lyrics. */
    val lyricsOnDemand: Flow<Boolean> =
        context.playerPreferencesDataStore.data.map { it[Keys.LYRICS_ON_DEMAND] ?: false }

    suspend fun setLyricsOnDemand(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { it[Keys.LYRICS_ON_DEMAND] = enabled }
    }

    /** How late lock screen lines appear, in milliseconds. Below zero they appear early. */
    val lyricsOnLockScreenDelayMs: Flow<Int> =
        context.playerPreferencesDataStore.data.map { it[Keys.LYRICS_ON_LOCK_SCREEN_DELAY_MS] ?: 0 }

    suspend fun setLyricsOnLockScreenDelayMs(delayMs: Int) {
        context.playerPreferencesDataStore.edit { it[Keys.LYRICS_ON_LOCK_SCREEN_DELAY_MS] = delayMs }
    }

    /** Whether lyrics skip a music video's non-music parts. Each song played is looked up on SponsorBlock while on. */
    val lyricsSponsorBlockTiming: Flow<Boolean> =
        context.playerPreferencesDataStore.data.map { it[Keys.LYRICS_SPONSORBLOCK_TIMING] ?: false }

    suspend fun setLyricsSponsorBlockTiming(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { it[Keys.LYRICS_SPONSORBLOCK_TIMING] = enabled }
    }

    /** Whether lyrics in other scripts get a Latin-script line made on the device when the source has none. */
    val lyricsAutoRomanize: Flow<Boolean> =
        context.playerPreferencesDataStore.data.map { it[Keys.LYRICS_AUTO_ROMANIZE] ?: false }

    suspend fun setLyricsAutoRomanize(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { it[Keys.LYRICS_AUTO_ROMANIZE] = enabled }
    }

    /** Whether a line's Latin-script version takes the place of the original instead of sitting under it. */
    val lyricsRomanizationOnly: Flow<Boolean> =
        context.playerPreferencesDataStore.data.map { it[Keys.LYRICS_ROMANIZATION_ONLY] ?: false }

    suspend fun setLyricsRomanizationOnly(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { it[Keys.LYRICS_ROMANIZATION_ONLY] = enabled }
    }

    val lyricsKeepScreenOn: Flow<Boolean> =
        context.playerPreferencesDataStore.data.map { it[Keys.LYRICS_KEEP_SCREEN_ON] ?: false }

    suspend fun setLyricsKeepScreenOn(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { it[Keys.LYRICS_KEEP_SCREEN_ON] = enabled }
    }

    val lyricsBackground: Flow<LyricsBackgroundStyle> =
        context.playerPreferencesDataStore.data.map { LyricsBackgroundStyle.fromStored(it[Keys.LYRICS_BACKGROUND]) }

    suspend fun setLyricsBackground(style: LyricsBackgroundStyle) {
        context.playerPreferencesDataStore.edit { it[Keys.LYRICS_BACKGROUND] = style.name }
    }

    val lyricsBlurOtherLines: Flow<Boolean> =
        context.playerPreferencesDataStore.data.map { it[Keys.LYRICS_BLUR_OTHER_LINES] ?: true }

    suspend fun setLyricsBlurOtherLines(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { it[Keys.LYRICS_BLUR_OTHER_LINES] = enabled }
    }

    // ========== MINI PLAYER PREFERENCES ==========

    val miniPlayerScale: Flow<Float> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.MINI_PLAYER_SCALE] ?: 0.45f
            }

    suspend fun setMiniPlayerScale(scale: Float) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MINI_PLAYER_SCALE] = scale
        }
    }

    val miniPlayerContinueWatchingEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.MINI_PLAYER_CONTINUE_WATCHING_ENABLED] ?: true
            }

    suspend fun setMiniPlayerContinueWatchingEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MINI_PLAYER_CONTINUE_WATCHING_ENABLED] = enabled
        }
    }

    val minimizeToAudioBar: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.MINIMIZE_TO_AUDIO_BAR] ?: false
            }

    suspend fun setMinimizeToAudioBar(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MINIMIZE_TO_AUDIO_BAR] = enabled
        }
    }

    val showRestoredMusicMiniPlayer: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SHOW_RESTORED_MUSIC_MINI_PLAYER] ?: true
            }

    suspend fun setShowRestoredMusicMiniPlayer(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SHOW_RESTORED_MUSIC_MINI_PLAYER] = enabled
        }
    }

    /** Whether starting a song opens the full player; off keeps it in the mini player. */
    val openMusicPlayerOnPlay: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.OPEN_MUSIC_PLAYER_ON_PLAY] ?: false
            }

    suspend fun setOpenMusicPlayerOnPlay(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.OPEN_MUSIC_PLAYER_ON_PLAY] = enabled
        }
    }

    val playDuringCalls: Flow<Boolean> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                preferences[DeviceKeys.PLAY_DURING_CALLS] ?: false
            }

    suspend fun setPlayDuringCalls(enabled: Boolean) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.PLAY_DURING_CALLS] = enabled
        }
    }

    val miniPlayerShowSkipControls: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.MINI_PLAYER_SHOW_SKIP_CONTROLS] ?: false
            }

    suspend fun setMiniPlayerShowSkipControls(show: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MINI_PLAYER_SHOW_SKIP_CONTROLS] = show
        }
    }

    val miniPlayerShowNextPrevControls: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.MINI_PLAYER_SHOW_NEXT_PREV_CONTROLS] ?: false
            }

    suspend fun setMiniPlayerShowNextPrevControls(show: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.MINI_PLAYER_SHOW_NEXT_PREV_CONTROLS] = show
        }
    }

    /** What swiping the music bar or the background video bar to the left does. */
    val miniBarSwipeLeftAction: Flow<MiniBarSwipeAction> =
        context.devicePreferencesDataStore.data
            .map { preferences -> MiniBarSwipeAction.fromString(preferences[DeviceKeys.MINI_BAR_SWIPE_LEFT_ACTION]) }

    suspend fun setMiniBarSwipeLeftAction(action: MiniBarSwipeAction) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.MINI_BAR_SWIPE_LEFT_ACTION] = action.name
        }
    }

    /** What swiping the music bar or the background video bar to the right does. */
    val miniBarSwipeRightAction: Flow<MiniBarSwipeAction> =
        context.devicePreferencesDataStore.data
            .map { preferences -> MiniBarSwipeAction.fromString(preferences[DeviceKeys.MINI_BAR_SWIPE_RIGHT_ACTION]) }

    suspend fun setMiniBarSwipeRightAction(action: MiniBarSwipeAction) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.MINI_BAR_SWIPE_RIGHT_ACTION] = action.name
        }
    }

    /** What swiping a song in the music queue to the left does. */
    val queueSwipeLeftAction: Flow<QueueSwipeAction> =
        context.devicePreferencesDataStore.data
            .map { preferences -> QueueSwipeAction.fromString(preferences[DeviceKeys.QUEUE_SWIPE_LEFT_ACTION], QueueSwipeAction.PLAY_NEXT) }

    suspend fun setQueueSwipeLeftAction(action: QueueSwipeAction) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.QUEUE_SWIPE_LEFT_ACTION] = action.name
        }
    }

    /** What swiping a song in the music queue to the right does. */
    val queueSwipeRightAction: Flow<QueueSwipeAction> =
        context.devicePreferencesDataStore.data
            .map { preferences -> QueueSwipeAction.fromString(preferences[DeviceKeys.QUEUE_SWIPE_RIGHT_ACTION], QueueSwipeAction.REMOVE) }

    suspend fun setQueueSwipeRightAction(action: QueueSwipeAction) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.QUEUE_SWIPE_RIGHT_ACTION] = action.name
        }
    }

    // DEEP FLOW (INCOGNITO / NO-ENGINE) MODE

    val deepFlowActive: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.DEEP_FLOW_ACTIVE] ?: false }

    val deepFlowActivatedAt: Flow<Long> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.DEEP_FLOW_ACTIVATED_AT] ?: 0L }

    val deepFlowExpireHours: Flow<Int> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.DEEP_FLOW_EXPIRE_HOURS] ?: 4 }

    val deepFlowSaveToHistory: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.DEEP_FLOW_SAVE_HISTORY] ?: false }

    suspend fun setDeepFlowSaveToHistory(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.DEEP_FLOW_SAVE_HISTORY] = enabled
        }
    }

    suspend fun isDeepFlowSaveToHistoryEnabled(): Boolean =
        context.playerPreferencesDataStore.data.first()[Keys.DEEP_FLOW_SAVE_HISTORY] ?: false

    val deepFlowScrobble: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.DEEP_FLOW_SCROBBLE] ?: false }

    suspend fun setDeepFlowScrobble(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.DEEP_FLOW_SCROBBLE] = enabled
        }
    }

    /** Off by default: the search screen shows its Deep Flow switch only once this is turned on. */
    val deepFlowOnSearch: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.DEEP_FLOW_ON_SEARCH] ?: false }

    suspend fun setDeepFlowOnSearch(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.DEEP_FLOW_ON_SEARCH] = enabled
        }
    }

    suspend fun setDeepFlowActive(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.DEEP_FLOW_ACTIVE] = enabled
            if (enabled) {
                preferences[Keys.DEEP_FLOW_ACTIVATED_AT] = System.currentTimeMillis()
            } else {
                preferences[Keys.DEEP_FLOW_ACTIVATED_AT] = 0L
            }
        }
    }

    suspend fun setDeepFlowExpireHours(hours: Int) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.DEEP_FLOW_EXPIRE_HOURS] = hours
        }
    }

    // AUTO-BACKUP SETTINGS
    val autoBackupFrequency: Flow<LocalDataManager.AutoBackupFrequency> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                runCatching {
                    LocalDataManager.AutoBackupFrequency.valueOf(
                        preferences[DeviceKeys.AUTO_BACKUP_FREQUENCY] ?: LocalDataManager.AutoBackupFrequency.NONE.name,
                    )
                }.getOrDefault(LocalDataManager.AutoBackupFrequency.NONE)
            }

    suspend fun setAutoBackupFrequency(frequency: LocalDataManager.AutoBackupFrequency) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.AUTO_BACKUP_FREQUENCY] = frequency.name
        }
    }

    val autoBackupFolderUri: Flow<String?> =
        context.devicePreferencesDataStore.data
            .map { preferences -> preferences[DeviceKeys.AUTO_BACKUP_FOLDER_URI]?.takeIf { it.isNotBlank() } }

    suspend fun setAutoBackupFolderUri(uri: String?) {
        context.devicePreferencesDataStore.edit { preferences ->
            if (uri != null) {
                preferences[DeviceKeys.AUTO_BACKUP_FOLDER_URI] = uri
            } else {
                preferences.remove(DeviceKeys.AUTO_BACKUP_FOLDER_URI)
            }
        }
    }

    val autoBackupType: Flow<LocalDataManager.AutoBackupType> =
        context.devicePreferencesDataStore.data
            .map { preferences ->
                runCatching {
                    LocalDataManager.AutoBackupType.valueOf(
                        preferences[DeviceKeys.AUTO_BACKUP_TYPE] ?: LocalDataManager.AutoBackupType.APP_DATA.name,
                    )
                }.getOrDefault(LocalDataManager.AutoBackupType.APP_DATA)
            }

    suspend fun setAutoBackupType(type: LocalDataManager.AutoBackupType) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[DeviceKeys.AUTO_BACKUP_TYPE] = type.name
        }
    }

    /** Whether a watch may be written to history: paused history and Deep Flow both stop it. */
    suspend fun isWatchHistorySavingBlocked(): Boolean =
        watchHistoryPaused.first() || (isDeepFlowCurrentlyActive() && !isDeepFlowSaveToHistoryEnabled())

    suspend fun isDeepFlowCurrentlyActive(): Boolean {
        val prefs = context.playerPreferencesDataStore.data.first()
        val active = prefs[Keys.DEEP_FLOW_ACTIVE] ?: false
        if (!active) return false
        val activatedAt = prefs[Keys.DEEP_FLOW_ACTIVATED_AT] ?: 0L
        val expireHours = prefs[Keys.DEEP_FLOW_EXPIRE_HOURS] ?: 4
        if (expireHours == DEEP_FLOW_NEVER_EXPIRES_HOURS) return true
        val elapsedHours = (System.currentTimeMillis() - activatedAt) / 3_600_000.0
        val stillActive = elapsedHours < expireHours
        if (!stillActive) {
            setDeepFlowActive(false)
        }
        return stillActive
    }

    /** Without [includeDevice], only this profile's own settings, as a new profile copies them. */
    suspend fun getExportData(includeDevice: Boolean = true): SettingsBackup {
        val device =
            if (includeDevice) {
                context.devicePreferencesDataStore.data
                    .first()
                    .asMap()
            } else {
                emptyMap()
            }
        val stored =
            exportedPreferences(
                context.playerPreferencesDataStore.data
                    .first()
                    .asMap(),
                device,
                DeviceKeys.NAMES,
            )
        val strings = mutableMapOf<String, String>()
        val booleans = mutableMapOf<String, Boolean>()
        val ints = mutableMapOf<String, Int>()
        val floats = mutableMapOf<String, Float>()
        val longs = mutableMapOf<String, Long>()
        val stringSets = mutableMapOf<String, Set<String>>()

        stored.forEach { (key, value) ->
            if (key.name in BackupExcludedKeys) return@forEach
            when (value) {
                is String -> strings[key.name] = value
                is Boolean -> booleans[key.name] = value
                is Int -> ints[key.name] = value
                is Float -> floats[key.name] = value
                is Long -> longs[key.name] = value
                is Set<*> -> stringSets[key.name] = value.filterIsInstance<String>().toSet()
            }
        }
        return SettingsBackup(strings, booleans, ints, floats, longs, stringSets)
    }

    suspend fun restoreData(backup: SettingsBackup) {
        val (deviceBackup, profileBackup) = backup.splitBy { it in DeviceKeys.NAMES }
        context.playerPreferencesDataStore.edit { prefs -> prefs.restore(profileBackup) }
        context.devicePreferencesDataStore.edit { prefs -> prefs.restore(deviceBackup) }
        // The UI language belongs to the language manager; written here alone it would never show,
        // while YouTube's language follows what is shown (#1277).
        backup.strings[DeviceKeys.APP_LANGUAGE.name]
            ?.takeIf { AppLanguageManager.normalizeLanguageTag(it) != AppLanguageManager.appliedLanguage.value }
            ?.let { tag -> withContext(Dispatchers.Main.immediate) { AppLanguageManager.applyLanguageTag(context, tag) } }
    }
}

/**
 * The settings a backup carries. Device keys were copied out of the profile store, not moved, so the profile store's
 * own copies of them are stale and only the device store's count.
 */
internal fun exportedPreferences(
    profile: Map<Preferences.Key<*>, Any>,
    device: Map<Preferences.Key<*>, Any>,
    deviceNames: Set<String>,
): Map<Preferences.Key<*>, Any> = profile.filterKeys { it.name !in deviceNames } + device

private fun SettingsBackup.splitBy(predicate: (String) -> Boolean): Pair<SettingsBackup, SettingsBackup> =
    SettingsBackup(
        strings.filterKeys(predicate),
        booleans.filterKeys(predicate),
        ints.filterKeys(predicate),
        floats.filterKeys(predicate),
        longs.filterKeys(predicate),
        stringSets.filterKeys(predicate),
    ) to
        SettingsBackup(
            strings.filterKeys { !predicate(it) },
            booleans.filterKeys { !predicate(it) },
            ints.filterKeys { !predicate(it) },
            floats.filterKeys { !predicate(it) },
            longs.filterKeys { !predicate(it) },
            stringSets.filterKeys { !predicate(it) },
        )

private fun MutablePreferences.restore(backup: SettingsBackup) {
    fun <T> Map<String, T>.restorable() = filterKeys { it !in BackupExcludedKeys }
    backup.strings.restorable().forEach { (k, v) -> this[stringPreferencesKey(k)] = v }
    backup.booleans.restorable().forEach { (k, v) -> this[booleanPreferencesKey(k)] = v }
    backup.ints.restorable().forEach { (k, v) -> this[intPreferencesKey(k)] = v }
    backup.floats.restorable().forEach { (k, v) -> this[floatPreferencesKey(k)] = v }
    backup.longs.restorable().forEach { (k, v) -> this[longPreferencesKey(k)] = v }
    backup.stringSets.restorable().forEach { (k, v) -> this[stringSetPreferencesKey(k)] = v }
}

const val DEFAULT_NEW_SETTINGS_HIGHLIGHT = false
const val DEFAULT_NEW_SETTINGS_HIGHLIGHT_DAYS = 7

private val FirstSeenSerializer = MapSerializer(String.serializer(), Long.serializer())

/** Loud videos play at their own level unless the user turns levelling on. */
const val DEFAULT_VIDEO_VOLUME_NORMALIZATION = false

/** How the skip button looks over the video. Stored and synced by name. */
enum class SponsorBlockSkipButtonStyle {
    FULL,
    COMPACT,
    ICON_ONLY,
    ;

    companion object {
        fun fromStored(stored: String?): SponsorBlockSkipButtonStyle = entries.firstOrNull { it.name == stored } ?: FULL
    }
}

/**
 * What the player does with a SponsorBlock segment, in the order settings list them. The names are
 * stored and synced, so they never change: [IGNORE] is "show in seek bar" and [SHOW_TOAST] is "notify".
 */
enum class SponsorBlockAction {
    SKIP,
    SKIP_ONCE,
    SKIP_BUTTON,
    SHOW_TOAST,
    MUTE,
    IGNORE,
    DISABLED,
    ;

    companion object {
        fun fromString(name: String): SponsorBlockAction = entries.find { it.name == name } ?: SKIP
    }
}

const val DEFAULT_CROSSFADE_MS = 3_000
private const val MS_PER_CROSSFADE_SECOND = 1_000

/** How the two songs' volumes move during a crossfade, in the order settings list them. */
enum class CrossfadeCurve {
    EQUAL_POWER,
    S_CURVE,
    HOLD,
    GENTLE,
    ;

    companion object {
        fun fromString(name: String?): CrossfadeCurve = entries.find { it.name == name } ?: EQUAL_POWER
    }
}

enum class BufferProfile(
    val label: String,
    val minBuffer: Int,
    val maxBuffer: Int,
    val playbackBuffer: Int,
    val rebufferBuffer: Int,
) {
    // Fast Start: Prioritize quick playback start
    AGGRESSIVE("Fast Start", 5_000, 30_000, 500, 2_500),

    // Balanced: Good default for most connections
    STABLE("Balanced", 20_000, 50_000, 2_500, 5_000),

    // Data Saver: Minimize data usage with smaller buffers
    DATASAVER("Data Saver", 12_000, 25_000, 1_500, 3_000),

    // Custom: User-defined values
    CUSTOM("Custom", -1, -1, -1, -1),
    ;

    companion object {
        fun fromString(name: String): BufferProfile = values().find { it.name == name } ?: STABLE
    }
}

enum class VideoQuality(
    val label: String,
    val height: Int,
) {
    Q_144P("144p", 144),
    Q_240P("240p", 240),
    Q_360P("360p", 360),
    Q_480P("480p", 480),
    Q_720P("720p", 720),
    Q_1080P("1080p", 1080),
    Q_1440P("1440p", 1440),
    Q_2160P("2160p", 2160), // 4K
    AUTO("Auto", 0),
    ;

    companion object {
        fun fromString(label: String): VideoQuality = values().find { it.label == label } ?: AUTO

        fun fromHeight(height: Int): VideoQuality =
            values()
                .filter { it != AUTO }
                .minByOrNull { kotlin.math.abs(it.height - height) } ?: Q_720P
    }
}

enum class VideoCodec(
    val label: String,
    val codecKey: String,
) {
    AUTO("Auto", "auto"),
    H264("H.264", "h264"),
    VP9("VP9", "vp9"),
    AV1("AV1", "av1"),
    ;

    companion object {
        fun fromString(label: String): VideoCodec = values().find { it.label == label } ?: H264
    }
}

enum class MusicAudioQuality(
    val label: String,
) {
    AUTO("Auto"),
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low"),
    ;

    /** The quality to actually stream: Auto picks High on Wi-Fi and Medium on mobile data, like video does. */
    fun resolve(onWifi: Boolean): MusicAudioQuality =
        when {
            this != AUTO -> this
            onWifi -> HIGH
            else -> MEDIUM
        }

    companion object {
        fun fromString(label: String): MusicAudioQuality = values().find { it.label == label } ?: AUTO
    }
}

enum class SliderStyle {
    DEFAULT,
    THICK,
    COMPACT,
    SQUIGGLY,
    EXPRESSIVE_WAVY,
    SLIM,
}

enum class DownloadDialogStyle {
    FULL,
    COMPACT,
}

/** When an opened video is downloaded on its own: never, only on Wi-Fi, or on any network. */
enum class AutoDownloadMode {
    OFF,
    WIFI,
    ALWAYS,
}

/** Control colors for the music player when artwork colors are off. */
enum class MusicPlainControlColors {
    MONOCHROME,
    APP_THEME,
}

/** What the full-screen lyrics draw on. Stored by name. */
enum class LyricsBackgroundStyle {
    SOLID,
    COLORFUL,
    MOVING,
    ;

    companion object {
        fun fromStored(stored: String?): LyricsBackgroundStyle = entries.firstOrNull { it.name == stored } ?: SOLID
    }
}

enum class MusicPlayerBackgroundStyle {
    BLUR_GRADIENT,
    BLUR,
    GRADIENT,
    IMMERSIVE,
    DEFAULT,
}

/** Which side of the fullscreen player a vertical swipe changes the playback speed on, in place of brightness or volume. */
enum class SpeedSwipeSide {
    OFF,
    LEFT,
    RIGHT,
}

/** How much one step of a speed swipe changes the speed by. */
val SPEED_SWIPE_STEPS = listOf(0.05f, 0.1f, 0.25f)
const val DEFAULT_SPEED_SWIPE_STEP = 0.1f

/** How much of a landscape fullscreen player the side panel takes, as a share of the window width. */
enum class FullscreenSidePanelWidth(
    val fraction: Float,
) {
    NARROW(0.25f),
    SMALL(0.33f),
    DEFAULT(0.42f),
    WIDE(0.5f),
}

/**
 * How much of the player's width each double-tap seek side takes. [reachesEdges] lets taps count right
 * up to the screen edges instead of leaving them to the back gesture.
 */
enum class DoubleTapSeekZone(
    val sideFraction: Float,
    val reachesEdges: Boolean = false,
) {
    NORMAL(1f / 3f),
    NARROW(1f / 4f),
    EDGE_TO_EDGE(1f / 3f, reachesEdges = true),
}

/** A mini bar swipe. The music bar only acts on [CLOSE], [NEXT] and [PREVIOUS]; it closes on the rest. */
enum class MiniBarSwipeAction {
    CLOSE,
    NEXT,
    PREVIOUS,
    WATCH_LATER,
    LIKE,
    DOWNLOAD,
    NOT_INTERESTED,
    REMOVE_FROM_QUEUE,
    ;

    val appliesToMusic: Boolean get() = this == CLOSE || this == NEXT || this == PREVIOUS

    companion object {
        fun fromString(value: String?): MiniBarSwipeAction = entries.firstOrNull { it.name == value } ?: CLOSE
    }
}

/** A swipe on a song in the music queue. */
enum class QueueSwipeAction {
    PLAY_NEXT,
    MOVE_TO_END,
    REMOVE,
    ;

    companion object {
        fun fromString(
            value: String?,
            default: QueueSwipeAction,
        ): QueueSwipeAction = entries.firstOrNull { it.name == value } ?: default
    }
}

/** How the volume and brightness read-outs are drawn mid-gesture. */
enum class GestureOverlayStyle {
    CIRCULAR,
    VERTICAL,
    HORIZONTAL,
    MINIMAL,
}

enum class ShortsPlayerUiMode {
    DEFAULT,
    SIMPLE,
    IMPRESSIVE,
}

enum class SeekbarPaddingMode {
    FULL_WIDTH,
    SPACED,
    DEFAULT,
    CUSTOM,
}

/** Where the portrait player draws its thin progress bar while the controls are hidden (#1550). */
enum class IdleProgressBar {
    ON_VIDEO,
    BELOW_VIDEO,
    OFF,
}

internal fun resolveIdleProgressBar(stored: String?): IdleProgressBar =
    stored?.let { value -> IdleProgressBar.entries.firstOrNull { it.name == value } } ?: IdleProgressBar.ON_VIDEO

/** What a scrub shows above the bar: a filmstrip around the target, or the single frame under it. */
enum class ScrubPreviewStyle {
    STRIP,
    FRAME,
}

internal fun resolveScrubPreviewStyle(storedStyle: String?): ScrubPreviewStyle =
    storedStyle?.let { value -> runCatching { ScrubPreviewStyle.valueOf(value) }.getOrNull() }
        ?: ScrubPreviewStyle.STRIP

internal fun resolvePortraitSeekbarPaddingMode(storedMode: String?): SeekbarPaddingMode {
    val mode =
        storedMode?.let { value ->
            runCatching { SeekbarPaddingMode.valueOf(value) }.getOrNull()
        }
    return when (mode) {
        null -> SeekbarPaddingMode.FULL_WIDTH
        SeekbarPaddingMode.DEFAULT -> SeekbarPaddingMode.SPACED
        else -> mode
    }
}

internal fun resolveSeekbarHorizontalPaddingDp(
    mode: SeekbarPaddingMode,
    customPaddingDp: Int,
    defaultPaddingDp: Int,
    maxPaddingDp: Int,
): Int =
    when (mode) {
        SeekbarPaddingMode.FULL_WIDTH -> 0

        SeekbarPaddingMode.SPACED,
        SeekbarPaddingMode.DEFAULT,
        -> defaultPaddingDp

        SeekbarPaddingMode.CUSTOM -> customPaddingDp.coerceIn(0, maxPaddingDp)
    }

enum class HomeViewMode {
    GRID,
    LIST,
}

/**
 * How many cards the home grid puts on a row. [AUTO] keeps the responsive breakpoints; the rest
 * pin a count regardless of screen width. Only meaningful in [HomeViewMode.GRID].
 */
enum class HomeFeedColumns(
    val fixedCount: Int?,
) {
    AUTO(null),
    ONE(1),
    TWO(2),
    THREE(3),
}

enum class PlayerRelatedCardStyle {
    COMPACT,
    FULL_WIDTH,
}

enum class SharedLinkAction {
    /** Play the video straight away, replacing what plays. */
    PLAY,

    /** Offer the video's menu while something plays, so the queue is not lost; otherwise play it. */
    ASK_WHILE_PLAYING,

    /** Always offer the video's menu. */
    ALWAYS_ASK,
}

const val LYRICS_ALIGN_LEFT = "left"
const val LYRICS_ALIGN_CENTER = "center"
const val LYRICS_ALIGN_RIGHT = "right"
