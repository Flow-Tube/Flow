package io.github.aedev.flow.ui.components.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.paging.SearchResultItem
import io.github.aedev.flow.data.paging.SearchShelfKind
import io.github.aedev.flow.innertube.pages.renderer.CommunityPost
import io.github.aedev.flow.ui.components.shared.MediaShortsShelf
import io.github.aedev.flow.ui.components.shared.card.MediaVideoCard
import io.github.aedev.flow.ui.components.shared.card.VideoCardDefaults
import io.github.aedev.flow.ui.components.shared.card.VideoCardLayout

/**
 * One of the strips YouTube interleaves between search results: a creator's latest uploads, an
 * inline Shorts row, or their recent community posts.
 *
 * Each is fenced by dividers, the way YouTube separates a strip from the results around it, and a
 * videos strip opens at the count YouTube itself collapses it to rather than all ten.
 *
 * A strip is always one card per row, so on a wide window its cards take the thumbnail-left shape —
 * a full-width card there is a thumbnail the size of the screen.
 */
@Composable
fun SearchShelf(
    shelf: SearchResultItem.ShelfResult,
    asThumbnailRows: Boolean,
    onVideoClick: (Video) -> Unit,
    onShortsClick: (shelf: List<Video>, tapped: Video) -> Unit,
    onChannelClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onPostClick: (CommunityPost) -> Unit = {},
    thumbnailWidth: Dp = VideoCardDefaults.RowThumbnailWidth,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = SectionSpacing)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // The Shorts strip draws its own branded heading; the other two take the response's title.
        if (shelf.kind != SearchShelfKind.SHORTS) shelf.title?.let { ShelfTitle(it) }
        when (shelf.kind) {
            SearchShelfKind.SHORTS -> {
                MediaShortsShelf(shelf.videos, onShortsClick)
            }

            SearchShelfKind.VIDEOS -> {
                VideoStrip(shelf, asThumbnailRows, thumbnailWidth, onVideoClick)
            }

            SearchShelfKind.POSTS -> {
                PostStrip(shelf, onPostClick)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ShelfTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = StripHorizontalPadding, vertical = TitleVerticalPadding),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VideoStrip(
    shelf: SearchResultItem.ShelfResult,
    asThumbnailRows: Boolean,
    thumbnailWidth: Dp,
    onVideoClick: (Video) -> Unit,
) {
    var expanded by rememberSaveable(shelf.id) { mutableStateOf(false) }
    val collapsedCount = shelf.collapsedItemCount ?: shelf.videos.size
    val shown =
        remember(shelf.videos, expanded, collapsedCount) {
            if (expanded) shelf.videos else shelf.videos.take(collapsedCount)
        }

    shown.forEach { video ->
        MediaVideoCard(
            video = video,
            layout = if (asThumbnailRows) VideoCardLayout.Row else VideoCardLayout.Stacked,
            onClick = { onVideoClick(video) },
            thumbnailWidth = thumbnailWidth,
        )
    }
    if (shelf.videos.size > collapsedCount) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(onClick = { expanded = !expanded }, shapes = ButtonDefaults.shapes()) {
                Text(
                    text =
                        stringResource(
                            if (expanded) R.string.search_shelf_show_less else R.string.search_shelf_show_more,
                        ),
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.padding(start = ButtonIconSpacing),
                )
            }
        }
    }
}

@Composable
private fun PostStrip(
    shelf: SearchResultItem.ShelfResult,
    onPostClick: (CommunityPost) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = StripHorizontalPadding, vertical = StripVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(PostSpacing),
    ) {
        items(shelf.posts, key = { it.id }) { post ->
            SearchPostCard(post = post, onClick = { onPostClick(post) })
        }
    }
}

private val SectionSpacing = 8.dp
private val StripHorizontalPadding = 12.dp
private val StripVerticalPadding = 8.dp
private val TitleVerticalPadding = 10.dp
private val PostSpacing = 10.dp
private val ButtonIconSpacing = 6.dp
