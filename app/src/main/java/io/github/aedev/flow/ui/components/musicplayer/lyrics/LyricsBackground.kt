package io.github.aedev.flow.ui.components.musicplayer.lyrics

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.scale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val COVER_CELLS = 8
private const val MESH_SIZE = 64
private const val COVER_SATURATION = 1.35f
private const val COVER_SCALE = 1.4f
private const val FRAME_MS = 41L
private const val MILLIS_PER_SECOND = 1000f
private const val TWO_PI = (2 * PI).toFloat()
private const val DRIFT_SCALE = 2.2f
private const val DRIFT_REACH = 0.42f
private const val SECOND_LAYER_ALPHA = 0.6f
private const val FIRST_PERIOD_X = 16f
private const val FIRST_PERIOD_Y = 22f
private const val SECOND_PERIOD_X = 24f
private const val SECOND_PERIOD_Y = 28f

/** Every period divides this, so wrapping the clock never makes a layer jump. */
private const val DRIFT_CYCLE_SECONDS = 1848f

/** The cover as a soft color field, and the color lyric text is measured and blended against. */
@Immutable
internal class LyricsCover(
    val image: ImageBitmap,
    val backdrop: Color,
)

/** Nothing is loaded until [enabled], so a retained sheet does no work for a track nobody is looking at. */
@Composable
internal fun rememberLyricsCover(
    artworkUrl: String,
    enabled: Boolean,
): LyricsCover? {
    val context = LocalContext.current
    val cover by produceState<LyricsCover?>(null, artworkUrl, enabled) {
        if (!enabled || artworkUrl.isBlank()) return@produceState
        val request =
            ImageRequest
                .Builder(context)
                .data(artworkUrl)
                .allowHardware(false)
                .size(COVER_CELLS)
                .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@produceState
        value = withContext(Dispatchers.Default) { lyricsCover(result.image.toBitmap()) }
    }
    return cover
}

private fun lyricsCover(source: Bitmap): LyricsCover {
    val pixels = IntArray(COVER_CELLS * COVER_CELLS)
    source.scale(COVER_CELLS, COVER_CELLS).getPixels(pixels, 0, COVER_CELLS, 0, 0, COVER_CELLS, COVER_CELLS)
    val colors = lyricsCoverColors(pixels)
    val mesh = lyricsCoverMesh(colors.cells, COVER_CELLS, MESH_SIZE)
    val image = Bitmap.createBitmap(mesh, MESH_SIZE, MESH_SIZE, Bitmap.Config.ARGB_8888).asImageBitmap()
    return LyricsCover(image, colors.brightest)
}

/**
 * [moving] lays a second, flipped copy of the field over the first and sends the two on different
 * paths, so the colors flow through each other. [running] stops it with the sheet, the song or the
 * screen; it only ever moves these layers, at a capped rate, and redraws no text.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LyricsCoverLayer(
    cover: LyricsCover?,
    moving: Boolean,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val elapsedSeconds = remember { mutableFloatStateOf(0f) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val animate = moving && running && rememberLyricsMotionEnabled()
    LaunchedEffect(animate, lifecycleOwner) {
        if (!animate) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = withFrameMillis { it }
            while (isActive) {
                delay(FRAME_MS)
                val now = withFrameMillis { it }
                elapsedSeconds.floatValue = (elapsedSeconds.floatValue + (now - last) / MILLIS_PER_SECOND) % DRIFT_CYCLE_SECONDS
                last = now
            }
        }
    }
    val saturation = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(COVER_SATURATION) }) }
    Crossfade(
        targetState = cover,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "lyricsCover",
        modifier = modifier.fillMaxSize().clipToBounds(),
    ) { shown ->
        if (shown == null) return@Crossfade
        if (!moving) {
            Image(
                bitmap = shown.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = saturation,
                modifier =
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = COVER_SCALE
                        scaleY = COVER_SCALE
                    },
            )
            return@Crossfade
        }
        Image(
            bitmap = shown.image,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            colorFilter = saturation,
            modifier = Modifier.fillMaxSize().drifting({ elapsedSeconds.floatValue }, FIRST_PERIOD_X, FIRST_PERIOD_Y, DRIFT_SCALE),
        )
        Image(
            bitmap = shown.image,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            alpha = SECOND_LAYER_ALPHA,
            colorFilter = saturation,
            modifier = Modifier.fillMaxSize().drifting({ elapsedSeconds.floatValue }, SECOND_PERIOD_X, SECOND_PERIOD_Y, -DRIFT_SCALE),
        )
    }
}

/** Keeps the layer past the screen's edges at every point of its path, so no edge of the field ever shows. */
private fun Modifier.drifting(
    elapsedSeconds: () -> Float,
    periodX: Float,
    periodY: Float,
    scale: Float,
): Modifier =
    graphicsLayer {
        val seconds = elapsedSeconds()
        scaleX = scale
        scaleY = scale
        translationX = sin(TWO_PI * seconds / periodX) * size.width * DRIFT_REACH
        translationY = cos(TWO_PI * seconds / periodY) * size.height * DRIFT_REACH
    }
