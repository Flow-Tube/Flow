package io.github.aedev.flow.ui.components.shared

import android.graphics.Bitmap
import androidx.collection.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Artwork-derived colours, shared by the music players and the video description sheet. */
@Immutable
data class MediaPalette(
    val base: Color,
    val accent: Color,
    /** Readable ink over [base] (white on dark swatches, near-black on light). */
    val onBase: Color,
)

internal val PaletteInkDark = Color(0xFF161616)

/**
 * [animated] eases the swatches in over a second, which is right for the player and wrong for a
 * page that re-derives a whole colour scheme from them: a scheme change recomposes everything
 * under the theme, so pages take the settled colours in one step instead. [cacheOnly] reads the
 * image only if it is already cached, so a tint never downloads a picture the viewer chose not to load.
 */
@Composable
fun rememberMediaPalette(
    thumbnailUrl: String?,
    animated: Boolean = true,
    cacheOnly: Boolean = false,
): MediaPalette {
    val context = LocalContext.current
    var baseSwatch by remember { mutableStateOf<Color?>(null) }
    var accentSwatch by remember { mutableStateOf<Color?>(null) }

    LaunchedEffect(thumbnailUrl, cacheOnly) {
        if (thumbnailUrl.isNullOrEmpty()) return@LaunchedEffect
        val request =
            ImageRequest
                .Builder(context)
                .data(thumbnailUrl)
                .allowHardware(false)
                .size(128)
                .apply { if (cacheOnly) networkCachePolicy(CachePolicy.DISABLED) }
                .build()
        val result = SingletonImageLoader.get(context).execute(request)
        if (result is SuccessResult) {
            val (base, accent) = withContext(Dispatchers.Default) { artworkSwatches(result.image.toBitmap()) }
            baseSwatch = base
            accentSwatch = accent
        } else {
            baseSwatch = null
            accentSwatch = null
        }
    }

    val baseTarget = baseSwatch ?: MaterialTheme.colorScheme.surface
    val accentTarget = accentSwatch ?: MaterialTheme.colorScheme.primary
    val base =
        if (animated) {
            animateColorAsState(
                targetValue = baseTarget,
                animationSpec = tween(1000),
                label = "mediaPaletteBase",
            ).value
        } else {
            baseTarget
        }
    val accent =
        if (animated) {
            animateColorAsState(
                targetValue = accentTarget,
                animationSpec = tween(1000),
                label = "mediaPaletteAccent",
            ).value
        } else {
            accentTarget
        }
    val onBase = remember(base) { onBaseFor(base) }
    return MediaPalette(base = base, accent = accent, onBase = onBase)
}

/**
 * The cover's vivid swatch (its dominant one when it has none), or null until it is known. It reads
 * [thumbnailUrl] from the image cache only, so pass it once the cover itself has loaded; the swatch is
 * kept per URL for the process, so a card scrolled back into view does not run the palette again.
 */
@Composable
fun rememberArtworkAccent(thumbnailUrl: String?): Color? {
    val context = LocalContext.current
    var accent by remember(thumbnailUrl) { mutableStateOf(thumbnailUrl?.let { artworkAccents[it] }) }
    LaunchedEffect(thumbnailUrl) {
        if (thumbnailUrl.isNullOrEmpty() || accent != null) return@LaunchedEffect
        val request =
            ImageRequest
                .Builder(context)
                .data(thumbnailUrl)
                .allowHardware(false)
                .size(ACCENT_SAMPLE_PX)
                .networkCachePolicy(CachePolicy.DISABLED)
                .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@LaunchedEffect
        val swatch =
            withContext(Dispatchers.Default) {
                val (base, vivid) = artworkSwatches(result.image.toBitmap())
                vivid ?: base
            } ?: return@LaunchedEffect
        artworkAccents.put(thumbnailUrl, swatch)
        accent = swatch
    }
    return accent
}

private const val ACCENT_SAMPLE_PX = 64
private val artworkAccents = LruCache<String, Color>(256)

/** The palette of a decoded cover, off the composition, for surfaces with no composition of their own. */
fun mediaPaletteOf(
    bitmap: Bitmap,
    fallbackBase: Color,
    fallbackAccent: Color,
): MediaPalette {
    val (base, accent) = artworkSwatches(bitmap)
    val resolvedBase = base ?: fallbackBase
    return MediaPalette(base = resolvedBase, accent = accent ?: fallbackAccent, onBase = onBaseFor(resolvedBase))
}

/** A dark swatch to sit under the content and a vivid one to draw with, either possibly missing. */
private fun artworkSwatches(bitmap: Bitmap): Pair<Color?, Color?> {
    val palette = Palette.from(bitmap).generate()
    val base = palette.darkMutedSwatch ?: palette.darkVibrantSwatch ?: palette.dominantSwatch
    val accent = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.lightMutedSwatch
    return base?.let { Color(it.rgb) } to accent?.let { Color(it.rgb) }
}

private fun onBaseFor(base: Color): Color = if (base.luminance() < 0.45f) Color.White else PaletteInkDark
