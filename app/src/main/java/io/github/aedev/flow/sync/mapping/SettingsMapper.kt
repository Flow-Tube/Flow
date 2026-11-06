package io.github.aedev.flow.sync.mapping

import io.github.aedev.flow.data.audio.eq.EqStateJson
import io.github.aedev.flow.data.local.SettingsBackup
import io.github.aedev.flow.sync.canonical.CanonicalSetting
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Curated, device-independent settings whitelist. ONLY these
 * keys are ever put on the wire — device-specific values (paths, proxy creds, geometry,
 * caches, telemetry) are deliberately excluded. Each setting is stamped with when it last changed
 * here, recovered by [io.github.aedev.flow.sync.apply.SyncChangeTracker] (preferences keep no
 * per-key timestamps).
 *
 * Bridges via [SettingsBackup] (the typed maps already produced by `PlayerPreferences`).
 */
object SettingsMapper {
    enum class Ty { BOOL, STRING, FLOAT, INT, LONG }

    /** [applied] false: sent so other devices see it, never taken from them. */
    data class Entry(
        val canonical: String,
        val android: String,
        val type: Ty,
        val applied: Boolean = true,
    )

    val WHITELIST: List<Entry> =
        listOf(
            Entry("autoplay", "autoplay_enabled", Ty.BOOL),
            Entry("queue_autoplay", "queue_autoplay_enabled", Ty.BOOL),
            Entry("default_quality_wifi", "default_quality_wifi", Ty.STRING),
            Entry("default_quality_cellular", "default_quality_cellular", Ty.STRING),
            Entry("playback_speed", "playback_speed", Ty.FLOAT),
            Entry("sponsorblock_enabled", "sponsor_block_enabled", Ty.BOOL),
            Entry("sponsorblock_submit_enabled", "sb_submit_enabled", Ty.BOOL),
            Entry("sponsorblock_skip_notice_enabled", "sb_skip_notice_enabled", Ty.BOOL),
            Entry("sponsorblock_skip_button_style", "sb_skip_button_style", Ty.STRING),
            Entry("sponsorblock_action_sponsor", "sb_action_sponsor", Ty.STRING),
            Entry("sponsorblock_action_intro", "sb_action_intro", Ty.STRING),
            Entry("sponsorblock_action_outro", "sb_action_outro", Ty.STRING),
            Entry("sponsorblock_action_selfpromo", "sb_action_selfpromo", Ty.STRING),
            Entry("sponsorblock_action_interaction", "sb_action_interaction", Ty.STRING),
            Entry("sponsorblock_action_music_offtopic", "sb_action_music_offtopic", Ty.STRING),
            Entry("sponsorblock_action_filler", "sb_action_filler", Ty.STRING),
            Entry("sponsorblock_action_preview", "sb_action_preview", Ty.STRING),
            Entry("sponsorblock_action_exclusive_access", "sb_action_exclusive_access", Ty.STRING),
            Entry("sponsorblock_action_hook", "sb_action_hook", Ty.STRING),
            Entry("sponsorblock_action_poi_highlight", "sb_action_poi_highlight", Ty.STRING),
            Entry("dearrow_enabled", "dearrow_enabled", Ty.BOOL),
            Entry("dearrow_badge_enabled", "dearrow_badge_enabled", Ty.BOOL),
            Entry("subtitles_enabled", "subtitles_enabled", Ty.BOOL),
            // Another device must not switch this phone's UI language (#1277).
            Entry("app_language", "app_language", Ty.STRING, applied = false),
            Entry("trending_region", "trending_region", Ty.STRING),
            Entry("hide_watched_videos", "hide_watched_videos", Ty.BOOL),
            Entry("show_shorts_player_prompt", "show_shorts_player_prompt", Ty.BOOL),
            Entry("comments_enabled", "comments_enabled", Ty.BOOL),
            Entry("comments_preview_enabled", "comments_preview_enabled", Ty.BOOL),
            Entry("return_youtube_dislikes", "rytd_enabled", Ty.BOOL),
            Entry("background_play", "background_play_enabled", Ty.BOOL),
            Entry("video_loop", "video_loop_enabled", Ty.BOOL),
            Entry("skip_silence", "skip_silence_enabled", Ty.BOOL),
            Entry("stable_volume", "stable_volume_enabled", Ty.BOOL),
            Entry("subscriptions_show_videos", "subscription_show_videos", Ty.BOOL),
            Entry("subscriptions_show_shorts", "subscription_show_shorts", Ty.BOOL),
            Entry("subscriptions_show_live", "subscription_show_live", Ty.BOOL),
            Entry("default_video_codec", "default_video_codec", Ty.STRING),
            Entry("shorts_quality_wifi", "shorts_quality_wifi", Ty.STRING),
            Entry("shorts_quality_cellular", "shorts_quality_cellular", Ty.STRING),
            Entry("music_audio_quality", "music_audio_quality", Ty.STRING),
            Entry("preferred_audio_language", "preferred_audio_language", Ty.STRING),
            Entry("preferred_subtitle_language", "preferred_subtitle_language", Ty.STRING),
            Entry("equalizer", EqStateJson.KEY, Ty.STRING),
        )

    private val byCanonical = WHITELIST.associateBy { it.canonical }

    /** Pull the whitelisted keys out of a full settings export into canonical records. */
    fun exportToCanonical(
        backup: SettingsBackup,
        hlc: String,
    ): List<CanonicalSetting> {
        val out = ArrayList<CanonicalSetting>(WHITELIST.size)
        for (e in WHITELIST) {
            val value: JsonElement? =
                when (e.type) {
                    Ty.BOOL -> backup.booleans[e.android]?.let { JsonPrimitive(it) }
                    Ty.STRING -> backup.strings[e.android]?.let { JsonPrimitive(it) }
                    Ty.FLOAT -> backup.floats[e.android]?.let { JsonPrimitive(it) }
                    Ty.INT -> backup.ints[e.android]?.let { JsonPrimitive(it) }
                    Ty.LONG -> backup.longs[e.android]?.let { JsonPrimitive(it) }
                }
            if (value != null) out.add(CanonicalSetting(e.canonical, value, hlc))
        }
        return out
    }

    /** Build a [SettingsBackup] containing ONLY whitelisted keys, coercing each typed value. */
    fun applyToBackup(settings: List<CanonicalSetting>): SettingsBackup {
        val strings = HashMap<String, String>()
        val booleans = HashMap<String, Boolean>()
        val ints = HashMap<String, Int>()
        val floats = HashMap<String, Float>()
        val longs = HashMap<String, Long>()
        for (s in settings) {
            val e = byCanonical[s.key]?.takeIf { it.applied } ?: continue // ignore unknown/non-whitelisted keys
            val prim = runCatching { s.value.jsonPrimitive }.getOrNull() ?: continue
            when (e.type) {
                Ty.BOOL -> prim.booleanOrNull?.let { booleans[e.android] = it }
                Ty.STRING -> strings[e.android] = prim.content
                Ty.FLOAT -> prim.floatOrNull?.let { floats[e.android] = it }
                Ty.INT -> prim.intOrNull?.let { ints[e.android] = it }
                Ty.LONG -> prim.longOrNull?.let { longs[e.android] = it }
            }
        }
        return SettingsBackup(strings, booleans, ints, floats, longs)
    }
}
