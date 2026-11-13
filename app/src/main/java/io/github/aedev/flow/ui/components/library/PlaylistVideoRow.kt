package io.github.aedev.flow.ui.components.library

import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.shared.MediaRow
import io.github.aedev.flow.ui.components.shared.MediaThumbnail
import io.github.aedev.flow.ui.components.shared.MediaThumbnailDefaults
import io.github.aedev.flow.ui.components.shared.ReorderHandle
import io.github.aedev.flow.ui.components.shared.card.LocalVideoCardPreferences
import io.github.aedev.flow.ui.components.shared.quickactions.VideoQuickActionsBottomSheet
import io.github.aedev.flow.ui.components.shared.videoMetadataLine
import io.github.aedev.flow.utils.formatYouTubeRelativeTime

/** A video in a playlist. Long press opens its menu, with the playlist's own remove when it has one. */
@Composable
internal fun PlaylistVideoRow(
    video: Video,
    isSelected: Boolean,
    inSelectionMode: Boolean,
    canModify: Boolean,
    dragHandleModifier: Modifier,
    showDragHandle: Boolean,
    showAddedDate: Boolean,
    isWatchLater: Boolean,
    onRemove: () -> Unit,
    isLikes: Boolean = false,
    onMoveToTop: (() -> Unit)? = null,
    onMoveToBottom: (() -> Unit)? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailWidth: Dp = MediaThumbnailDefaults.VideoWidth,
) {
    var showQuickActions by remember { mutableStateOf(false) }

    MediaRow(
        title = video.title,
        modifier = modifier,
        subtitle = video.channelName,
        supporting = video.playlistMetadataLine(showAddedDate, isLikes),
        supportingColor =
            if (video.viewCount < 0L && !showAddedDate) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        selected = isSelected,
        onClick = onClick,
        onLongClick = if (inSelectionMode) null else ({ showQuickActions = true }),
        leading =
            when {
                inSelectionMode -> {
                    { Checkbox(checked = isSelected, onCheckedChange = { onClick() }) }
                }

                showDragHandle -> {
                    { ReorderHandle(modifier = dragHandleModifier) }
                }

                else -> {
                    null
                }
            },
    ) {
        MediaThumbnail(
            videoId = video.id,
            thumbnailUrl = video.thumbnailUrl,
            durationSeconds = video.duration,
            showWatchProgress = LocalVideoCardPreferences.current.showWatchProgress,
            width = thumbnailWidth,
        )
    }

    if (showQuickActions) {
        VideoQuickActionsBottomSheet(
            video = video,
            onRemoveFromCollection = if (canModify) onRemove else null,
            removeFromCollectionLabel =
                if (canModify) {
                    stringResource(
                        when {
                            isLikes -> R.string.unlike
                            isWatchLater -> R.string.remove_from_watch_later
                            else -> R.string.remove_from_playlist_action
                        },
                    )
                } else {
                    null
                },
            onMoveToTop = onMoveToTop,
            onMoveToBottom = onMoveToBottom,
            onDismiss = { showQuickActions = false },
        )
    }
}

@Composable
internal fun Video.playlistMetadataLine(
    showAddedDate: Boolean,
    isLikes: Boolean = false,
): String {
    val addedAt = addedAtInPlaylist
    if (showAddedDate && addedAt != null) {
        val template = if (isLikes) R.string.liked_video_template else R.string.playlist_video_added_template
        return stringResource(template, formatYouTubeRelativeTime(addedAt))
    }
    return videoMetadataLine(video = this, isUpcoming = viewCount < 0L)
}
