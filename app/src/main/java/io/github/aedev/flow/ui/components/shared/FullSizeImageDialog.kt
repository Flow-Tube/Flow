package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.gestures.animateZoomBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.size.Scale
import io.github.aedev.flow.R
import kotlinx.coroutines.launch

@Composable
fun FullSizeImageDialog(
    imageUrl: String,
    onDismiss: () -> Unit,
) {
    FullSizeImageDialog(imageUrls = listOf(imageUrl), initialIndex = 0, onDismiss = onDismiss)
}

/** Full-screen images at their own shape: pinch or double-tap to zoom, swipe between several, tap to close. */
@Composable
fun FullSizeImageDialog(
    imageUrls: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    if (imageUrls.isEmpty()) return
    val pagerState =
        rememberPagerState(initialPage = initialIndex.coerceIn(imageUrls.indices)) { imageUrls.size }
    var pageZoomed by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = !pageZoomed,
                    beyondViewportPageCount = 1,
                ) { page ->
                    ZoomableImage(
                        url = imageUrls[page],
                        onTap = onDismiss,
                        onZoomedChange = { zoomed -> if (page == pagerState.currentPage) pageZoomed = zoomed },
                    )
                }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
                    }
                    if (imageUrls.size > 1) {
                        Text(
                            text = stringResource(R.string.channel_post_image_count, pagerState.currentPage + 1, imageUrls.size),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(
    url: String,
    onTap: () -> Unit,
    onZoomedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zoomSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    var size by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(MIN_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state =
        rememberTransformableState { centroid, zoomChange, panChange, _ ->
            val newScale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
            val moved = centroid - (centroid - offset) * (newScale / scale) + panChange
            scale = newScale
            offset = clampOffset(moved, newScale, size)
            onZoomedChange(newScale > MIN_SCALE)
        }

    AsyncImage(
        model =
            ImageRequest
                .Builder(context)
                .data(toHighQualityAvatarUrl(url))
                .crossfade(true)
                .size(MAX_DECODE_PX, MAX_DECODE_PX)
                .scale(Scale.FIT)
                .allowHardware(false)
                .build(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.High,
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { size = it }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { tap ->
                            val target = if (scale > MIN_SCALE) MIN_SCALE else DOUBLE_TAP_SCALE
                            scope.launch { state.animateZoomBy(target / scale, zoomSpec, tap) }
                        },
                    )
                }.transformable(state = state, canPan = { scale > MIN_SCALE })
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
    )
}

/** Keeps the zoomed image over the box it was fitted to, so it can never be dragged off screen. */
private fun clampOffset(
    offset: Offset,
    scale: Float,
    size: IntSize,
): Offset {
    val minX = size.width * (1f - scale)
    val minY = size.height * (1f - scale)
    return Offset(offset.x.coerceIn(minX, 0f), offset.y.coerceIn(minY, 0f))
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val MAX_DECODE_PX = 2048
