/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.ui.components.music.item

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.innertube.models.AlbumItem
import io.github.aedev.flow.innertube.models.ArtistItem
import io.github.aedev.flow.innertube.models.PlaylistItem
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.models.YTItem
import io.github.aedev.flow.ui.components.shared.flowArtistShape
import io.github.aedev.flow.ui.components.shared.onMenuKey
import io.github.aedev.flow.ui.screens.music.convertSongToMusicTrack

/**
 * List-shaped row for any InnerTube item. Songs are handed to [MusicTrackItem] so a song looks the
 * same in search results as it does everywhere else; artists, albums and playlists render here.
 *
 * [showPlayCount] appends the play count to a song's subtitle — the only difference between the
 * two search rows this replaced.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MusicCollectionRow(
    item: YTItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDownloaded: Boolean = false,
    showPlayCount: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onMenuClick: (() -> Unit)? = null,
) {
    if (item is SongItem) {
        MusicTrackItem(
            track = convertSongToMusicTrack(item),
            onClick = onClick,
            modifier = modifier,
            isDownloaded = isDownloaded,
            showMenu = onMenuClick != null,
            onLongClick = onLongClick,
            onMenuClick = { onMenuClick?.invoke() },
        )
        return
    }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (onLongClick != null) Modifier.onMenuKey(onLongClick) else Modifier)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.thumbnail,
            contentDescription = null,
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(if (item is ArtistItem) flowArtistShape() else MaterialTheme.shapes.small),
            contentScale = ContentScale.Crop,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLargeEmphasized,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.rowSubtitle(showPlayCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onMenuClick != null) {
            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.more_options),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun YTItem.rowSubtitle(showPlayCount: Boolean): String =
    when (this) {
        is SongItem -> {
            val prefix = stringResource(R.string.subtitle_song_prefix, artists.joinToString { it.name })
            val plays = viewCountText?.takeIf { showPlayCount }?.let { stringResource(R.string.plays_count_template, it) }
            listOfNotNull(prefix, plays).joinToString(" ")
        }

        is ArtistItem -> {
            stringResource(if (isProfile) R.string.subtitle_profile else R.string.subtitle_artist)
        }

        is AlbumItem -> {
            stringResource(R.string.album_year_template, artists?.joinToString { it.name } ?: "", year ?: "")
        }

        is PlaylistItem -> {
            stringResource(R.string.subtitle_playlist_template, author?.name ?: "")
        }
    }
