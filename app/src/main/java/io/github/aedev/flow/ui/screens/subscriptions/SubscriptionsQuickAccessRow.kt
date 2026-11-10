package io.github.aedev.flow.ui.screens.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.ui.components.shared.dpadFocusGroup

private val RowHorizontalPadding = 12.dp
private val AvatarSpacing = 12.dp
private val AvatarRowTopPadding = 10.dp
private val PinDividerHeight = 40.dp
private val PinDividerTopPadding = 8.dp

@Composable
internal fun SubscriptionsQuickAccessRow(
    channels: List<Channel>,
    onChannelClick: (Channel) -> Unit,
    onViewAllClick: () -> Unit,
    onMusicClick: () -> Unit,
    modifier: Modifier = Modifier,
    pinnedCount: Int = 0,
    onTogglePin: ((Channel) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val pinnedIds = channels.take(pinnedCount).map(Channel::id)
    // A lazy row keeps its first visible item in place, so a new pin would land off-screen to the left.
    LaunchedEffect(pinnedIds) { listState.animateScrollToItem(0) }
    Column(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = RowHorizontalPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.subscriptions_quick_access_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (channels.any { it.isMusic }) {
                SubscriptionMusicPill(
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    iconSize = 14.dp,
                    textStyle = MaterialTheme.typography.labelMedium,
                    onClick = onMusicClick,
                )
            }
        }

        LazyRow(
            state = listState,
            modifier = Modifier.dpadFocusGroup(),
            contentPadding =
                PaddingValues(
                    start = RowHorizontalPadding,
                    end = 8.dp,
                    top = AvatarRowTopPadding,
                ),
            horizontalArrangement = Arrangement.spacedBy(AvatarSpacing),
        ) {
            items(channels.take(pinnedCount), key = { it.id }) { channel ->
                ChannelAvatarItem(
                    channel = channel,
                    onClick = { onChannelClick(channel) },
                    isPinned = true,
                    onTogglePin = onTogglePin?.let { toggle -> { toggle(channel) } },
                )
            }
            if (pinnedCount > 0 && channels.size > pinnedCount) {
                item(key = "pinned_divider") {
                    VerticalDivider(modifier = Modifier.padding(top = PinDividerTopPadding).height(PinDividerHeight))
                }
            }
            items(channels.drop(pinnedCount), key = { it.id }) { channel ->
                ChannelAvatarItem(
                    channel = channel,
                    onClick = { onChannelClick(channel) },
                    onTogglePin = onTogglePin?.let { toggle -> { toggle(channel) } },
                )
            }
            item(key = "view_all") {
                AllSubscriptionsAvatarItem(onClick = onViewAllClick)
            }
        }
    }
}
