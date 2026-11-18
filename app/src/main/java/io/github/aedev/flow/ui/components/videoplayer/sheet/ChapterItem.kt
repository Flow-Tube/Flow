package io.github.aedev.flow.ui.components.videoplayer.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.innertube.models.response.VideoChapter
import io.github.aedev.flow.ui.components.shared.DurationBadge
import io.github.aedev.flow.ui.components.shared.MediaArtworkTint
import io.github.aedev.flow.ui.components.shared.MediaThumbnailDefaults
import io.github.aedev.flow.ui.components.shared.thumbnailUrlOrNull
import io.github.aedev.flow.utils.formatDuration

private val ThumbnailWidth: Dp = 104.dp

/**
 * One chapter in a segmented list. The chapter in play takes the artwork tint and shows how far
 * into it the playhead is; [shape] is its place in the group.
 */
@Composable
internal fun ChapterItem(
    chapter: VideoChapter,
    isCurrent: Boolean,
    progress: Float,
    durationSeconds: Int?,
    thumbnailUrl: String,
    shape: Shape,
    tint: MediaArtworkTint,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = shape,
        color = if (isCurrent) tint.container else colors.surfaceContainer,
        contentColor = if (isCurrent) tint.onContainer else colors.onSurface,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .clickable(onClick = onClick)
                // Being the chapter in play is carried by colour, which a screen reader cannot see.
                .semantics { selected = isCurrent },
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChapterThumbnail(thumbnailUrl = thumbnailUrl, durationSeconds = durationSeconds)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = chapter.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatDuration(chapter.startTimeSeconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isCurrent) tint.onContainer else colors.onSurfaceVariant,
                )
                if (isCurrent) {
                    LinearProgressIndicator(
                        progress = { progress },
                        color = tint.accent,
                        trackColor = tint.raised,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterThumbnail(
    thumbnailUrl: String,
    durationSeconds: Int?,
) {
    Box(
        modifier =
            Modifier
                .width(ThumbnailWidth)
                .aspectRatio(MediaThumbnailDefaults.VideoAspectRatio)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        thumbnailUrlOrNull(thumbnailUrl)?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        if (durationSeconds != null && durationSeconds > 0) {
            DurationBadge(
                seconds = durationSeconds,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(MediaThumbnailDefaults.BadgePadding),
            )
        }
    }
}
