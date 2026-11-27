package io.github.aedev.flow.ui.screens.player.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.RelatedCollection
import io.github.aedev.flow.ui.components.knownVideoCount
import io.github.aedev.flow.ui.components.shared.CollectionThumbnail
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation
import io.github.aedev.flow.ui.components.shared.MediaTextBadge
import io.github.aedev.flow.ui.components.shared.MediaThumbnailDefaults
import io.github.aedev.flow.ui.components.shared.card.VideoCardDefaults
import io.github.aedev.flow.ui.components.shared.card.VideoCardLayout
import io.github.aedev.flow.ui.components.shared.focusLift
import io.github.aedev.flow.ui.components.shared.pressScale
import io.github.aedev.flow.ui.theme.cardTitle
import io.github.aedev.flow.ui.theme.cardTitleSmall

/**
 * How a playlist sits among the related videos: the same [layout], [thumbnailWidth], [inset] and
 * [horizontalPadding] the video cards around it use, so its thumbnail and text line up with theirs.
 */
internal data class RelatedPlaylistMetrics(
    val layout: VideoCardLayout,
    val thumbnailWidth: Dp = VideoCardDefaults.RowThumbnailWidth,
    val inset: Dp = VideoCardDefaults.Inset,
    val horizontalPadding: Dp = 0.dp,
)

/**
 * A playlist or Mix in the related lane, drawn like the related video cards: YouTube's own badge
 * ("Mix", "25 videos") where a video shows its length, then the title and YouTube's lines under it.
 */
@Composable
internal fun RelatedPlaylistCard(
    card: RelatedCollection.PlaylistCard,
    metrics: RelatedPlaylistMetrics,
    onClick: () -> Unit,
) {
    val badge =
        card.labels.badge
            ?: card.playlist.knownVideoCount()?.let { pluralStringResource(R.plurals.videos_count_template, it, it) }
    val byline = card.labels.byline
    val details = card.labels.details
    val interactionSource = remember { MutableInteractionSource() }
    val clickable =
        Modifier
            .padding(horizontal = metrics.horizontalPadding)
            .fillMaxWidth()
            .pressScale(interactionSource)
            .focusLift(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(focusRingShape = MaterialTheme.shapes.large),
                onClick = onClick,
            )
    when (metrics.layout) {
        VideoCardLayout.Row -> {
            Row(
                modifier =
                    clickable.padding(
                        start = metrics.inset,
                        top = VideoCardDefaults.RowVerticalPadding,
                        bottom = VideoCardDefaults.RowVerticalPadding,
                    ),
            ) {
                PlaylistThumbnail(card, badge, metrics.thumbnailWidth, MaterialTheme.shapes.medium, Modifier)
                Spacer(modifier = Modifier.width(VideoCardDefaults.Inset))
                Column(modifier = Modifier.weight(1f)) {
                    PlaylistTitle(card.playlist.name, MaterialTheme.typography.cardTitleSmall)
                    Spacer(modifier = Modifier.height(VideoCardDefaults.RowTitleGap))
                    byline?.let { PlaylistLine(it, maxLines = 1) }
                    details?.let { PlaylistLine(it, maxLines = 2) }
                }
                MenuButtonRoom()
            }
        }

        VideoCardLayout.Stacked -> {
            Column(modifier = clickable.padding(horizontal = metrics.inset)) {
                PlaylistThumbnail(card, badge, Dp.Unspecified, MaterialTheme.shapes.large, Modifier.fillMaxWidth())
                Row(modifier = Modifier.padding(VideoCardDefaults.Inset)) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(VideoCardDefaults.StackedTextSpacing),
                    ) {
                        PlaylistTitle(card.playlist.name, MaterialTheme.typography.cardTitle)
                        val line =
                            when {
                                byline != null && details != null -> stringResource(R.string.video_metadata_short_template, byline, details)
                                else -> byline ?: details
                            }
                        line?.let { PlaylistLine(it, maxLines = 2) }
                    }
                    MenuButtonRoom()
                }
            }
        }
    }
}

@Composable
private fun PlaylistThumbnail(
    card: RelatedCollection.PlaylistCard,
    badge: String?,
    width: Dp,
    shape: Shape,
    modifier: Modifier,
) {
    CollectionThumbnail(
        thumbnailUrl = card.playlist.thumbnailUrl,
        width = width,
        shape = shape,
        placeholder = Icons.AutoMirrored.Filled.PlaylistPlay,
        modifier = modifier,
    ) {
        if (badge != null) {
            MediaTextBadge(
                text = badge,
                leadingIcon = Icons.AutoMirrored.Filled.PlaylistPlay,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(MediaThumbnailDefaults.BadgePadding),
            )
        }
    }
}

@Composable
private fun PlaylistTitle(
    title: String,
    style: TextStyle,
) {
    Text(
        text = title,
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PlaylistLine(
    text: String,
    maxLines: Int,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The room a video card's ⋮ button takes, so the playlist's text wraps where the videos' text does. */
@Composable
private fun MenuButtonRoom() {
    if (!LocalDpadNavigation.current) Spacer(modifier = Modifier.width(LocalMinimumInteractiveComponentSize.current))
}
