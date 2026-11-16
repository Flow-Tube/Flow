package io.github.aedev.flow.ui.components.videoplayer.sheet

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.shared.FlowReorderButtons
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation
import io.github.aedev.flow.ui.components.shared.MediaThumbnail
import io.github.aedev.flow.ui.components.shared.ReorderHandle
import io.github.aedev.flow.ui.components.shared.card.LocalVideoCardPreferences
import io.github.aedev.flow.ui.components.shared.onMenuKey
import io.github.aedev.flow.ui.components.shared.quickactions.VideoQuickActionsBottomSheet

private val ThumbnailWidth: Dp = 112.dp
private val HandleTouchSize: Dp = 48.dp
private val NowPlayingMarkSize: Dp = 24.dp
private val NowPlayingIconSize: Dp = 16.dp
private val RemoveIconPadding: Dp = 24.dp

/**
 * A video in the player's queue. Swiping it towards the start removes it and long press opens its
 * menu; the host offers the undo. The playing video is neither swipeable nor removable.
 */
@Composable
internal fun PlaylistQueueItem(
    video: Video,
    isPlaying: Boolean,
    isPlayed: Boolean,
    reorderModifier: Modifier,
    dragHandleModifier: Modifier,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var showQuickActions by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.large
    // Not rememberSaveable: the lazy list restores saved state by key, so an undone removal would
    // come back already dismissed and remove itself again.
    val positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val swipeState = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold) }

    SwipeToDismissBox(
        state = swipeState,
        modifier =
            modifier
                .then(reorderModifier)
                .padding(horizontal = 8.dp)
                .clip(shape),
        enableDismissFromStartToEnd = false,
        gesturesEnabled = onRemove != null,
        onDismiss = { value -> if (value == SwipeToDismissBoxValue.EndToStart) onRemove?.invoke() },
        backgroundContent = { RemoveBackground() },
    ) {
        QueueRowContent(
            video = video,
            isPlaying = isPlaying,
            isPlayed = isPlayed,
            dragHandleModifier = dragHandleModifier,
            onClick = onClick,
            onLongClick = { showQuickActions = true },
            onRemove = onRemove,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
        )
    }

    if (showQuickActions) {
        VideoQuickActionsBottomSheet(
            video = video,
            onRemoveFromCollection = onRemove,
            removeFromCollectionLabel = onRemove?.let { stringResource(R.string.remove_from_queue) },
            onDismiss = { showQuickActions = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueRowContent(
    video: Video,
    isPlaying: Boolean,
    isPlayed: Boolean,
    dragHandleModifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRemove: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val removeLabel = stringResource(R.string.remove_from_queue)
    val moveUpLabel = stringResource(R.string.move_up)
    val moveDownLabel = stringResource(R.string.move_down)
    val actions =
        buildList {
            listOf(moveUpLabel to onMoveUp, moveDownLabel to onMoveDown, removeLabel to onRemove)
                .forEach { (label, action) ->
                    if (action != null) {
                        add(
                            CustomAccessibilityAction(label) {
                                action()
                                true
                            },
                        )
                    }
                }
        }

    Surface(
        color = if (isPlaying) colors.secondaryContainer else colors.surface,
        contentColor = if (isPlaying) colors.onSecondaryContainer else colors.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier =
            Modifier
                .fillMaxWidth()
                .onMenuKey(onLongClick)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .semantics { customActions = actions },
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MediaThumbnail(
                videoId = video.id,
                thumbnailUrl = video.thumbnailUrl,
                width = ThumbnailWidth,
                durationSeconds = video.duration.takeUnless { isPlaying },
                showWatchProgress = LocalVideoCardPreferences.current.showWatchProgress,
                overlay = { if (isPlaying) NowPlayingMark(Modifier.align(Alignment.TopStart)) },
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium,
                    color =
                        when {
                            isPlaying -> colors.onSecondaryContainer
                            isPlayed -> colors.onSurfaceVariant
                            else -> colors.onSurface
                        },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = video.channelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isPlaying) colors.onSecondaryContainer else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (LocalDpadNavigation.current) {
                FlowReorderButtons(onMoveUp = onMoveUp, onMoveDown = onMoveDown)
            } else {
                val reorderLabel = stringResource(R.string.reorder_queue_item)
                Box(
                    modifier =
                        dragHandleModifier
                            .size(HandleTouchSize)
                            .semantics { contentDescription = reorderLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    ReorderHandle()
                }
            }
        }
    }
}

@Composable
private fun NowPlayingMark(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .padding(6.dp)
                .size(NowPlayingMarkSize)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.GraphicEq,
            contentDescription = stringResource(R.string.now_playing),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(NowPlayingIconSize),
        )
    }
}

@Composable
private fun RemoveBackground() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(end = RemoveIconPadding),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
