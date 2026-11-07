package io.github.aedev.flow.ui.components.music.section

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.components.music.common.MusicNowPlayingOverlay
import io.github.aedev.flow.ui.components.music.common.isTrackPlaying
import io.github.aedev.flow.ui.components.music.common.rememberMusicArtworkColors
import io.github.aedev.flow.ui.components.music.header.MusicSectionHeader
import io.github.aedev.flow.ui.components.shared.DownloadedBadge
import io.github.aedev.flow.ui.components.shared.flowGridCellWidth
import io.github.aedev.flow.ui.components.shared.flowGridColumns
import io.github.aedev.flow.ui.components.shared.titleMarquee
import io.github.aedev.flow.ui.components.shared.titleMarqueeOverflow
import io.github.aedev.flow.ui.theme.Dimensions

private val SpeedDialRowHeight = 64.dp
private val SpeedDialGap = 8.dp
private const val SPEED_DIAL_ROWS = 3
private const val SPEED_DIAL_MAX_TRACKS = 18
private const val SUBTITLE_ALPHA = 0.8f

/**
 * Three rows of compact tiles the listener reaches for most, led by a shuffle tile. The column
 * count follows the window width, so the section is the same height on every device.
 */
@Composable
fun SpeedDialSection(
    speedDialTracks: List<MusicTrack>,
    onSongClick: (MusicTrack, List<MusicTrack>, String?) -> Unit,
    modifier: Modifier = Modifier,
    downloadedTrackIds: Set<String> = emptySet(),
    onTrackMenu: (MusicTrack) -> Unit = {},
) {
    if (speedDialTracks.isEmpty()) return

    val tiles = remember(speedDialTracks) { speedDialTracks.distinctBy { it.videoId }.take(SPEED_DIAL_MAX_TRACKS) }
    val columns = flowGridColumns(compact = 2, medium = 3, expanded = 4)
    val cellWidth = flowGridCellWidth(columns = columns, gap = SpeedDialGap)

    Column(modifier = modifier.fillMaxWidth()) {
        MusicSectionHeader(title = stringResource(R.string.section_speed_dial))
        LazyHorizontalGrid(
            rows = GridCells.Fixed(SPEED_DIAL_ROWS),
            contentPadding = PaddingValues(horizontal = Dimensions.ContentPaddingHorizontal),
            horizontalArrangement = Arrangement.spacedBy(SpeedDialGap),
            verticalArrangement = Arrangement.spacedBy(SpeedDialGap),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(SpeedDialRowHeight * SPEED_DIAL_ROWS + SpeedDialGap * (SPEED_DIAL_ROWS - 1)),
        ) {
            item(key = "speed_dial_shuffle") {
                SpeedDialShuffleTile(
                    onClick = {
                        val shuffled = speedDialTracks.shuffled()
                        onSongClick(shuffled.first(), shuffled, "speed_dial_shuffle")
                    },
                    modifier = Modifier.width(cellWidth),
                )
            }
            items(items = tiles, key = { "speed_dial:${it.videoId}" }) { track ->
                SpeedDialTile(
                    track = track,
                    isDownloaded = downloadedTrackIds.contains(track.videoId),
                    onClick = { onSongClick(track, speedDialTracks, "speed_dial") },
                    onLongClick = { onTrackMenu(track) },
                    modifier = Modifier.width(cellWidth),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpeedDialTile(
    track: MusicTrack,
    isDownloaded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isPlaying = isTrackPlaying(track.videoId)
    val highlight = if (isPlaying) rememberMusicArtworkColors(track.thumbnailUrl) else null
    val scheme = MaterialTheme.colorScheme
    val titleColor = highlight?.onContainer ?: scheme.onSurface
    val subtitleColor = highlight?.onContainer?.copy(alpha = SUBTITLE_ALPHA) ?: scheme.onSurfaceVariant

    Row(
        modifier =
            modifier
                .fillMaxHeight()
                .clip(MaterialTheme.shapes.medium)
                .background(highlight?.container ?: scheme.surfaceContainerHigh)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f),
        ) {
            AsyncImage(
                model = track.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (highlight != null) {
                MusicNowPlayingOverlay(waveformWidth = 20.dp, waveformHeight = 16.dp, color = highlight.accent)
            }
        }
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = titleColor,
                maxLines = 1,
                overflow = titleMarqueeOverflow(),
                modifier = Modifier.titleMarquee(),
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isDownloaded) {
            DownloadedBadge(modifier = Modifier.padding(end = 12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpeedDialShuffleTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxHeight()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Shuffle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = stringResource(R.string.shuffle_play),
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
        )
    }
}
