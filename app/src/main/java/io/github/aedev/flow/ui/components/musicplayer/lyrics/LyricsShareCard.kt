package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import coil3.request.ImageRequest
import coil3.request.allowHardware
import io.github.aedev.flow.ui.components.shared.MediaPalette
import kotlin.math.sqrt

// About 15 of 255 across red, green and blue together: closer than that, two swatches read as one.
private const val SAME_GROUND_DISTANCE = 0.06f

internal enum class LyricsCardStyle {
    ARTWORK,
    COLORFUL,
    SURFACE,
    INVERSE,
    PRIMARY,
    PRIMARY_CONTAINER,
    SECONDARY_CONTAINER,
    TERTIARY,
    TERTIARY_CONTAINER,
}

@Immutable
internal data class LyricsCardColors(
    val ground: Color,
    val ink: Color,
)

@Composable
internal fun lyricsCardColors(
    style: LyricsCardStyle,
    artwork: MediaPalette,
    cover: LyricsCover?,
): LyricsCardColors {
    val scheme = MaterialTheme.colorScheme
    return when (style) {
        LyricsCardStyle.ARTWORK -> LyricsCardColors(artwork.base, artwork.onBase)

        // The field is darkened until white keeps 7:1 on its brightest cell, as on the lyrics screen.
        LyricsCardStyle.COLORFUL -> LyricsCardColors(cover?.backdrop ?: artwork.base, Color.White)

        LyricsCardStyle.SURFACE -> LyricsCardColors(scheme.surfaceContainerHighest, scheme.onSurface)

        LyricsCardStyle.INVERSE -> LyricsCardColors(scheme.inverseSurface, scheme.inverseOnSurface)

        LyricsCardStyle.PRIMARY -> LyricsCardColors(scheme.primary, scheme.onPrimary)

        LyricsCardStyle.PRIMARY_CONTAINER -> LyricsCardColors(scheme.primaryContainer, scheme.onPrimaryContainer)

        LyricsCardStyle.SECONDARY_CONTAINER -> LyricsCardColors(scheme.secondaryContainer, scheme.onSecondaryContainer)

        LyricsCardStyle.TERTIARY -> LyricsCardColors(scheme.tertiary, scheme.onTertiary)

        LyricsCardStyle.TERTIARY_CONTAINER -> LyricsCardColors(scheme.tertiaryContainer, scheme.onTertiaryContainer)
    }
}

/**
 * The styles worth a swatch, in order. The theme roles follow the cover too, so several can land on
 * what reads as one colour; only the first of each is offered. COLORFUL needs a cover to show.
 */
internal fun distinctCardStyles(
    hasCover: Boolean,
    groundOf: (LyricsCardStyle) -> Color,
): List<LyricsCardStyle> {
    val kept = mutableListOf<LyricsCardStyle>()
    LyricsCardStyle.entries.forEach { style ->
        val shown =
            if (style == LyricsCardStyle.COLORFUL) {
                hasCover
            } else {
                kept.none { it != LyricsCardStyle.COLORFUL && looksAlike(groundOf(it), groundOf(style)) }
            }
        if (shown) kept += style
    }
    return kept
}

internal fun looksAlike(
    a: Color,
    b: Color,
): Boolean {
    val red = a.red - b.red
    val green = a.green - b.green
    val blue = a.blue - b.blue
    return sqrt(red * red + green * green + blue * blue) < SAME_GROUND_DISTANCE
}

/** [base] set the way the lyrics screen sets a line: the app font, bold, untracked and unpadded. */
@Composable
internal fun lyricsShareLineStyle(base: TextStyle): TextStyle =
    base.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp,
        fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

/** The card that gets shared, drawn in [layout] on one flat colour or the [cover] field. [fill] gives it a fixed height to fill. */
@Composable
internal fun LyricsShareCard(
    title: String,
    artist: String,
    artworkUrl: String,
    lines: List<String>,
    colors: LyricsCardColors,
    textAlign: TextAlign,
    shape: Shape,
    modifier: Modifier = Modifier,
    layout: LyricsCardLayout = LyricsCardLayout.CLASSIC,
    fill: Boolean = false,
    cover: LyricsCover? = null,
) {
    val context = LocalContext.current
    // The card is also drawn into a bitmap, which a hardware-backed cover cannot always be.
    val artwork =
        remember(artworkUrl) {
            ImageRequest
                .Builder(context)
                .data(artworkUrl)
                .allowHardware(false)
                .build()
        }
    Box(modifier = modifier.clip(shape).background(colors.ground)) {
        if (cover != null) {
            LyricsCoverLayer(cover = cover, moving = false, running = false, modifier = Modifier.matchParentSize())
        }
        val content = LyricsCardContent(title, artist, artwork, lines, colors, textAlign)
        when (layout) {
            LyricsCardLayout.CLASSIC -> ClassicCard(content, fill)
            LyricsCardLayout.POSTER -> PosterCard(content, fill)
            LyricsCardLayout.SHAPE -> ShapeCard(content, fill)
        }
    }
}
