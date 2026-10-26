package io.github.aedev.flow.ui.screens.settings.index

import android.os.Build
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingAvailability
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination

internal object DownloadsIndex {
    private val page = SettingsDestination.DOWNLOADS

    private fun entry(
        key: String,
        title: Int,
        section: Int,
        summary: Int? = null,
        availability: SettingAvailability = SettingAvailability.Always,
    ) = SettingEntry(
        key = "downloads.$key",
        title = title,
        summary = summary,
        section = section,
        keywords = R.string.settings_keywords_downloads,
        availability = availability,
        destination = page,
    )

    val videoLocation = entry("video_location", R.string.video_download_location_label, R.string.storage_header)
    val musicLocation = entry("music_location", R.string.music_download_location_label, R.string.storage_header)
    val usage = entry("usage", R.string.internal_storage_label, R.string.storage_header)
    val cacheUsage = entry("cache_usage", R.string.cache_group_header, R.string.cache_group_header)
    val videoCache = entry("cache_size", R.string.cache_videos, R.string.cache_group_header)
    val songCache = entry("song_cache", R.string.cache_songs, R.string.cache_group_header)
    val artworkCache = entry("artwork_cache", R.string.cache_artwork, R.string.cache_group_header)
    val otherCache = entry("other_cache", R.string.cache_other, R.string.cache_group_header)
    val quickQuality =
        entry(
            "quick_quality",
            R.string.settings_quick_download_quality,
            R.string.settings_section_download_defaults,
            summary = R.string.settings_quick_download_quality_summary,
        )
    val codec = entry("codec", R.string.default_download_codec_label, R.string.settings_section_download_defaults)
    val musicQuality =
        entry(
            "music_quality",
            R.string.settings_music_download_quality,
            R.string.settings_section_download_defaults,
            summary = R.string.settings_music_download_quality_summary,
        )
    val menuStyle =
        entry(
            "menu_style",
            R.string.download_menu_style_title,
            R.string.settings_section_download_defaults,
            summary = R.string.download_menu_style_subtitle,
        )
    val askEveryTime =
        entry(
            "ask_every_time",
            R.string.download_ask_every_time_title,
            R.string.settings_section_download_defaults,
            summary = R.string.download_ask_every_time_summary,
        )
    val autoDownloadLikes =
        entry(
            "auto_download_likes",
            R.string.settings_auto_download_likes_title,
            R.string.settings_section_download_defaults,
            summary = R.string.settings_auto_download_likes_summary,
        )
    val autoDownloadOpened =
        entry(
            "auto_download_opened",
            R.string.settings_auto_download_opened_title,
            R.string.settings_section_download_defaults,
            summary = R.string.settings_auto_download_opened_summary,
        )
    val wifiOnly =
        entry(
            "wifi_only",
            R.string.download_over_wifi_only,
            R.string.settings_section_download_defaults,
            summary = R.string.reduce_data_usage_subtitle,
        )
    val subtitleFile =
        entry(
            "subtitle_file",
            R.string.download_subtitle_file,
            R.string.settings_section_download_defaults,
            summary = R.string.download_subtitle_file_subtitle,
        )
    val storageCard =
        entry(
            "storage_card",
            R.string.downloads_storage_card_title,
            R.string.local_section_library,
            summary = R.string.downloads_storage_card_summary,
        )
    val confirmDelete =
        entry(
            "confirm_delete",
            R.string.downloads_confirm_delete_title,
            R.string.local_section_library,
            summary = R.string.downloads_confirm_delete_summary,
        )
    val retag = entry("retag", R.string.download_retag_title, R.string.local_section_library)
    val findEarlier = entry("find_earlier", R.string.downloads_find_earlier_title, R.string.local_section_library)
    val concurrentDownloads =
        entry(
            "concurrent_downloads",
            R.string.concurrent_downloads_title,
            R.string.performance_header,
            summary = R.string.concurrent_downloads_summary,
        )
    val threads = entry("threads", R.string.concurrent_threads_title, R.string.performance_header)
    val allFilesAccess =
        entry(
            "all_files",
            R.string.files_access_title,
            R.string.settings_section_storage_access,
            availability = SettingAvailability.MinSdk(Build.VERSION_CODES.R),
        )
    val videoAccess =
        entry(
            "media_video",
            R.string.media_access_title,
            R.string.settings_section_storage_access,
            availability = SettingAvailability.MinSdk(Build.VERSION_CODES.TIRAMISU),
        )
    val audioAccess =
        entry(
            "media_audio",
            R.string.audio_access_title,
            R.string.settings_section_storage_access,
            availability = SettingAvailability.MinSdk(Build.VERSION_CODES.TIRAMISU),
        )

    val all =
        listOf(
            videoLocation,
            musicLocation,
            usage,
            cacheUsage,
            videoCache,
            songCache,
            artworkCache,
            otherCache,
            quickQuality,
            codec,
            musicQuality,
            menuStyle,
            askEveryTime,
            autoDownloadLikes,
            autoDownloadOpened,
            wifiOnly,
            subtitleFile,
            storageCard,
            confirmDelete,
            retag,
            findEarlier,
            concurrentDownloads,
            threads,
            allFilesAccess,
            videoAccess,
            audioAccess,
        )
}
