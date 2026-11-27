package io.github.aedev.flow.ui.screens.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.entity.NotificationEntity
import io.github.aedev.flow.ui.components.shared.MediaThumbnail
import kotlinx.coroutines.launch

internal val NotificationThumbnailWidth = 120.dp
internal val NotificationRowPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
internal val NotificationMetaSpacing = 6.dp
private val SwipeIconPadding = 24.dp

/**
 * One notification in a segmented group. Swiping it towards the start removes it, and towards the
 * end saves its video to Watch later and leaves the row in place; the screen shows the result.
 */
@Composable
internal fun NotificationRow(
    notification: NotificationEntity,
    isNew: Boolean,
    time: String,
    shape: Shape,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberNotificationSwipeState()
    val scope = rememberCoroutineScope()
    val currentOnSave by rememberUpdatedState(onSave)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    // SwipeToDismissBox restarts its dismiss effect whenever this callback changes, so it must stay one instance.
    val settle: (SwipeToDismissBoxValue) -> Unit =
        remember(state, scope) {
            { value ->
                if (value == SwipeToDismissBoxValue.StartToEnd) {
                    currentOnSave()
                    scope.launch { state.reset() }
                } else if (value == SwipeToDismissBoxValue.EndToStart) {
                    currentOnDismiss()
                }
            }
        }
    val saveLabel = stringResource(R.string.notifications_save_to_watch_later)
    val deleteLabel = stringResource(R.string.delete)
    SwipeToDismissBox(
        state = state,
        modifier =
            modifier.clip(shape).semantics {
                customActions =
                    listOf(
                        CustomAccessibilityAction(saveLabel) {
                            onSave()
                            true
                        },
                        CustomAccessibilityAction(deleteLabel) {
                            onDismiss()
                            true
                        },
                    )
            },
        onDismiss = settle,
        backgroundContent = {
            if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                NotificationSwipeBackground(
                    icon = Icons.Outlined.WatchLater,
                    label = saveLabel,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    alignment = Alignment.CenterStart,
                )
            } else {
                NotificationSwipeBackground(
                    icon = Icons.Outlined.Delete,
                    label = deleteLabel,
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                    alignment = Alignment.CenterEnd,
                )
            }
        },
    ) {
        NotificationContent(notification, isNew, time, shape, onClick, onLongClick)
    }
}

/**
 * A swipe state that starts settled every time its row enters the list. The saveable default would
 * come back dismissed when an undone row returns under the same key, and dismiss it again at once.
 */
@Composable
internal fun rememberNotificationSwipeState(): SwipeToDismissBoxState {
    val density = LocalDensity.current
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    return remember(density) { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, density, positionalThreshold = threshold) }
}

@Composable
internal fun NotificationSwipeBackground(
    icon: ImageVector,
    label: String,
    container: Color,
    content: Color,
    alignment: Alignment,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(container)
                .padding(horizontal = SwipeIconPadding),
        contentAlignment = alignment,
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = content)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NotificationContent(
    notification: NotificationEntity,
    isNew: Boolean,
    time: String,
    shape: Shape,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val newLabel = stringResource(R.string.notifications_group_new)
    val colors = MaterialTheme.colorScheme
    SegmentedListItem(
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = stringResource(R.string.more_options),
        shapes = ListItemDefaults.shapes(shape = shape),
        modifier = if (isNew) Modifier.semantics { stateDescription = newLabel } else Modifier,
        verticalAlignment = Alignment.CenterVertically,
        colors =
            ListItemDefaults.segmentedColors(
                containerColor = colors.surfaceContainerHigh,
                contentColor = colors.onSurface,
                supportingContentColor = colors.onSurfaceVariant,
            ),
        contentPadding = NotificationRowPadding,
        leadingContent = {
            MediaThumbnail(
                videoId = notification.videoId,
                thumbnailUrl = notification.thumbnailUrl,
                width = NotificationThumbnailWidth,
                placeholder = Icons.Outlined.Notifications,
            )
        },
        supportingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NotificationMetaSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isNew) Badge(containerColor = colors.primary)
                Text(
                    text = stringResource(R.string.notifications_meta, notification.channelName, time),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) {
        Text(
            text = notification.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (isNew) FontWeight.SemiBold else null,
        )
    }
}
