package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache

object MediaThumbnailDefaults {
    val VideoWidth: Dp = 152.dp
    val ArtworkSize: Dp = 56.dp
    val CollectionWidth: Dp = 88.dp
    val VideoAspectRatio: Float = 16f / 9f
    val BadgePadding: Dp = 6.dp
    val PlaceholderSize: Dp = 24.dp
    const val PLACEHOLDER_ALPHA = 0.4f
}

@Composable
fun MediaThumbnail(
    videoId: String,
    thumbnailUrl: String?,
    modifier: Modifier = Modifier,
    width: Dp = MediaThumbnailDefaults.VideoWidth,
    shape: Shape = MaterialTheme.shapes.small,
    durationSeconds: Int? = null,
    showWatchProgress: Boolean = false,
    placeholder: ImageVector? = null,
    onModelLoaded: ((Any) -> Unit)? = null,
    youTubeFrames: Boolean = LocalYouTubeFramesOnly.current,
    showImage: Boolean = true,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val openOrigin = rememberMediaOpenOrigin(videoId, shape)
    val reportImageKey: ((MemoryCache.Key?) -> Unit)? =
        remember(openOrigin) { openOrigin?.let { registration -> { key -> registration.imageKey = key } } }
    Box(
        modifier =
            modifier
                .width(width)
                .aspectRatio(MediaThumbnailDefaults.VideoAspectRatio)
                .mediaOpenOrigin(openOrigin)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (placeholder != null) {
            Icon(
                imageVector = placeholder,
                contentDescription = null,
                tint =
                    MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = MediaThumbnailDefaults.PLACEHOLDER_ALPHA),
                modifier = Modifier.size(MediaThumbnailDefaults.PlaceholderSize),
            )
        }

        if (showImage) {
            VideoThumbnailImage(
                videoId = videoId,
                model = thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onImageKey = reportImageKey,
                onModelLoaded = onModelLoaded,
                youTubeFrames = youTubeFrames,
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

        if (showWatchProgress) {
            ThumbnailWatchProgress(
                videoId = videoId,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        overlay()
    }
}

@Composable
fun ArtworkThumbnail(
    thumbnailUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = MediaThumbnailDefaults.ArtworkSize,
    shape: Shape = MaterialTheme.shapes.small,
    placeholder: ImageVector? = null,
) {
    Box(
        modifier =
            modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (placeholder != null) {
            Icon(
                imageVector = placeholder,
                contentDescription = null,
                tint =
                    MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = MediaThumbnailDefaults.PLACEHOLDER_ALPHA),
                modifier = Modifier.size(MediaThumbnailDefaults.PlaceholderSize),
            )
        }

        if (thumbnailUrl != null) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
fun CollectionThumbnail(
    thumbnailUrl: String?,
    modifier: Modifier = Modifier,
    width: Dp = MediaThumbnailDefaults.CollectionWidth,
    shape: Shape = MaterialTheme.shapes.small,
    placeholder: ImageVector? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier =
            modifier
                .width(width)
                .aspectRatio(MediaThumbnailDefaults.VideoAspectRatio)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (placeholder != null) {
            Icon(
                imageVector = placeholder,
                contentDescription = null,
                tint =
                    MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = MediaThumbnailDefaults.PLACEHOLDER_ALPHA),
                modifier = Modifier.size(MediaThumbnailDefaults.PlaceholderSize),
            )
        }

        thumbnailUrlOrNull(thumbnailUrl)?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        overlay()
    }
}
