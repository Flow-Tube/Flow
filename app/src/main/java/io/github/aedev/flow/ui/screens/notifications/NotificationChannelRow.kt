package io.github.aedev.flow.ui.screens.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.MediaThumbnail

/**
 * One channel's uploads in a section, as a single row that opens to show each of them. Swiping it
 * towards the start removes them all; saving to Watch later is done per video, once it is open.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun NotificationChannelRow(
    row: InboxRow.Channel,
    isNew: Boolean,
    time: String,
    expanded: Boolean,
    shape: Shape,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberSwipeToDismissBoxState()
    val deleteLabel = stringResource(R.string.delete)
    val stateLabel = stringResource(if (expanded) R.string.notifications_channel_open else R.string.notifications_channel_closed)
    val newLabel = stringResource(R.string.notifications_group_new)
    val colors = MaterialTheme.colorScheme
    val newest = row.notifications.first()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        modifier =
            modifier.clip(shape).semantics {
                stateDescription = if (isNew) "$newLabel, $stateLabel" else stateLabel
                customActions =
                    listOf(
                        CustomAccessibilityAction(deleteLabel) {
                            onDismiss()
                            true
                        },
                    )
            },
        onDismiss = { value -> if (value == SwipeToDismissBoxValue.EndToStart) onDismiss() },
        backgroundContent = {
            NotificationSwipeBackground(
                icon = Icons.Outlined.Delete,
                label = deleteLabel,
                container = colors.errorContainer,
                content = colors.onErrorContainer,
                alignment = Alignment.CenterEnd,
            )
        },
    ) {
        SegmentedListItem(
            onClick = onToggle,
            shapes = ListItemDefaults.shapes(shape = shape),
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
                    videoId = newest.videoId,
                    thumbnailUrl = newest.thumbnailUrl,
                    width = NotificationThumbnailWidth,
                    placeholder = Icons.Outlined.Notifications,
                )
            },
            supportingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(NotificationMetaSpacing), verticalAlignment = Alignment.CenterVertically) {
                    if (isNew) Badge(containerColor = colors.primary)
                    Text(
                        text = stringResource(R.string.notifications_meta, row.channelName, time),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            trailingContent = {
                Icon(imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
            },
        ) {
            Text(
                text = pluralStringResource(R.plurals.notifications_channel_uploads, row.notifications.size, row.notifications.size),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isNew) FontWeight.SemiBold else null,
            )
        }
    }
}
