package io.github.aedev.flow.ui.components.shorts

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.ChannelAvatarImage
import io.github.aedev.flow.ui.components.videoplayer.controls.PlayerPillIconButton
import io.github.aedev.flow.ui.theme.PlayerScrimContent
import io.github.aedev.flow.ui.theme.PlayerScrimPanel

private const val DISC_SPIN_MS = 4_000
private val RailSpacing = 16.dp
private val RailButtonSize = 44.dp
private val RailIconSize = 24.dp
private val RailLabelSpacing = 2.dp
private val SoundDiscSize = 36.dp
private val SoundDiscRim = 3.dp

/** The column of actions down a reel's right edge. Labels are the caller's: a count, a name, or nothing. */
@Composable
internal fun ShortsActionRail(
    isLiked: Boolean,
    likeLabel: String,
    onLikeClick: () -> Unit,
    commentLabel: String,
    onCommentsClick: () -> Unit,
    isSaved: Boolean,
    saveLabel: String,
    onSaveClick: () -> Unit,
    shareLabel: String,
    onShareClick: () -> Unit,
    moreLabel: String,
    onMoreClick: () -> Unit,
    channelAvatarUrl: String,
    channelName: String,
    isDiscSpinning: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RailSpacing),
        modifier = modifier,
    ) {
        ShortsRailAction(
            icon = if (isLiked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
            label = likeLabel,
            contentDescription = stringResource(R.string.action_like),
            contentColor = if (isLiked) MaterialTheme.colorScheme.primary else PlayerScrimContent,
            onClick = onLikeClick,
            haptic = null,
        )
        ShortsRailAction(
            icon = Icons.Outlined.ChatBubbleOutline,
            label = commentLabel,
            contentDescription = stringResource(R.string.action_comments),
            onClick = onCommentsClick,
        )
        ShortsRailAction(
            icon = if (isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            label = saveLabel,
            contentDescription = stringResource(R.string.action_save),
            contentColor = if (isSaved) MaterialTheme.colorScheme.primary else PlayerScrimContent,
            onClick = onSaveClick,
            haptic = null,
        )
        ShortsRailAction(
            icon = Icons.Outlined.Share,
            label = shareLabel,
            contentDescription = stringResource(R.string.action_share),
            onClick = onShareClick,
        )
        ShortsRailAction(
            icon = Icons.Rounded.MoreVert,
            label = moreLabel,
            contentDescription = stringResource(R.string.cd_more_options),
            onClick = onMoreClick,
        )
        ShortsSoundDisc(
            avatarUrl = channelAvatarUrl,
            channelName = channelName,
            spinning = isDiscSpinning,
        )
    }
}

@Composable
private fun ShortsRailAction(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    contentColor: Color = PlayerScrimContent,
    haptic: HapticFeedbackType? = HapticFeedbackType.ContextClick,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PlayerPillIconButton(
            onClick = onClick,
            icon = icon,
            contentDescription = contentDescription,
            buttonSize = RailButtonSize,
            iconSize = RailIconSize,
            contentColor = contentColor,
            haptic = haptic,
        )
        if (label.isNotBlank()) {
            Spacer(modifier = Modifier.height(RailLabelSpacing))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(shadow = ShortsOverlayDefaults.TextShadow),
                color = PlayerScrimContent,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * The channel's avatar as a record that turns while the reel plays. The transition is only
 * composed while it spins: a hidden or paused page must not own a running animation.
 */
@Composable
private fun ShortsSoundDisc(
    avatarUrl: String,
    channelName: String,
    spinning: Boolean,
) {
    Box(
        modifier =
            Modifier
                .size(SoundDiscSize)
                .background(PlayerScrimPanel, CircleShape)
                .padding(SoundDiscRim),
    ) {
        ChannelAvatarImage(
            url = avatarUrl,
            contentDescription = channelName,
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .then(if (spinning) spinningDiscModifier() else Modifier),
        )
    }
}

@Composable
private fun spinningDiscModifier(): Modifier {
    val transition = rememberInfiniteTransition(label = "album_spin")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(DISC_SPIN_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "album_rotation",
    )
    return Modifier.graphicsLayer { rotationZ = rotation }
}
