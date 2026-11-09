package io.github.aedev.flow.ui.screens.subscriptions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.ui.components.shared.FlowFilterChip

internal val GroupRowHorizontalPadding = 12.dp
private val GroupRowVerticalPadding = 8.dp
private val GroupChipSpacing = 8.dp
private val GroupEditButtonSize = 32.dp
private val GroupEditGlyphSize = 18.dp
private val PostsDividerHeight = 24.dp
private val HeaderTopPadding = 8.dp
private val HeaderBottomPadding = 12.dp

/**
 * The top of the Subscriptions feed: the channel shortcuts, then the chips that pick a group. With
 * [onPostsClick], a Posts chip leads the row and switches the feed below between videos and posts;
 * a divider sets it apart from the group chips, since it is selected alongside one of them.
 */
@Composable
internal fun SubscriptionsFeedHeader(
    state: SubscriptionsUiState,
    topChannels: List<Channel>,
    onChannelClick: (Channel) -> Unit,
    onViewAllClick: () -> Unit,
    onMusicSubscriptionsClick: () -> Unit,
    onGroupSelected: (String?) -> Unit,
    onManageGroups: () -> Unit,
    modifier: Modifier = Modifier,
    postsSelected: Boolean = false,
    onPostsClick: (() -> Unit)? = null,
) {
    Column(modifier = modifier) {
        SubscriptionsQuickAccessRow(
            channels = topChannels,
            onChannelClick = onChannelClick,
            onViewAllClick = onViewAllClick,
            onMusicClick = onMusicSubscriptionsClick,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = HeaderTopPadding, bottom = HeaderBottomPadding),
        )

        HorizontalDivider()

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(
                        horizontal = GroupRowHorizontalPadding,
                        vertical = GroupRowVerticalPadding,
                    ),
            horizontalArrangement = Arrangement.spacedBy(GroupChipSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onPostsClick != null) {
                FlowFilterChip(
                    label = stringResource(R.string.tab_posts),
                    selected = postsSelected,
                    onClick = onPostsClick,
                )
                VerticalDivider(modifier = Modifier.height(PostsDividerHeight))
            }
            FlowFilterChip(
                label = stringResource(R.string.group_all),
                selected = state.selectedGroupName == null,
                onClick = { onGroupSelected(null) },
            )
            state.groups.forEach { group ->
                FlowFilterChip(
                    label = group.name,
                    selected = state.selectedGroupName == group.name,
                    onClick = { onGroupSelected(group.name) },
                )
            }
            IconButton(
                onClick = onManageGroups,
                modifier = Modifier.size(GroupEditButtonSize),
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.manage_groups),
                    modifier = Modifier.size(GroupEditGlyphSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
