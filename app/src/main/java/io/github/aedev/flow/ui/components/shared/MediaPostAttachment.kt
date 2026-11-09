package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import coil3.compose.LocalPlatformContext
import coil3.gif.repeatCount
import coil3.request.ImageRequest
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.pages.renderer.PostAttachment
import io.github.aedev.flow.ui.theme.extendedColors
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import io.github.aedev.flow.utils.formatDuration
import io.github.aedev.flow.utils.formatViewCount

@Composable
fun MediaPostAttachment(
    attachment: PostAttachment,
    onVideoClick: (Video) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    when (attachment) {
        is PostAttachment.Images -> PostImages(attachment.urls, modifier, compact)
        is PostAttachment.Poll -> PostPoll(attachment, modifier)
        is PostAttachment.SharedVideo -> PostSharedVideo(attachment.video, onVideoClick, modifier)
    }
}

@Composable
private fun PostImages(
    urls: List<String>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val resolved = remember(urls) { urls.map { ThumbnailUrlResolver.resolveCommunityPostImage(it) } }
    val whole = remember(urls) { urls.map { ThumbnailUrlResolver.resolveCommunityPostImage(it, uncropped = true) } }
    if (resolved.isEmpty()) return

    var fullSizeIndex by rememberSaveable { mutableIntStateOf(-1) }
    if (fullSizeIndex in whole.indices) {
        FullSizeImageDialog(imageUrls = whole, initialIndex = fullSizeIndex, onDismiss = { fullSizeIndex = -1 })
    }

    // A lone image runs edge to edge; a gallery insets so the next page peeks, which is the only cue
    // that there is one.
    if (resolved.size == 1) {
        AsyncImage(
            model = rememberPostImageRequest(if (compact) resolved.first() else whole.first()),
            contentDescription = stringResource(R.string.community_post_image_content_description),
            modifier =
                modifier
                    .fillMaxWidth()
                    .then(
                        if (compact) {
                            Modifier.aspectRatio(VIDEO_ASPECT_RATIO)
                        } else {
                            Modifier.heightIn(min = 120.dp, max = 520.dp)
                        },
                    ).background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { fullSizeIndex = 0 },
            contentScale = if (compact) ContentScale.Crop else ContentScale.Fit,
        )
        return
    }

    val pagerState = rememberPagerState(pageCount = { resolved.size })
    Box(modifier = modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = PostHorizontalPadding, end = GalleryPeek),
            pageSpacing = 8.dp,
        ) { page ->
            AsyncImage(
                model = rememberPostImageRequest(resolved[page]),
                contentDescription = stringResource(R.string.community_post_image_content_description),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { fullSizeIndex = page },
                contentScale = ContentScale.Crop,
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.scrim,
            shape = MaterialTheme.shapes.large,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = GalleryPeek + 12.dp, top = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.channel_post_image_count, pagerState.currentPage + 1, resolved.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun PostPoll(
    poll: PostAttachment.Poll,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = PostHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        poll.choices.forEach { choice ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = choice.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    choice.voteCountText?.takeIf { it.isNotBlank() }?.let { votes ->
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = votes,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.extendedColors.textSecondary,
                        )
                    }
                }
                choice.voteRatio?.let { ratio ->
                    LinearProgressIndicator(
                        progress = { ratio.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        poll.totalVotesText?.takeIf { it.isNotBlank() }?.let { total ->
            Text(
                text = total,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.extendedColors.textSecondary,
            )
        }
    }
}

@Composable
private fun PostSharedVideo(
    video: Video,
    onVideoClick: (Video) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = PostHorizontalPadding)
                .clip(MaterialTheme.shapes.medium)
                .clickable { onVideoClick(video) },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(VIDEO_ASPECT_RATIO)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            VideoThumbnailImage(
                videoId = video.id,
                model = video.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (video.duration > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.scrim,
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp),
                ) {
                    Text(
                        text = formatDuration(video.duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle =
                listOfNotNull(
                    video.viewCount.takeIf { it > 0L }?.let(::formatViewCount),
                    video.uploadDate.takeIf { it.isNotBlank() },
                ).joinToString(" • ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.extendedColors.textSecondary,
                )
            }
        }
    }
}

/** GIF posts loop a few times and stop: the feed stays composed under the expanded player, unseen. */
@Composable
private fun rememberPostImageRequest(url: String): ImageRequest {
    val context = LocalPlatformContext.current
    return remember(context, url) {
        ImageRequest
            .Builder(context)
            .data(url)
            .repeatCount(GIF_REPEAT_COUNT)
            .build()
    }
}

private const val GIF_REPEAT_COUNT = 2

private const val VIDEO_ASPECT_RATIO = 16f / 9f

/** Enough of the next image to read as a gallery rather than a cropped photo. */
private val GalleryPeek = 48.dp
