package io.github.aedev.flow.ui.screens.settings.content

import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.PlaylistSwipeAction
import io.github.aedev.flow.data.local.PlaylistSwipes
import io.github.aedev.flow.ui.components.library.LibrarySection
import io.github.aedev.flow.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class LibrarySettingsViewModel
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
    ) : SettingsViewModel() {
        internal val hidden = preferences.hiddenLibrarySections.map(LibrarySection::fromStored).asState(emptySet())
        val separatePlaylists = preferences.separatePlaylistKinds.asState(false)
        val shortsContent = preferences.shortsContentEnabled.asState(true)
        val notes = preferences.notesEnabled.asState(true)
        val playlistSwipes = preferences.playlistSwipeActions.asState(PlaylistSwipes())

        internal fun setShown(
            section: LibrarySection,
            shown: Boolean,
        ) = write { preferences.setLibrarySectionHidden(section.name, hidden = !shown) }

        fun setPlaylistSwipeLeft(action: PlaylistSwipeAction) = write { preferences.setPlaylistSwipeLeftAction(action) }

        fun setPlaylistSwipeRight(action: PlaylistSwipeAction) = write { preferences.setPlaylistSwipeRightAction(action) }
    }
