/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.ui.components.music.item

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.currentGridThumbnailHeight
import io.github.aedev.flow.ui.components.music.common.MusicNowPlayingOverlay
import io.github.aedev.flow.ui.components.music.common.isTrackPlaying
import io.github.aedev.flow.ui.components.shared.DownloadedBadge
import io.github.aedev.flow.ui.components.shared.titleMarquee
import io.github.aedev.flow.ui.components.shared.titleMarqueeOverflow

/**
 * The single grid card for anything that is not a single track — albums, playlists, artists,
 * music videos and mixes.
 *
 * Shape carries the artist variant (pass CircleShape), [aspectRatio] carries the 16:9 video
 * variant, and [trailingContent] carries an overflow affordance drawn over the artwork. The card's
 * width is [thumbnailHeight] × [aspectRatio], so a lane of cards lines up on the artwork edge
 * whatever its shape.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MusicCollectionCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    thumbnailUrl: String? = null,
    thumbnailHeight: Dp = currentGridThumbnailHeight(),
    aspectRatio: Float = 1f,
    shape: Shape = MaterialTheme.shapes.large,
    fillMaxWidth: Boolean = false,
    mediaId: String? = null,
    isPlaying: Boolean = isTrackPlaying(mediaId),
    isDownloaded: Boolean = false,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    trailingContent: (@Composable BoxScope.() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val textAlign = if (horizontalAlignment == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start

    Column(
        horizontalAlignment = horizontalAlignment,
        modifier =
            modifier
                .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier.width(thumbnailHeight * aspectRatio))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
    ) {
        Box {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier.height(thumbnailHeight))
                        .aspectRatio(aspectRatio)
                        .clip(shape),
            )
            if (isPlaying) {
                MusicNowPlayingOverlay()
            }
            if (isDownloaded) {
                DownloadedBadge(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                )
            }
            trailingContent?.invoke(this)
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.bodyMediumEmphasized,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = textAlign,
            maxLines = 1,
            overflow = titleMarqueeOverflow(),
            modifier = Modifier.titleMarquee(),
        )

        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Overflow affordance for a [MusicCollectionCard], drawn over the top-right of the artwork.
 */
@Composable
fun BoxScope.MusicCardOverflowButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.align(Alignment.TopEnd),
    ) {
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.more_options),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}
