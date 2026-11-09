package io.github.aedev.flow.ui.components.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.innertube.pages.renderer.CommunityPost
import io.github.aedev.flow.innertube.pages.renderer.PostAttachment
import io.github.aedev.flow.ui.components.shared.ChannelAvatarImage
import io.github.aedev.flow.ui.components.shared.thumbnailUrlOrNull

/**
 * One community post in the search strip, shaped like YouTube's: a fixed-width outlined card with
 * the author line, the text, and the attachment preview beside it.
 *
 * The full [io.github.aedev.flow.ui.components.shared.MediaPostCard] is a full-width reading
 * surface with actions; neither fits a horizontally scrolling strip.
 */
@Composable
fun SearchPostCard(
    post: CommunityPost,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier.width(CardWidth),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(BlockSpacing),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AuthorSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelAvatarImage(
                    url = post.authorAvatarUrl,
                    contentDescription = post.authorName,
                    modifier = Modifier.size(AvatarSize),
                )
                Text(
                    text = post.authorName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = post.publishedTimeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BlockSpacing),
            ) {
                Text(
                    text = post.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = TEXT_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                thumbnailUrlOrNull(post.previewImageUrl())?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .width(PreviewWidth)
                                .aspectRatio(PREVIEW_ASPECT)
                                .clip(MaterialTheme.shapes.small),
                    )
                }
            }
        }
    }
}

private fun CommunityPost.previewImageUrl(): String? =
    when (val value = attachment) {
        is PostAttachment.Images -> value.urls.firstOrNull()
        is PostAttachment.SharedVideo -> value.video.thumbnailUrl.takeIf(String::isNotBlank)
        else -> null
    }

private val CardWidth = 280.dp
private val CardPadding = 12.dp
private val BlockSpacing = 10.dp
private val AuthorSpacing = 8.dp
private val AvatarSize = 24.dp
private val PreviewWidth = 72.dp
private const val PREVIEW_ASPECT = 1f
private const val TEXT_LINES = 4
