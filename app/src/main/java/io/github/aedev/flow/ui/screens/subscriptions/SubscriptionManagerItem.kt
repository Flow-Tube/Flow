package io.github.aedev.flow.ui.screens.subscriptions

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.ui.components.shared.ChannelAvatarImage
import io.github.aedev.flow.ui.components.shared.ChannelGroupBadge
import io.github.aedev.flow.ui.components.shared.FlowSubscribeButton

private val RowPadding = 12.dp
private val AvatarSize = 48.dp
private val AvatarTextSpacing = 16.dp
private val TitleBlockSpacing = 4.dp
private val TitleActionSpacing = 8.dp

@Composable
internal fun SubscriptionManagerItem(
    channel: Channel,
    onClick: () -> Unit,
    onUnsubscribe: () -> Unit,
    isNotificationsEnabled: Boolean,
    areShortsExcluded: Boolean,
    onNotificationChange: (Boolean) -> Unit,
    onShortsExcludeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    isPinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    dragHandle: (@Composable () -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .combinedClickable(onClick = onClick, onLongClick = onTogglePin?.let { { menuOpen = true } })
                .background(MaterialTheme.colorScheme.surface)
                .padding(RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dragHandle?.invoke()
        Box(modifier = Modifier.size(AvatarSize)) {
            if (onTogglePin != null) {
                SubscriptionChannelMenu(
                    expanded = menuOpen,
                    isPinned = isPinned,
                    onDismiss = { menuOpen = false },
                    onTogglePin = onTogglePin,
                )
            }
            ChannelAvatarImage(
                url = channel.thumbnailUrl,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(AvatarSize)
                        .clip(subscriptionAvatarShape(channel.isMusic))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            ChannelGroupBadge(channelId = channel.id)
        }

        Spacer(modifier = Modifier.width(AvatarTextSpacing))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TitleBlockSpacing),
        ) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (channel.isMusic) {
                SubscriptionMusicPill()
            }
        }

        Spacer(modifier = Modifier.width(TitleActionSpacing))

        FlowSubscribeButton(
            isSubscribed = true,
            onSubscribeClick = {},
            isNotificationsEnabled = isNotificationsEnabled,
            onUnsubscribeClick = onUnsubscribe,
            onNotificationChange = onNotificationChange,
            areShortsExcluded = areShortsExcluded,
            onShortsExcludeChange = onShortsExcludeChange,
            showSubscribedLabel = false,
        )
    }
}
