package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.local.HomeFeedColumns
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.pages.renderer.CommunityPost
import io.github.aedev.flow.innertube.pages.renderer.FeedItem
import io.github.aedev.flow.innertube.pages.renderer.FeedShelf
import io.github.aedev.flow.innertube.pages.renderer.FeedShelfStyle
import io.github.aedev.flow.ui.components.FEED_MAX_AUTO_COLUMNS
import io.github.aedev.flow.ui.components.FeedGridLayout
import io.github.aedev.flow.ui.components.PlaylistCard
import io.github.aedev.flow.ui.components.PlaylistCardLayout
import io.github.aedev.flow.ui.components.feedCardsFormGrid
import io.github.aedev.flow.ui.components.feedShelfPreviewCount
import io.github.aedev.flow.ui.components.rememberFeedGridLayout
import io.github.aedev.flow.ui.components.shared.card.MediaVideoCard
import io.github.aedev.flow.ui.components.shared.card.VideoCardDensity
import io.github.aedev.flow.ui.components.shared.card.VideoCardLayout

@Composable
internal fun ShelfItem(
    item: FeedItem,
    gridCard: Boolean,
    rowThumbnailWidth: Dp,
    layout: FeedGridLayout,
    actions: FeedShelfActions,
    slots: FeedShelfSlots,
    showChannelInfo: Boolean,
) {
    when (item) {
        is FeedItem.VideoItem -> {
            ShelfVideoCard(
                video = item.video,
                gridCard = gridCard,
                rowThumbnailWidth = rowThumbnailWidth,
                density = layout.cardDensity,
                showChannelInfo = showChannelInfo,
                onClick = { actions.onVideoClick(item.video) },
            )
        }

        is FeedItem.ShortItem -> {
            ShelfVideoCard(
                video = item.video,
                gridCard = gridCard,
                rowThumbnailWidth = rowThumbnailWidth,
                density = layout.cardDensity,
                showChannelInfo = showChannelInfo,
                onClick = { actions.onShortClick(item.video.id) },
            )
        }

        is FeedItem.PlaylistItem -> {
            if (gridCard) {
                PlaylistCard(
                    playlist = item.playlist,
                    onClick = { actions.onPlaylistClick(item.playlist.id) },
                    layout = PlaylistCardLayout.SHELF,
                    modifier = Modifier.padding(horizontal = layout.cardInset),
                )
            } else {
                PlaylistCard(playlist = item.playlist, onClick = { actions.onPlaylistClick(item.playlist.id) })
            }
        }

        is FeedItem.RelatedChannelItem -> {
            slots.channelRow?.invoke(item.channel, item.channel.id in actions.subscribedChannelIds)
        }

        is FeedItem.PostItem -> {
            slots.post?.invoke(item.post, false)
        }
    }
}

/** Expanding a shelf slides the rows below it down and fades the new cards in, on the theme's springs. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LazyGridItemScope.shelfItemMotion(): Modifier {
    val motion = MaterialTheme.motionScheme
    return Modifier.animateItem(
        fadeInSpec = motion.defaultEffectsSpec(),
        placementSpec = motion.defaultSpatialSpec(),
        fadeOutSpec = motion.fastEffectsSpec(),
    )
}

@Composable
private fun ShelfVideoCard(
    video: Video,
    gridCard: Boolean,
    rowThumbnailWidth: Dp,
    density: VideoCardDensity,
    showChannelInfo: Boolean,
    onClick: () -> Unit,
) {
    if (gridCard) {
        MediaVideoCard(
            video = video,
            showChannel = showChannelInfo,
            onClick = onClick,
            density = density,
        )
    } else {
        MediaVideoCard(
            video = video,
            layout = VideoCardLayout.Row,
            showChannel = showChannelInfo,
            onClick = onClick,
            thumbnailWidth = rowThumbnailWidth,
            density = density,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PostsShelf(
    posts: List<CommunityPost>,
    post: @Composable (post: CommunityPost, compact: Boolean) -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    HorizontalMultiBrowseCarousel(
        state = rememberCarouselState { posts.size },
        preferredItemWidth = PostShelfCardWidth,
        itemSpacing = 12.dp,
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .height(PostShelfHeight),
    ) { index ->
        val item = posts[index]
        Card(
            shape = shape,
            colors = CardDefaults.outlinedCardColors(),
            modifier =
                Modifier
                    .fillMaxHeight()
                    .maskClip(shape)
                    .maskBorder(CardDefaults.outlinedCardBorder(), shape),
        ) {
            post(item, true)
        }
    }
}

@Composable
internal fun ShelfHeader(
    title: String?,
    hasMore: Boolean,
    onClick: () -> Unit,
) {
    if (title.isNullOrBlank()) return
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (hasMore) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hasMore) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ShelfExpander(
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, shapes = IconButtonDefaults.shapes()) {
            Icon(
                imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val PostShelfCardWidth = 380.dp

/** Header, four lines of text, a 16:9 crop of the item width and the action row. */
private val PostShelfHeight = 440.dp
