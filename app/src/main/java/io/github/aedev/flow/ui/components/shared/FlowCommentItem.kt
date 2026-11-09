package io.github.aedev.flow.ui.components.shared

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.utils.formatLikeCount

/** Centre of the parent avatar: the row's start padding plus half the 40 dp avatar, less the line. */
private val ThreadLineStart = 35.dp

@Composable
fun FlowCommentItem(
    comment: Comment,
    onSeekMs: (Long) -> Unit,
    onLoadReplies: (Comment) -> Unit,
    onLoadMoreReplies: (Comment) -> Unit,
    onAuthorClick: (String) -> Unit = {},
    isRepliesVisible: Boolean,
    onToggleReplies: () -> Unit,
    isExpanded: Boolean,
    onExpand: () -> Unit,
    onAvatarClick: (String) -> Unit = {},
    tint: MediaArtworkTint? = null,
) {
    var isOverflowing by remember { mutableStateOf(false) }
    var isLoadingReplies by remember { mutableStateOf(false) }
    var commentTextLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var showFullSizeImage by remember { mutableStateOf(false) }

    val uriHandler = LocalUriHandler.current

    LaunchedEffect(comment.replies) {
        isLoadingReplies = false
    }

    val accent = tint?.accent ?: MaterialTheme.colorScheme.primary
    val chipColor = tint?.container ?: MaterialTheme.colorScheme.surfaceContainerHighest
    val commentText = rememberCommentText(comment, accent)

    if (showFullSizeImage) {
        FullSizeImageDialog(
            imageUrl = toHighQualityAvatarUrl(comment.authorThumbnail),
            onDismiss = { showFullSizeImage = false },
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ThreadHorizontalPadding)
                    .padding(top = 12.dp, bottom = 4.dp),
        ) {
            ChannelAvatarImage(
                url = comment.authorThumbnail,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            onAvatarClick(comment.authorThumbnail)
                            showFullSizeImage = true
                        },
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                if (comment.isPinned) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = stringResource(R.string.pinned_comment),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = comment.pinnedByText?.takeIf { it.isNotBlank() } ?: stringResource(R.string.pinned_by_creator),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                CommentAuthorRow(
                    comment = comment,
                    onAuthorClick = onAuthorClick,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Box(modifier = Modifier.animateContentSize()) {
                    SelectionContainer {
                        BasicText(
                            text = commentText.annotated,
                            inlineContent = commentText.inlineContent,
                            style =
                                MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    lineHeight = 20.sp,
                                ),
                            maxLines = if (isExpanded) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { result ->
                                commentTextLayoutResult = result
                                if (result.hasVisualOverflow) isOverflowing = true
                            },
                            modifier =
                                Modifier
                                    .richTextHighlights(
                                        text = commentText.annotated,
                                        layoutResult = { commentTextLayoutResult },
                                        color = chipColor,
                                    ).pointerInput(commentText.annotated) {
                                        detectTapGestures(
                                            onTap = { tapOffset ->
                                                val result = commentTextLayoutResult ?: return@detectTapGestures
                                                val offset = result.getOffsetForPosition(tapOffset)
                                                val handled =
                                                    commentText.handleTap(
                                                        offset = offset,
                                                        onSeekMs = onSeekMs,
                                                        onOpenUrl = { url ->
                                                            runCatching { uriHandler.openUri(url) }
                                                        },
                                                        onAuthorClick = onAuthorClick,
                                                    )
                                                if (!handled && !isExpanded && isOverflowing) onExpand()
                                            },
                                        )
                                    },
                        )
                    }
                }

                if (isOverflowing && !isExpanded) {
                    Text(
                        text = stringResource(R.string.read_more),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier =
                            Modifier
                                .padding(top = 4.dp)
                                .clickable(onClick = onExpand),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                CommentEngagementRow(comment = comment)

                if (comment.replyCount > 0) {
                    RepliesToggle(
                        replyCount = comment.replyCount,
                        expanded = isRepliesVisible,
                        loading = isLoadingReplies,
                        onClick = {
                            if (!isRepliesVisible && comment.replies.isEmpty()) {
                                isLoadingReplies = true
                                onLoadReplies(comment)
                            }
                            onToggleReplies()
                        },
                    )
                }
            }
        }

        if (isRepliesVisible && comment.replies.isNotEmpty()) {
            val tree = remember(comment.replies) { buildCommentReplyTree(comment.replies) }
            ReplyThread(
                nodes = tree,
                hasMore = comment.repliesPage != null || comment.continuationToken != null,
                startPadding = ThreadLineStart,
                tint = tint,
                onSeekMs = onSeekMs,
                onAuthorClick = onAuthorClick,
                onAvatarClick = onAvatarClick,
                onLoadMore = {
                    isLoadingReplies = true
                    onLoadMoreReplies(comment)
                },
            )
        }
    }
}

@Composable
internal fun CommentAuthorRow(
    comment: Comment,
    onAuthorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        CommentAuthorName(
            comment = comment,
            onAuthorClick = onAuthorClick,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (comment.isVerified || comment.isArtist) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Rounded.Verified,
                contentDescription = stringResource(R.string.verified),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = localizedCommentPublishedTime(comment.publishedTime),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CommentAuthorName(
    comment: Comment,
    onAuthorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = formatAuthorName(comment.author)
    val clickable =
        modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(),
            onClick = { onAuthorClick(commentAuthorChannelRef(comment)) },
        )
    if (comment.isCreator) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = CircleShape,
            modifier = clickable,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        return
    }
    Text(
        text = name,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = clickable,
    )
}

/**
 * The like count, and the heart the creator left on this comment.
 *
 * The thumbs-down and reply icons that used to sit here were decoration: neither was clickable,
 * and neither has an action this app can perform without a signed-in session.
 */
@Composable
internal fun CommentEngagementRow(
    comment: Comment,
    modifier: Modifier = Modifier,
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
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = comment.heartedByText ?: stringResource(R.string.comment_hearted_by_creator),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
