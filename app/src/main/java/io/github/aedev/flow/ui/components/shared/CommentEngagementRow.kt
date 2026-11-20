package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.ui.theme.extendedColors
import io.github.aedev.flow.utils.formatLikeCount

private val CreatorHeartAvatarSize = 18.dp
private val CreatorHeartBadgeSize = 10.dp

/**
 * The like count, and the heart the creator left on this comment.
 *
 * Thumbs-down and reply stay out: neither has an action this app can perform without a signed-in
 * session, so showing them would only be decoration.
 *
 * [creatorAvatar] stands in when YouTube sent no avatar with this heart, which it does for replies.
 */
@Composable
internal fun CommentEngagementRow(
    comment: Comment,
    modifier: Modifier = Modifier,
    creatorAvatar: String? = null,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Outlined.ThumbUp,
            contentDescription = stringResource(R.string.like),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        val likeText = comment.likeCountText.takeIf { it.isNotBlank() } ?: comment.likeCount.takeIf { it > 0 }?.let(::formatLikeCount)
        if (likeText != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = likeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (comment.isHearted) {
            Spacer(modifier = Modifier.width(14.dp))
            CreatorHeart(
                avatarUrl = comment.creatorHeartThumbnail ?: creatorAvatar,
                description = comment.heartedByText ?: stringResource(R.string.comment_hearted_by_creator),
            )
        }
    }
}

/** The creator's avatar with a heart on its corner, or the heart alone when no avatar is known. */
@Composable
private fun CreatorHeart(
    avatarUrl: String?,
    description: String,
) {
    if (avatarUrl.isNullOrBlank()) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = description,
            tint = MaterialTheme.extendedColors.creatorHeart,
            modifier = Modifier.size(14.dp),
        )
        return
    }
    Box(modifier = Modifier.semantics { contentDescription = description }) {
        ChannelAvatarImage(
            url = avatarUrl,
            contentDescription = null,
            modifier = Modifier.size(CreatorHeartAvatarSize).clip(CircleShape),
        )
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = null,
            tint = MaterialTheme.extendedColors.creatorHeart,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(CreatorHeartBadgeSize),
        )
    }
}
