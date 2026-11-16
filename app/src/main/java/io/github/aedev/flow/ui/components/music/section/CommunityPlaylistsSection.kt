package io.github.aedev.flow.ui.components.music.section

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.CommunityMusicPlaylist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.components.music.common.MusicMosaicThumbnail
import io.github.aedev.flow.ui.components.music.header.MusicSectionHeader
import io.github.aedev.flow.ui.components.music.item.MusicItemDensity
import io.github.aedev.flow.ui.components.music.item.MusicTrackItem
import io.github.aedev.flow.ui.components.shared.flowLaneItemWidth
import io.github.aedev.flow.ui.components.shared.onMenuKey
import io.github.aedev.flow.ui.theme.Dimensions

private const val PREVIEW_TRACKS = 3
private val CommunityCardMaxWidth = 360.dp
private val CommunityCardPeek = 48.dp

@Composable
fun CommunityPlaylistsSection(
    playlists: List<CommunityMusicPlaylist>,
    onPlaylistClick: (CommunityMusicPlaylist) -> Unit,
    onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit,
    modifier: Modifier = Modifier,
    onPlaylistAction: (CommunityMusicPlaylist) -> Unit = {},
    onTrackMenu: (MusicTrack) -> Unit = {},
    downloadedTrackIds: Set<String> = emptySet(),
) {
    val uniquePlaylists = remember(playlists) { playlists.distinctBy { it.playlist.id } }
    if (uniquePlaylists.isEmpty()) return
    val cardWidth = flowLaneItemWidth(maxWidth = CommunityCardMaxWidth, peek = CommunityCardPeek)

    Column(modifier = modifier.fillMaxWidth()) {
        MusicSectionHeader(title = stringResource(R.string.section_from_the_community))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimensions.ContentPaddingHorizontal),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.ItemSpacing),
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            items(uniquePlaylists, key = { it.playlist.id }) { item ->
                CommunityPlaylistCard(
                    item = item,
                    onPlaylistClick = { onPlaylistClick(item) },
                    onTrackClick = { track -> onTrackClick(track, item.tracks) },
                    onPlaylistAction = { onPlaylistAction(item) },
                    onTrackMenu = onTrackMenu,
                    downloadedTrackIds = downloadedTrackIds,
                    modifier = Modifier.width(cardWidth),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CommunityPlaylistCard(
    item: CommunityMusicPlaylist,
    onPlaylistClick: () -> Unit,
    onTrackClick: (MusicTrack) -> Unit,
    modifier: Modifier = Modifier,
    onPlaylistAction: () -> Unit = {},
    onTrackMenu: (MusicTrack) -> Unit = {},
    downloadedTrackIds: Set<String> = emptySet(),
) {
    Card(
        modifier =
            modifier.onMenuKey(onPlaylistAction).combinedClickable(
                onClick = onPlaylistClick,
                onLongClick = onPlaylistAction,
            ),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MusicMosaicThumbnail(
                    tracks = item.tracks,
                    modifier = Modifier.size(88.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.playlist.title,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text =
                            item.playlist.author.ifBlank {
                                item.playlist.trackCount
                                    .takeIf { it > 0 }
                                    ?.let { stringResource(R.string.tracks_count_template, it) } ?: ""
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onPlaylistAction) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.more_options),
                    )
                }
            }

            item.tracks.take(PREVIEW_TRACKS).forEach { track ->
                MusicTrackItem(
                    track = track,
                    density = MusicItemDensity.Compact,
                    isDownloaded = downloadedTrackIds.contains(track.videoId),
                    showMenu = false,
                    shape = MaterialTheme.shapes.medium,
                    onClick = { onTrackClick(track) },
                    onLongClick = { onTrackMenu(track) },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { item.tracks.firstOrNull()?.let(onTrackClick) },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MinHeight, hasStartIcon = true),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                    Text(text = stringResource(R.string.play_all))
                }
                FilledTonalIconButton(
                    onClick = onPlaylistAction,
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BookmarkBorder,
                        contentDescription = stringResource(R.string.add_to_library),
                    )
                }
                FilledTonalIconButton(
                    onClick = onPlaylistClick,
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = stringResource(R.string.ui_navigate),
                    )
                }
            }
        }
    }
}
