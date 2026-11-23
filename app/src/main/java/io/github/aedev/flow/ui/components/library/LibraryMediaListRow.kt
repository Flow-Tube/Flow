package io.github.aedev.flow.ui.components.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.components.music.item.MusicTrackItem
import io.github.aedev.flow.ui.components.music.sheet.LocalMusicMenus
import io.github.aedev.flow.ui.components.shared.MediaRow
import io.github.aedev.flow.ui.components.shared.MediaThumbnailDefaults
import io.github.aedev.flow.ui.components.shared.card.LocalVideoCardPreferences
import io.github.aedev.flow.ui.components.shared.card.VideoCardThumbnail
import io.github.aedev.flow.ui.components.shared.card.rememberVideoCardState
import io.github.aedev.flow.ui.components.shared.quickactions.VideoQuickActionsBottomSheet

/**
 * A library entry as a row. Long press opens the item's menu, where [removeLabel] runs
 * [onRemove] as the screen's own remove, beside the inline [action] that does the same.
 * Files on the device get no online menu: its rows would write a local id into history,
 * likes and sync as if it were a YouTube video.
 */
@Composable
internal fun LibraryMediaListRow(
    track: MusicTrack,
    video: Video,
    isMusic: Boolean,
    title: String,
    onVideoClick: () -> Unit,
    onMusicClick: () -> Unit,
    removeLabel: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    durationSeconds: Int? = null,
    thumbnailWidth: Dp = MediaThumbnailDefaults.VideoWidth,
    showWatchProgress: Boolean = LocalVideoCardPreferences.current.showWatchProgress,
    action: @Composable () -> Unit,
) {
    val isLocal = LocalMediaIds.isLocal(track.videoId)
    if (isMusic) {
        val musicMenus = LocalMusicMenus.current
        MusicTrackItem(
            track = track,
            onClick = onMusicClick,
            modifier = modifier,
            showMenu = false,
            trailingContent = { action() },
            onLongClick = if (isLocal) null else ({ musicMenus.openSong(track) }),
        )
    } else {
        var showMenu by remember { mutableStateOf(false) }
        val state = rememberVideoCardState(video)
        MediaRow(
            title = state.title.ifBlank { title },
            modifier = modifier,
            subtitle = subtitle,
            onClick = onVideoClick,
            onLongClick = if (isLocal) null else ({ showMenu = true }),
            trailing = { action() },
        ) {
            VideoCardThumbnail(
                state = state,
                isUpcoming = false,
                shape = MaterialTheme.shapes.small,
                width = thumbnailWidth,
                durationSeconds = durationSeconds ?: 0,
                showWatchProgress = showWatchProgress,
            )
        }
        if (showMenu) {
            VideoQuickActionsBottomSheet(
                video = video,
                onDismiss = { showMenu = false },
                onRemoveFromCollection = onRemove,
                removeFromCollectionLabel = removeLabel,
            )
        }
    }
}
