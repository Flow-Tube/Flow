package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.video.preferVideoFrameEmbeddedThumbnailKey
import coil3.video.videoFramePercent
import io.github.aedev.flow.data.local.ThumbnailQuality
import io.github.aedev.flow.data.localmedia.localVideoFrameFor
import io.github.aedev.flow.utils.ThumbnailUrlResolver

/**
 * The thumbnail size the viewer chose for the network they are on. Null until the preference has
 * been read, so the first frame never fetches a size that is about to be replaced.
 */
val LocalThumbnailQuality = staticCompositionLocalOf<ThumbnailQuality?> { ThumbnailQuality.HIGH }

/** [portrait] marks a card that crops the frame to 9:16, which keeps it from the smallest tier. */
@Composable
fun VideoThumbnailImage(
    videoId: String,
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    portrait: Boolean = false,
    placeholderKey: MemoryCache.Key? = null,
    onImageKey: ((MemoryCache.Key?) -> Unit)? = null,
    onModelLoaded: ((Any) -> Unit)? = null,
) {
    val quality = LocalThumbnailQuality.current
    val models =
        remember(videoId, model, quality, portrait) {
            when {
                quality == null -> {
                    emptyList()
                }

                model is String || model == null -> {
                    ThumbnailUrlResolver.resolveVideoThumbnailCandidates(videoId, model as? String, quality, portrait)
                }

                else -> {
                    listOf(model)
                }
            }
        }

    // Off draws nothing rather than the fallback fill, so the card's own container and badges show.
    if (models.isEmpty() && quality == ThumbnailQuality.OFF) return
    SafeAsyncImage(
        models = models,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        placeholderKey = placeholderKey,
        onImageKey = onImageKey,
        onModelLoaded = onModelLoaded,
    )
}

/** [url] when the viewer's thumbnail setting allows loading it here, else null. Device covers always load. */
@Composable
@ReadOnlyComposable
fun thumbnailUrlOrNull(url: String?): String? {
    val raw = url?.takeIf { it.isNotBlank() } ?: return null
    return raw.takeUnless { LocalThumbnailQuality.current == ThumbnailQuality.OFF && !ThumbnailUrlResolver.isDeviceUri(raw) }
}

@Composable
private fun SafeAsyncImage(
    models: List<Any>,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    placeholderKey: MemoryCache.Key? = null,
    onImageKey: ((MemoryCache.Key?) -> Unit)? = null,
    onModelLoaded: ((Any) -> Unit)? = null,
) {
    var index by remember(models) { mutableStateOf(0) }
    val currentModel = models.getOrNull(index)

    when {
        currentModel is ImageVector -> {
            Image(
                imageVector = currentModel,
                contentDescription = contentDescription,
                modifier = modifier,
                contentScale = contentScale,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }

        (currentModel is String && currentModel.isNotEmpty()) || currentModel is Int -> {
            val context = LocalPlatformContext.current
            val request =
                remember(currentModel, placeholderKey) {
                    val localFrame = (currentModel as? String)?.let(::localVideoFrameFor)
                    if (placeholderKey == null && localFrame == null) {
                        currentModel
                    } else {
                        ImageRequest
                            .Builder(context)
                            .data(currentModel)
                            .apply {
                                placeholderKey?.let { placeholderMemoryCacheKey(it) }
                                localFrame?.let {
                                    preferVideoFrameEmbeddedThumbnailKey(it.preferEmbeddedThumbnail)
                                    videoFramePercent(it.framePercent)
                                }
                            }.build()
                    }
                }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                modifier = modifier,
                contentScale = contentScale,
                onSuccess =
                    if (onImageKey == null && onModelLoaded == null) {
                        null
                    } else {
                        { state ->
                            onImageKey?.invoke(state.result.memoryCacheKey)
                            onModelLoaded?.invoke(currentModel)
                        }
                    },
                onError = {
                    index = if (index < models.lastIndex) index + 1 else models.size
                },
            )
        }

        else -> {
            Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        }
    }
}
