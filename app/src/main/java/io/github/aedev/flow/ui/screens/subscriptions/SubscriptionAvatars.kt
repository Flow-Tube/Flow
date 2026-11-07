package io.github.aedev.flow.ui.screens.subscriptions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.ui.components.shared.ChannelAvatarImage
import io.github.aedev.flow.ui.components.shared.ChannelGroupBadge
import io.github.aedev.flow.ui.components.shared.flowArtistShape
import io.github.aedev.flow.ui.components.shared.focusLift
import io.github.aedev.flow.ui.components.shared.titleMarquee
import io.github.aedev.flow.ui.components.shared.titleMarqueeOverflow

private val AvatarItemWidth = 64.dp
private val AvatarSize = 56.dp
private val BadgeOffset = 2.dp
private val BadgeIconSize = 12.dp
private val BadgePadding = 4.dp
private val LabelSpacing = 4.dp

/**
 * Music subscriptions wear the artist shape used across the music surfaces; video channels stay
 * circular, so the two kinds stay distinguishable before the label is read.
 */
@Composable
internal fun subscriptionAvatarShape(isMusic: Boolean): Shape = if (isMusic) flowArtistShape() else CircleShape

@Composable
internal fun ChannelAvatarItem(
    channel: Channel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .width(AvatarItemWidth)
                .focusLift(interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(focusRingShape = MaterialTheme.shapes.medium),
                    onClick = onClick,
                ),
    ) {
        Box(
            modifier = Modifier.size(AvatarSize),
            contentAlignment = Alignment.Center,
        ) {
            ChannelAvatarImage(
                url = channel.thumbnailUrl,
                contentDescription = channel.name,
                modifier =
                    Modifier
                        .size(AvatarSize)
                        .clip(subscriptionAvatarShape(channel.isMusic))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            ChannelGroupBadge(channelId = channel.id)
            if (channel.isMusic) {
                ChannelTypeBadge(
                    icon = Icons.Default.MusicNote,
                    contentDescription = stringResource(R.string.subscriptions_music_badge_cd),
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = BadgeOffset, y = BadgeOffset),
                )
            }
        }
        Spacer(modifier = Modifier.height(LabelSpacing))
        Text(
            text = channel.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = titleMarqueeOverflow(),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.titleMarquee(),
        )
    }
}

@Composable
internal fun AllSubscriptionsAvatarItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .width(AvatarItemWidth)
                .focusLift(interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(focusRingShape = MaterialTheme.shapes.medium),
                    onClick = onClick,
                ),
    ) {
        Box(
            modifier =
                Modifier
                    .size(AvatarSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.view_all_button_label),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(LabelSpacing))
        Text(
            text = stringResource(R.string.view_all_button_label),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChannelTypeBadge(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        tonalElevation = BadgeOffset,
    ) {
        Box(
            modifier = Modifier.padding(BadgePadding),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(BadgeIconSize),
            )
        }
    }
}
