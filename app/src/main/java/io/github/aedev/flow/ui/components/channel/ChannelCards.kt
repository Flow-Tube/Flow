package io.github.aedev.flow.ui.components.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.shared.FlowSubscribeButton
import io.github.aedev.flow.ui.components.shared.FlowSubscribeButtonSize
import io.github.aedev.flow.ui.components.shared.ShortWatchedIndicator
import io.github.aedev.flow.ui.components.shared.rememberChannelOrganizeActions
import io.github.aedev.flow.ui.components.shared.toOrganizeTarget
import io.github.aedev.flow.ui.theme.extendedColors
import io.github.aedev.flow.utils.formatSubscriberCount
import io.github.aedev.flow.utils.formatViewCount

/** Sister channels arrive as items on a channel's own tabs, so the grid has to be able to show one. */
@Composable
internal fun ChannelRow(
    channel: Channel,
    onClick: () -> Unit,
    isSubscribed: Boolean? = null,
    onSubscribeClick: () -> Unit = {},
    onUnsubscribeClick: () -> Unit = {},
    isNotificationsEnabled: Boolean = false,
    onNotificationChange: ((Boolean) -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = channel.thumbnailUrl,
            contentDescription = null,
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (channel.subscriberCount > 0L) {
                Text(
                    text = formatSubscriberCount(channel.subscriberCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.extendedColors.textSecondary,
                )
            }
        }
        if (isSubscribed != null) {
            val organize =
                rememberChannelOrganizeActions(
                    target = remember(channel) { channel.toOrganizeTarget() },
                    isSubscribed = isSubscribed,
                )
            FlowSubscribeButton(
                isSubscribed = isSubscribed,
                onSubscribeClick = onSubscribeClick,
                onUnsubscribeClick = onUnsubscribeClick,
                isNotificationsEnabled = isNotificationsEnabled,
                onNotificationChange = onNotificationChange,
                size = FlowSubscribeButtonSize.Compact,
                onAddNote = organize.onAddNote,
                onManageGroups = organize.onManageGroups,
            )
        }
    }
}
