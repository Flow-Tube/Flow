package io.github.aedev.flow.ui.components.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.DownloadedTrack
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.video.DownloadedVideo
import io.github.aedev.flow.ui.components.shared.MediaShortCard
import io.github.aedev.flow.ui.components.shared.ShimmerBone
import io.github.aedev.flow.ui.components.shared.ShortCardDefaults
import io.github.aedev.flow.ui.components.shared.dpadShelfRow

private const val PLACEHOLDER_CARD_COUNT = 2
private const val PLACEHOLDER_STAGGER_MS = 120
private val HeaderIconSize = 20.dp

@Composable
private fun LibraryShelfHeader(
    title: String,
    icon: ImageVector,
    showChevron: Boolean,
    horizontalInset: Dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalInset, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(HeaderIconSize),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun LibraryShelf(
    title: String,
    icon: ImageVector,
    onTitleClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    horizontalInset: Dp = 16.dp,
    content: LazyListScope.(cardWidth: Dp) -> Unit,
) {
    val headerFocus = remember { FocusRequester() }
    var headerHasFocus by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val cardWidth = libraryShelfCardWidth(maxWidth)
        Column(modifier = Modifier.fillMaxWidth()) {
            LibraryShelfHeader(
                title = title,
                icon = icon,
                showChevron = onTitleClick != null,
                horizontalInset = horizontalInset,
                modifier =
                    if (onTitleClick != null) {
                        Modifier
                            .focusRequester(headerFocus)
                            .onFocusChanged { headerHasFocus = it.isFocused }
                            .clickable(onClick = onTitleClick)
                    } else {
                        Modifier
                    },
            )

            LazyRow(
                modifier = if (onTitleClick != null) Modifier.dpadShelfRow(headerFocus) { headerHasFocus } else Modifier,
                contentPadding = PaddingValues(horizontal = horizontalInset),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content(cardWidth)
            }
        }
    }
}

/** A shelf with nothing in it yet that still opens its page, with [message] where the cards go. */
@Composable
internal fun LibraryEmptyShelf(
    title: String,
    icon: ImageVector,
    message: String,
    onTitleClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onTitleClick)) {
        LibraryShelfHeader(title = title, icon = icon, showChevron = true, horizontalInset = 16.dp)
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
internal fun LibraryMediaShelf(
    title: String,
    icon: ImageVector,
    items: List<LibraryMediaItem>,
    sourceName: String,
    onTitleClick: () -> Unit,
    onVideoClick: (Video) -> Unit,
    onMusicClick: (MusicTrack, List<MusicTrack>, String) -> Unit,
    onDownloadedVideoClick: (List<DownloadedVideo>, Int) -> Unit,
    onDownloadedMusicClick: (List<DownloadedTrack>, Int) -> Unit,
) {
    val musicQueue =
        remember(items) {
            items.mapNotNull { (it as? LibraryMediaItem.MusicItem)?.track }
        }
    val downloadedVideoQueue =
        remember(items) {
            items.mapNotNull { (it as? LibraryMediaItem.DownloadedVideoItem)?.download }
        }
    val downloadedMusicQueue =
        remember(items) {
            items.mapNotNull { (it as? LibraryMediaItem.DownloadedMusicItem)?.download }
        }

    LibraryShelf(title = title, icon = icon, onTitleClick = onTitleClick) { cardWidth ->
        val artworkSize = cardWidth * 9f / 16f
        items(
            items = items,
            key = LibraryMediaItem::key,
            contentType = {
                when (it) {
                    is LibraryMediaItem.VideoItem,
                    is LibraryMediaItem.DownloadedVideoItem,
                    -> "video"

                    is LibraryMediaItem.MusicItem,
                    is LibraryMediaItem.DownloadedMusicItem,
                    -> "music"
                }
            },
        ) { item ->
            when (item) {
                is LibraryMediaItem.VideoItem -> {
                    LibraryShelfVideoCard(
                        video = item.video,
                        onClick = { onVideoClick(item.video) },
                        width = cardWidth,
                    )
                }

                is LibraryMediaItem.MusicItem -> {
                    LibraryAlbumCard(
                        title = item.track.title,
                        subtitle = item.track.artist,
                        thumbnailUrl = item.track.thumbnailUrl,
                        onClick = { onMusicClick(item.track, musicQueue, sourceName) },
                        artworkSize = artworkSize,
                    )
                }

                is LibraryMediaItem.DownloadedVideoItem -> {
                    LibraryShelfVideoCard(
                        video = item.download.video,
                        onClick = {
                            val index =
                                downloadedVideoQueue.indexOfFirst {
                                    it.video.id == item.download.video.id
                                }
                            if (index >= 0) onDownloadedVideoClick(downloadedVideoQueue, index)
                        },
                        width = cardWidth,
                    )
                }

                is LibraryMediaItem.DownloadedMusicItem -> {
                    LibraryAlbumCard(
                        title = item.download.track.title,
                        subtitle = item.download.track.artist,
                        thumbnailUrl = item.download.track.thumbnailUrl,
                        isDownloaded = true,
                        onClick = {
                            val index =
                                downloadedMusicQueue.indexOfFirst {
                                    it.track.videoId == item.download.track.videoId
                                }
                            if (index >= 0) onDownloadedMusicClick(downloadedMusicQueue, index)
                        },
                        artworkSize = artworkSize,
                    )
                }
            }
        }
    }
}

@Composable
internal fun LibraryShortsShelf(
    title: String,
    icon: ImageVector,
    shorts: List<Video>,
    onTitleClick: () -> Unit,
    onShortClick: (Video) -> Unit,
) {
    LibraryShelf(title = title, icon = icon, onTitleClick = onTitleClick) {
        items(shorts, key = Video::id, contentType = { "short" }) { short ->
            MediaShortCard(video = short, onClick = { onShortClick(short) }, removableFromSavedShorts = true)
        }
    }
}

@Composable
internal fun LibraryShelfPlaceholder(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    portrait: Boolean = false,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val cardWidth = if (portrait) ShortCardDefaults.MinWidth else libraryShelfCardWidth(maxWidth)
        val aspectRatio = if (portrait) ShortCardDefaults.ASPECT_RATIO else 16f / 9f
        Column(modifier = Modifier.fillMaxWidth()) {
            LibraryShelfHeader(
                title = title,
                icon = icon,
                showChevron = false,
                horizontalInset = 16.dp,
            )

            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(PLACEHOLDER_CARD_COUNT) { index ->
                    Column(
                        modifier = Modifier.width(cardWidth),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ShimmerBone(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(aspectRatio),
                            shape = MaterialTheme.shapes.medium,
                            delayMillis = index * PLACEHOLDER_STAGGER_MS,
                        )
                        ShimmerBone(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(14.dp),
                            shape = MaterialTheme.shapes.extraSmall,
                            delayMillis = index * PLACEHOLDER_STAGGER_MS,
                        )
                    }
                }
            }
        }
    }
}
