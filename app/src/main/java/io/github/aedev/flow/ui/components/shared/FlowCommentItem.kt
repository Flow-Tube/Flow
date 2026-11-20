package io.github.aedev.flow.ui.components.shared

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
    isUnfolded: (String) -> Boolean,
    onUnfold: (String) -> Unit,
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
    val translation = rememberCommentTranslation(comment)
    val linkActions =
        rememberRichTextLinkActions(
            onSeekMs = onSeekMs,
            onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
            onChannel = onAuthorClick,
        )
    val commentText = translation.shown(rememberCommentText(comment, linkActions, accent))
    val expand by rememberUpdatedState(onExpand)

    if (showFullSizeImage) {
        FullSizeImageDialog(
            imageUrl = toHighQualityAvatarUrl(comment.authorThumbnail),
            onDismiss = { showFullSizeImage = false },
        )
    }

    val threadOpen = isRepliesVisible && comment.replies.isNotEmpty()
    val lineColor = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (threadOpen) Modifier.commentThreadStem(lineColor) else Modifier)
                    .padding(horizontal = ThreadHorizontalPadding)
                    .padding(top = CommentRowTopPadding, bottom = 4.dp),
        ) {
            ChannelAvatarImage(
                url = comment.authorThumbnail,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(CommentAvatarSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            onAvatarClick(comment.authorThumbnail)
                            showFullSizeImage = true
                        },
            )

            Spacer(modifier = Modifier.width(CommentAvatarGap))

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
                                    ).then(
                                        // Links are child nodes and consume their own taps, so this
                                        // only sees a tap on plain text.
                                        if (isExpanded) {
                                            Modifier
                                        } else {
                                            Modifier.pointerInput(Unit) {
                                                detectTapGestures(onTap = { if (isOverflowing) expand() })
                                            }
                                        },
                                    ),
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

                CommentTranslateLink(comment = comment, color = accent, state = translation)

                comment.image?.let { FlowCommentImage(image = it, modifier = Modifier.padding(top = 8.dp)) }

                Spacer(modifier = Modifier.height(8.dp))

                CommentEngagementRow(comment = comment)

                // YouTube's Timed list gives no way to load a comment's replies, so nothing to open.
                if (comment.replyCount > 0 && (comment.continuationToken != null || comment.replies.isNotEmpty())) {
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

        if (threadOpen) {
            val creatorAvatar =
                remember(comment) {
                    comment.creatorHeartThumbnail
                        ?: comment.creatorReplyThumbnail
                        ?: comment.replies
                            .firstOrNull { it.isCreator }
                            ?.authorThumbnail
                            ?.takeIf(String::isNotBlank)
                }
            ReplyThread(
                replies = comment.replies,
                hasMore = comment.continuationToken != null,
                loadingMore = isLoadingReplies,
                isUnfolded = isUnfolded,
                onUnfold = onUnfold,
                creatorAvatar = creatorAvatar,
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
