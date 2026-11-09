package io.github.aedev.flow.ui.components.shared.card

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.ui.components.shared.MediaThumbnailDefaults
import io.github.aedev.flow.ui.components.shared.ShimmerBone

private val TitleLineHeight = 16.dp
private val MetaLineHeight = 12.dp
private val DenseTextTopPadding = 6.dp
private val DenseTextBottomPadding = 8.dp

/**
 * A loading placeholder shaped exactly like [MediaVideoCard] with the same [layout], [density],
 * widths, shapes and paddings, so the real card lands where its skeleton was.
 */
@Composable
fun VideoCardSkeleton(
    modifier: Modifier = Modifier,
    layout: VideoCardLayout = VideoCardLayout.Stacked,
    useInternalPadding: Boolean = true,
    thumbnailWidth: Dp = VideoCardDefaults.RowThumbnailWidth,
    density: VideoCardDensity = VideoCardDensity.Regular,
) {
    when (layout) {
        VideoCardLayout.Stacked -> StackedSkeleton(useInternalPadding, density, modifier)
        VideoCardLayout.Row -> RowSkeleton(thumbnailWidth, density.inset, modifier)
    }
}

@Composable
private fun StackedSkeleton(
    useInternalPadding: Boolean,
    density: VideoCardDensity,
    modifier: Modifier,
) {
    val dense = density == VideoCardDensity.Dense
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (useInternalPadding) Modifier.padding(horizontal = density.inset) else Modifier),
    ) {
        ShimmerBone(
            modifier = Modifier.fillMaxWidth().aspectRatio(MediaThumbnailDefaults.VideoAspectRatio),
            shape = if (dense) MaterialTheme.shapes.medium else MaterialTheme.shapes.large,
        )
        if (dense) {
            TextLines(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = DenseTextTopPadding, bottom = DenseTextBottomPadding),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(VideoCardDefaults.Inset),
                horizontalArrangement = Arrangement.spacedBy(VideoCardDefaults.Inset),
            ) {
                ShimmerBone(modifier = Modifier.size(VideoCardDefaults.AvatarSize), shape = CircleShape, delayMillis = 80)
                TextLines(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RowSkeleton(
    thumbnailWidth: Dp,
    inset: Dp,
    modifier: Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    start = inset,
                    end = VideoCardDefaults.Inset,
                    top = VideoCardDefaults.RowVerticalPadding,
                    bottom = VideoCardDefaults.RowVerticalPadding,
                ),
        horizontalArrangement = Arrangement.spacedBy(VideoCardDefaults.Inset),
    ) {
        ShimmerBone(
            modifier = Modifier.width(thumbnailWidth).aspectRatio(MediaThumbnailDefaults.VideoAspectRatio),
            shape = MaterialTheme.shapes.medium,
        )
        TextLines(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TextLines(modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShimmerBone(modifier = Modifier.fillMaxWidth(0.92f).height(TitleLineHeight), delayMillis = 120)
        ShimmerBone(modifier = Modifier.fillMaxWidth(0.65f).height(TitleLineHeight), delayMillis = 160)
        ShimmerBone(
            modifier = Modifier.fillMaxWidth(0.5f).height(MetaLineHeight),
            shape = MaterialTheme.shapes.extraSmall,
            delayMillis = 200,
        )
    }
}
