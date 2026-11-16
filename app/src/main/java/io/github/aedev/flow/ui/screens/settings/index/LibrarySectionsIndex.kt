package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.library.LibrarySection
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination

internal object LibrarySectionsIndex {
    private val entries: Map<LibrarySection, SettingEntry> =
        LibrarySection.entries.associateWith { section ->
            SettingEntry(
                key = "library.${section.name.lowercase()}",
                title = section.titleRes,
                section =
                    if (section in
                        LibrarySection.settingsAndData
                    ) {
                        R.string.library_settings_data_header
                    } else {
                        R.string.library_section_header
                    },
                destination = SettingsDestination.LIBRARY,
            )
        }

    fun entry(section: LibrarySection): SettingEntry = entries.getValue(section)

    val playlistSwipeLeft = playlistEntry("playlist_swipe_left", R.string.playlist_swipe_left)
    val playlistSwipeRight = playlistEntry("playlist_swipe_right", R.string.playlist_swipe_right)

    val all: List<SettingEntry> = LibrarySection.entries.map(::entry) + listOf(playlistSwipeLeft, playlistSwipeRight)

    private fun playlistEntry(
        key: String,
        title: Int,
    ) = SettingEntry(
        key = "library.$key",
        title = title,
        summary = R.string.playlist_swipe_subtitle,
        section = R.string.library_playlists_label,
        destination = SettingsDestination.LIBRARY,
    )
}
