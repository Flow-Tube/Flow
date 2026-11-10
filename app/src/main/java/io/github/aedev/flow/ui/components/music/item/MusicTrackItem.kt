/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.ui.components.music.item

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.components.music.common.MusicNowPlayingOverlay
import io.github.aedev.flow.ui.components.music.common.isTrackPlaying
import io.github.aedev.flow.ui.components.music.common.rememberMusicArtworkColors
import io.github.aedev.flow.ui.components.shared.DownloadedBadge
import io.github.aedev.flow.ui.components.shared.ExplicitBadge
import io.github.aedev.flow.ui.components.shared.onMenuKey
import io.github.aedev.flow.ui.theme.Dimensions
import io.github.aedev.flow.utils.formatDuration
import io.github.aedev.flow.utils.formatViewCount

private const val SUBTITLE_ALPHA = 0.6f
private const val HIGHLIGHT_SUBTITLE_ALPHA = 0.8f

/**
 * Row height and artwork size. Comfortable is the default browse density; Compact is for dense
 * lists such as a playlist body, where more rows on screen matters more than artwork size.
 */
enum class MusicItemDensity {
    Comfortable,
    Compact,
}

private val MusicItemDensity.rowHeight: Dp
    get() = if (this == MusicItemDensity.Comfortable) 72.dp else Dimensions.ListItemHeight

private val MusicItemDensity.thumbnailSize: Dp
    get() = if (this == MusicItemDensity.Comfortable) 56.dp else Dimensions.ListThumbnailSize

private val MusicItemDensity.horizontalPadding: Dp
    get() = if (this == MusicItemDensity.Comfortable) 16.dp else Dimensions.ContentPaddingHorizontal

/**
 * The single row that renders one track anywhere in the app — browse shelves, search results,
 * playlist bodies, queues, chart lanes and download lists.
 *
 * Variants are expressed through the slots rather than through separate composables: [index] or
 * [leadingContent] for anything before the artwork, [thumbnailOverlay] for progress or state drawn
 * on it, [trailingContent] for an extra affordance before the overflow menu. [leadingContent] wins
 * when both it and [index] are supplied.
 *
 * The playing row keeps its shape and takes its container and text colours from its own artwork,
 * so the highlight is the record's colour rather than the theme's.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MusicTrackItem(
    track: MusicTrack,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    density: MusicItemDensity = MusicItemDensity.Comfortable,
    index: Int? = null,
    shape: Shape = RectangleShape,
    containerColor: Color = Color.Transparent,
    thumbnailWidth: Dp = density.thumbnailSize,
    isPlaying: Boolean = isTrackPlaying(track.videoId),
    isDownloaded: Boolean = false,
    showMenu: Boolean = true,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    thumbnailOverlay: (@Composable BoxScope.() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onMenuClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val highlight = if (isPlaying) rememberMusicArtworkColors(track.thumbnailUrl) else null
    val rowColor = highlight?.container ?: containerColor
    val titleColor = highlight?.onContainer ?: scheme.onBackground
    val subtitleColor = highlight?.onContainer?.copy(alpha = HIGHLIGHT_SUBTITLE_ALPHA) ?: scheme.onBackground.copy(alpha = SUBTITLE_ALPHA)
    val accentColor = highlight?.accent ?: scheme.onSurfaceVariant

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(density.rowHeight)
                .clip(shape)
                .background(rowColor)
                .then(if (onLongClick != null) Modifier.onMenuKey(onLongClick) else Modifier)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ).padding(horizontal = density.horizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leadingContent != null -> {
                leadingContent()
                Spacer(modifier = Modifier.width(8.dp))
            }

            index != null -> {
                Text(
                    text = index.toString(),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = accentColor,
                    modifier = Modifier.width(32.dp),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
        }

        Surface(
            shape = MaterialTheme.shapes.small,
            modifier =
                Modifier
                    .width(thumbnailWidth)
                    .height(density.thumbnailSize),
            tonalElevation = 4.dp,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(context)
                            .data(track.listThumbnailUrl)
                            .crossfade(true)
                            .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                if (highlight != null) {
                    MusicNowPlayingOverlay(color = highlight.accent)
                }
                thumbnailOverlay?.invoke(this)
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (track.isExplicit == true) {
                    ExplicitBadge()
                }

                Text(
                    text = track.metadataLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (isDownloaded) {
            DownloadedBadge(modifier = Modifier.padding(start = 8.dp))
        }

        trailingContent?.invoke(this)

        if (showMenu) {
            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.more_options),
                    tint = if (highlight != null) highlight.onContainer else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MusicTrack.metadataLine(): String {
    val suffix =
        when {
            duration > 0 -> formatDuration(duration)
            views > 0 -> formatViewCount(views)
            else -> null
        }
    return if (suffix != null) stringResource(R.string.year_artist_template, artist, suffix) else artist
}
