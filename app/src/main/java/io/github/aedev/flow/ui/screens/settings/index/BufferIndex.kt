package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination

internal object BufferIndex {
    private val page = SettingsDestination.BUFFER
    private const val CUSTOM_PROFILE_ROW = "buffer.profile.CUSTOM"

    val profile = SettingEntry(key = "buffer.profile", title = R.string.buffer_settings_header_profile, destination = page)
    val minBuffer =
        SettingEntry(key = "buffer.min", title = R.string.settings_buffer_min, destination = page, revealVia = CUSTOM_PROFILE_ROW)
    val maxBuffer =
        SettingEntry(key = "buffer.max", title = R.string.settings_buffer_max, destination = page, revealVia = CUSTOM_PROFILE_ROW)
    val startBuffer =
        SettingEntry(key = "buffer.start", title = R.string.settings_buffer_start, destination = page, revealVia = CUSTOM_PROFILE_ROW)
    val rebuffer =
        SettingEntry(key = "buffer.rebuffer", title = R.string.settings_buffer_rebuffer, destination = page, revealVia = CUSTOM_PROFILE_ROW)

    val cacheSize =
        SettingEntry(
            key = "buffer.cache_size",
            title = R.string.buffer_cache_size_link,
            summary = R.string.buffer_cache_size_link_summary,
            keywords = R.string.settings_keywords_cache,
            destination = page,
        )

    val all = listOf(profile, minBuffer, maxBuffer, startBuffer, rebuffer, cacheSize)
}
