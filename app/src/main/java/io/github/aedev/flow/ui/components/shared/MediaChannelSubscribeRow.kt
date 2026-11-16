package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

private val AvatarSize = 48.dp

/**
 * A channel to subscribe to from a list: avatar, name, an optional line under it, and the compact
 * subscribe button with its bell. A channel known only by name gets a person glyph for its avatar.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaChannelSubscribeRow(
    name: String,
    thumbnailUrl: String?,
    supportingText: String?,
    subscribed: Boolean,
    notifying: Boolean,
    shape: Shape,
    onToggle: () -> Unit,
    onNotificationsChange: (Boolean) -> Unit,
    organize: ChannelOrganizeActions? = null,
) {
    val colors = MaterialTheme.colorScheme
    SegmentedListItem(
        shapes = ListItemDefaults.shapes(shape = shape),
        verticalAlignment = Alignment.CenterVertically,
        colors =
            ListItemDefaults.segmentedColors(
                containerColor = colors.surfaceContainerHigh,
                contentColor = colors.onSurface,
                supportingContentColor = colors.onSurfaceVariant,
            ),
        leadingContent = {
            val avatar =
                Modifier
                    .size(AvatarSize)
                    .clip(flowArtistShape())
                    .background(colors.surfaceContainerHighest)
            if (thumbnailUrl.isNullOrBlank()) {
                Box(avatar, contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Person, contentDescription = null, tint = colors.onSurfaceVariant)
                }
            } else {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = avatar,
                )
            }
        },
        supportingContent = supportingText?.let { text -> { Text(text) } },
        trailingContent = {
            FlowSubscribeButton(
                isSubscribed = subscribed,
                onSubscribeClick = onToggle,
                onUnsubscribeClick = onToggle,
                isNotificationsEnabled = notifying,
                onNotificationChange = onNotificationsChange,
                size = FlowSubscribeButtonSize.Compact,
                onAddNote = organize?.onAddNote,
                onManageGroups = organize?.onManageGroups,
            )
        },
    ) {
        Text(text = name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
