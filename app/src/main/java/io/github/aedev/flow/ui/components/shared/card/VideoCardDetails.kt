package io.github.aedev.flow.ui.components.shared.card

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.model.VideoCollaborator
import io.github.aedev.flow.ui.components.layout.navigation.LocalMediaNavigator
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation
import io.github.aedev.flow.ui.components.shared.dpadReturnFocus
import io.github.aedev.flow.ui.components.shared.focusLift
import io.github.aedev.flow.ui.components.shared.onMenuKey
import io.github.aedev.flow.ui.components.shared.pressScale
import io.github.aedev.flow.ui.components.shared.skipDpadFocus
import io.github.aedev.flow.ui.theme.extendedColors
import io.github.aedev.flow.utils.avatarImageIdentityKey

internal fun Video.channelAvatarUrls(collaborators: List<VideoCollaborator> = emptyList()): List<String> {
    if (collaborators.size <= 1) {
        return (listOf(channelThumbnailUrl) + channelThumbnailUrls)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.avatarImageIdentityKey() }
            .take(1)
    }

    return collaborators
        .map { it.thumbnailUrl }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.avatarImageIdentityKey() }
        .take(3)
}

/**
 * YouTube omits the view count on members-only uploads and shows this badge instead, so the row above
 * it legitimately reads "2 days ago" with no views.
 */
@Composable
internal fun MembersOnlyLabel(video: Video) {
    val label = video.membersOnlyText ?: return
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.extendedColors.success,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.extendedColors.success,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Why the feed shows this video, such as "From a channel you watch". */
@Composable
internal fun CardReasonLabel(reason: String) {
    Text(
        text = reason,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The whole card opens the video, and a long press opens its quick actions. TalkBack announces the
 * long press by name, since the gesture alone is undiscoverable. On a D-pad the card lifts on focus
 * and the Menu key opens the quick actions too.
 */
@Composable
internal fun Modifier.videoCardClickable(
    state: VideoCardState,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return pressScale(interactionSource)
        .focusLift(interactionSource)
        .onMenuKey { state.sheets.showQuickActions = true }
        .dpadReturnFocus(state.video.id)
        .combinedClickable(
            interactionSource = interactionSource,
            indication = ripple(focusRingShape = MaterialTheme.shapes.large),
            onClickLabel = null,
            onLongClickLabel = stringResource(R.string.more_options),
            onLongClick = { state.sheets.showQuickActions = true },
            onClick = onClick,
        )
}

/** The channel as a tap target: the avatar on a stacked card, the name on a row. */
@Composable
internal fun Modifier.videoCardChannelClickable(state: VideoCardState): Modifier {
    val navigator = LocalMediaNavigator.current
    return skipDpadFocus().clickable(
        role = Role.Button,
        onClickLabel = stringResource(R.string.go_to_channel),
    ) { state.openChannel(navigator) }
}

/**
 * The ⋮ button and, on a row whose thumbnail leaves the height for it, the watched toggle beneath,
 * both at the full 48 dp target. [alignTo] pulls them up so the ⋮ icon sits on the title's first line.
 * A stacked card keeps its watched toggle in the feedback group instead, so its text keeps the width.
 * On a D-pad the card's long press and the Menu key open the same menu, so the title takes the room.
 */
@Composable
internal fun VideoCardSideActions(
    state: VideoCardState,
    showWatched: Boolean,
    onWatched: (Video) -> Unit,
    alignTo: Dp,
    modifier: Modifier = Modifier,
) {
    if (LocalDpadNavigation.current) return
    Column(modifier = modifier.offset(y = -alignTo)) {
        IconButton(onClick = { state.sheets.showQuickActions = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.more_options),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showWatched) {
            val isWatched = state.isWatched
            IconToggleButton(
                checked = isWatched,
                onCheckedChange = { if (!isWatched) onWatched(state.video) },
            ) {
                Icon(
                    imageVector = if (isWatched) Icons.Filled.Visibility else Icons.Outlined.Visibility,
                    contentDescription = stringResource(R.string.mark_as_watched),
                    tint = if (isWatched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
