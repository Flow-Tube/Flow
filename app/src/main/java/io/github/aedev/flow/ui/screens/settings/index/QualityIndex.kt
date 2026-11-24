package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination
import io.github.aedev.flow.ui.components.settings.SettingsTabs

internal object QualityIndex {
    private val page = SettingsDestination.QUALITY

    private fun entry(
        key: String,
        title: Int,
        tab: String,
        section: Int,
        keywords: Int = R.string.settings_keywords_quality,
        revealVia: String? = null,
        summary: Int? = null,
    ) = SettingEntry(
        key = "quality.$key",
        title = title,
        summary = summary,
        tab = tab,
        section = section,
        keywords = keywords,
        revealVia = revealVia,
        destination = page,
    )

    val videoWifi = entry("video.wifi", R.string.settings_quality_wifi, SettingsTabs.QUALITY_VIDEO, R.string.settings_quality_tab_video)
    val videoMobile =
        entry("video.mobile", R.string.settings_quality_mobile, SettingsTabs.QUALITY_VIDEO, R.string.settings_quality_tab_video)
    val rememberVideo =
        entry(
            "video.remember",
            R.string.settings_quality_remember,
            SettingsTabs.QUALITY_VIDEO,
            R.string.settings_quality_tab_video,
            summary = R.string.settings_quality_remember_summary,
        )
    val lowerWhenSmall =
        entry(
            "video.lower_when_small",
            R.string.settings_quality_lower_when_small,
            SettingsTabs.QUALITY_VIDEO,
            R.string.settings_quality_tab_video,
            summary = R.string.settings_quality_lower_when_small_summary,
        )
    val codec =
        entry(
            "video.codec",
            R.string.settings_quality_preferred_codec,
            SettingsTabs.QUALITY_VIDEO,
            R.string.settings_quality_tab_video,
            keywords = R.string.settings_keywords_codec,
        )
    val fallbackCodec =
        entry(
            "video.fallback_codec",
            R.string.player_settings_video_codec_fallback,
            SettingsTabs.QUALITY_VIDEO,
            R.string.settings_quality_tab_video,
            keywords = R.string.settings_keywords_codec,
            revealVia = "quality.video.codec",
        )
    val shortsWifi = entry("shorts.wifi", R.string.settings_quality_wifi, SettingsTabs.QUALITY_SHORTS, R.string.settings_quality_tab_shorts)
    val shortsMobile =
        entry("shorts.mobile", R.string.settings_quality_mobile, SettingsTabs.QUALITY_SHORTS, R.string.settings_quality_tab_shorts)
    val thumbnailsWifi =
        entry(
            "thumbnails.wifi",
            R.string.settings_quality_wifi,
            SettingsTabs.QUALITY_VIDEO,
            R.string.settings_section_thumbnails,
            keywords = R.string.settings_keywords_thumbnails,
        )
    val thumbnailsMobile =
        entry(
            "thumbnails.mobile",
            R.string.settings_quality_mobile,
            SettingsTabs.QUALITY_VIDEO,
            R.string.settings_section_thumbnails,
            keywords = R.string.settings_keywords_thumbnails,
        )
    val music = entry("music", R.string.music_quality_header, SettingsTabs.QUALITY_MUSIC, R.string.settings_quality_tab_music)

    val all =
        listOf(
            videoWifi,
            videoMobile,
            rememberVideo,
            lowerWhenSmall,
            codec,
            fallbackCodec,
            thumbnailsWifi,
            thumbnailsMobile,
            shortsWifi,
            shortsMobile,
            music,
        )
}
