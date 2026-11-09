package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.pages.renderer.CommunityPost
import io.github.aedev.flow.ui.theme.extendedColors
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import io.github.aedev.flow.utils.formatRichText

/**
 * One community post, laid out as YouTube lays it out: author, text, then the attachment running the
 * full width of the post rather than inset inside a bordered card. [compact] is the shelf variant:
 * a few lines of text and a 16:9 crop, so every card in a row shares one height.
 */
@Composable
fun MediaPostCard(
    post: CommunityPost,
    onAuthorClick: () -> Unit,
    onCommentsClick: () -> Unit,
    onShareClick: () -> Unit,
    modifier: Modifier = Modifier,
    onVideoClick: (Video) -> Unit = {},
    showDivider: Boolean = true,
    compact: Boolean = false,
) {
    var textExpanded by rememberSaveable(post.id) { mutableStateOf(false) }
    var textOverflows by rememberSaveable(post.id) { mutableStateOf(false) }
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val formattedText =
        remember(post.text, primaryColor, onSurfaceColor) {
            formatRichText(text = post.text, primaryColor = primaryColor, textColor = onSurfaceColor)
        }

    Column(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAuthorClick)
                    .padding(horizontal = PostHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = ThumbnailUrlResolver.resolveChannelAvatar(post.authorAvatarUrl),
                contentDescription = null,
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = post.authorName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (post.publishedTimeText.isNotBlank()) {
                    Text(
                        text = post.publishedTimeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.extendedColors.textSecondary,
                    )
                }
            }
        }

        if (post.text.isNotBlank()) {
            Column(modifier = Modifier.padding(horizontal = PostHorizontalPadding)) {
                val postText: @Composable () -> Unit = {
                    Text(
                        text = formattedText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines =
                            when {
                                compact -> COMPACT_TEXT_LINES
                                textExpanded -> Int.MAX_VALUE
                                else -> FULL_TEXT_LINES
                            },
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { textOverflows = it.hasVisualOverflow },
                    )
                }
                // A shelf card sits in a horizontal row, where a selection drag would fight the scroll.
                if (compact) postText() else SelectionContainer { postText() }
                if (!compact && !textExpanded && textOverflows) {
                    TextButton(onClick = { textExpanded = true }) {
                        Text(stringResource(R.string.read_more))
                    }
                }
            }
        }

        post.attachment?.let { attachment ->
            MediaPostAttachment(attachment = attachment, onVideoClick = onVideoClick, compact = compact)
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PostHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ThumbUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = post.likeCountText.ifBlank { stringResource(R.string.like) },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onShareClick) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = stringResource(R.string.share),
                    modifier = Modifier.size(ActionIconSize),
                )
            }
            TextButton(onClick = onCommentsClick) {
                Icon(
                    imageVector = Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(ActionIconSize),
                )
                Spacer(Modifier.width(8.dp))
                Text(post.commentCountText.ifBlank { stringResource(R.string.comments) })
            }
        }

        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)
        }
    }
}

/**
 * The post's own gutter. A single image ignores it and runs edge to edge, as YouTube's does, so it is
 * applied per row rather than to the column.
 */
internal val PostHorizontalPadding = 16.dp

private val ActionIconSize = 20.dp
private const val FULL_TEXT_LINES = 6
private const val COMPACT_TEXT_LINES = 4
