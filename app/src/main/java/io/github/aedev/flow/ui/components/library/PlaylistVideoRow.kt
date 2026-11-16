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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlaylistSwipeAction
import io.github.aedev.flow.data.local.PlaylistSwipes
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.shared.MediaRow
import io.github.aedev.flow.ui.components.shared.MediaThumbnail
import io.github.aedev.flow.ui.components.shared.MediaThumbnailDefaults
import io.github.aedev.flow.ui.components.shared.ReorderHandle
import io.github.aedev.flow.ui.components.shared.card.LocalVideoCardPreferences
import io.github.aedev.flow.ui.components.shared.quickactions.VideoQuickActionsBottomSheet
import io.github.aedev.flow.ui.components.shared.videoMetadataLine
import io.github.aedev.flow.utils.formatYouTubeRelativeTime

/**
 * A video in a playlist. Long press opens its menu, with the playlist's own remove and moves when it
 * has them, and the viewer's swipe choices run the same actions.
 */
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
    swipes: PlaylistSwipes = PlaylistSwipes(),
    swipeEnabled: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailWidth: Dp = MediaThumbnailDefaults.VideoWidth,
) {
    var showQuickActions by remember { mutableStateOf(false) }
    val removeLabel =
        stringResource(
            when {
                isLikes -> R.string.unlike
                isWatchLater -> R.string.remove_from_watch_later
                else -> R.string.remove_from_playlist_action
            },
        )
    val onRemoveHere = onRemove.takeIf { canModify }
    val actionFor: (PlaylistSwipeAction) -> (() -> Unit)? = { action ->
        when (action) {
            PlaylistSwipeAction.NONE -> null
            PlaylistSwipeAction.MOVE_TO_TOP -> onMoveToTop
            PlaylistSwipeAction.MOVE_TO_BOTTOM -> onMoveToBottom
            PlaylistSwipeAction.REMOVE -> onRemoveHere
        }
    }
    val moveToTopLabel = stringResource(R.string.playlist_move_to_top)
    val moveToBottomLabel = stringResource(R.string.playlist_move_to_bottom)

    PlaylistVideoSwipeBox(
        swipes = swipes,
        enabled = swipeEnabled && !inSelectionMode,
        removeLabel = removeLabel,
        actionFor = actionFor,
        modifier = modifier,
    ) {
        PlaylistVideoRowContent(
            video = video,
            isSelected = isSelected,
            inSelectionMode = inSelectionMode,
            dragHandleModifier = dragHandleModifier,
            showDragHandle = showDragHandle,
            showAddedDate = showAddedDate,
            isLikes = isLikes,
            onClick = onClick,
            onLongClick = { showQuickActions = true },
            thumbnailWidth = thumbnailWidth,
            modifier =
                Modifier.semantics {
                    if (!inSelectionMode) {
                        customActions =
                            listOfNotNull(
                                onMoveToTop?.let { moveToTopLabel to it },
                                onMoveToBottom?.let { moveToBottomLabel to it },
                                onRemoveHere?.let { removeLabel to it },
                            ).map { (label, action) ->
                                CustomAccessibilityAction(label) {
                                    action()
                                    true
                                }
                            }
                    }
                },
        )
    }

    if (showQuickActions) {
        VideoQuickActionsBottomSheet(
            video = video,
            onRemoveFromCollection = onRemoveHere,
            removeFromCollectionLabel = removeLabel.takeIf { canModify },
            onMoveToTop = onMoveToTop,
            onMoveToBottom = onMoveToBottom,
            onDismiss = { showQuickActions = false },
        )
    }
}

@Composable
private fun PlaylistVideoRowContent(
    video: Video,
    isSelected: Boolean,
    inSelectionMode: Boolean,
    dragHandleModifier: Modifier,
    showDragHandle: Boolean,
    showAddedDate: Boolean,
    isLikes: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    thumbnailWidth: Dp,
    modifier: Modifier = Modifier,
) {
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
        onLongClick = if (inSelectionMode) null else onLongClick,
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
