/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.ui.components.music.card

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.ui.components.music.common.MusicNowPlayingOverlay
import io.github.aedev.flow.ui.components.music.common.isTrackPlaying
import io.github.aedev.flow.ui.components.shared.DownloadedBadge
import io.github.aedev.flow.ui.components.shared.titleMarquee
import io.github.aedev.flow.ui.components.shared.titleMarqueeOverflow

val MusicHeroCaptionHeight = 60.dp

/**
 * One item of a hero lane: artwork on top, the caption on the page below it. Only the artwork is
 * masked, through [artModifier], so the caption never loses its corners to the mask. Fills
 * whatever size the lane gives it, so the same card serves square mixes and 16:9 videos.
 *
 * [captionAlpha] is read in the draw phase only, so the lane can fade the caption out as the item
 * shrinks into a preview without recomposing anything while it scrolls.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MusicHeroCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artModifier: Modifier = Modifier,
    subtitle: String? = null,
    thumbnailUrl: String? = null,
    artwork: (@Composable BoxScope.() -> Unit)? = null,
    mediaId: String? = null,
    isDownloaded: Boolean = false,
    captionAlpha: () -> Float = { 1f },
    onLongClick: (() -> Unit)? = null,
) {
    val isPlaying = isTrackPlaying(mediaId)

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(artModifier),
        ) {
            if (artwork != null) {
                artwork()
            } else {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (isPlaying) {
                MusicNowPlayingOverlay()
            }
            if (isDownloaded) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                ) {
                    DownloadedBadge(modifier = Modifier.padding(6.dp))
                }
            }
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(MusicHeroCaptionHeight)
                    .graphicsLayer { alpha = captionAlpha() }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = titleMarqueeOverflow(),
                modifier = Modifier.titleMarquee(),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
