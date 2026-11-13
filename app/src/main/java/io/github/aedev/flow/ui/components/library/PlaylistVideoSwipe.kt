package io.github.aedev.flow.ui.components.library

import androidx.annotation.StringRes
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlaylistSwipeAction

@get:StringRes
internal val PlaylistSwipeAction.labelRes: Int
    get() =
        when (this) {
            PlaylistSwipeAction.NONE -> R.string.off
            PlaylistSwipeAction.MOVE_TO_TOP -> R.string.playlist_move_to_top
            PlaylistSwipeAction.MOVE_TO_BOTTOM -> R.string.playlist_move_to_bottom
            PlaylistSwipeAction.REMOVE -> R.string.remove_from_playlist_action
        }
