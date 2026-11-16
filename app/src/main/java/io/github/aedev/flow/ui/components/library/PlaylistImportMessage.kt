package io.github.aedev.flow.ui.components.library

import android.content.Context
import io.github.aedev.flow.R
import io.github.aedev.flow.data.playlist.PlaylistImport

/** What to tell the viewer after an import, from the Playlists screen or a file opened in Flow. */
internal fun PlaylistImport.message(context: Context): String =
    when (this) {
        is PlaylistImport.Imported -> {
            val plural = if (isMusic) R.plurals.music_playlist_imported else R.plurals.playlist_imported
            context.resources.getQuantityString(plural, videoCount, name, videoCount)
        }

        is PlaylistImport.ImportedSeveral -> {
            context.resources.getQuantityString(R.plurals.playlists_imported, playlistCount, playlistCount)
        }

        PlaylistImport.NotAPlaylist -> {
            context.getString(R.string.playlist_import_not_a_playlist)
        }

        PlaylistImport.TooNew -> {
            context.getString(R.string.playlist_import_too_new)
        }

        PlaylistImport.Empty -> {
            context.getString(R.string.playlist_import_empty)
        }

        PlaylistImport.Unreadable -> {
            context.getString(R.string.playlist_import_unreadable)
        }
    }
