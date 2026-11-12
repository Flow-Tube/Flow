package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.FlowLogo
import io.github.aedev.flow.ui.components.shared.MediaPalette
import kotlin.math.sqrt

private val CardPadding = 24.dp
private val CardSpacing = 14.dp
private val HeaderSpacing = 12.dp
private val ArtworkSize = 56.dp
private val LogoWidth = 28.dp
private val LogoSpacing = 8.dp
private const val SECONDARY_INK = 0.7f
private const val DIVIDER_INK = 0.2f

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

private fun looksAlike(
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

/** The card that gets shared: the song, the chosen lines and the Flow mark, on one flat colour or the [cover] field. */
@Composable
internal fun LyricsShareCard(
    title: String,
    artist: String,
    artworkUrl: String,
    lines: List<String>,
    colors: LyricsCardColors,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
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
    Box(modifier = modifier.clip(MaterialTheme.shapes.extraLarge).background(colors.ground)) {
        if (cover != null) {
            LyricsCoverLayer(cover = cover, moving = false, running = false, modifier = Modifier.matchParentSize())
        }
        LyricsShareCardContent(title, artist, artwork, lines, colors, textAlign)
    }
}

@Composable
private fun LyricsShareCardContent(
    title: String,
    artist: String,
    artwork: ImageRequest,
    lines: List<String>,
    colors: LyricsCardColors,
    textAlign: TextAlign,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(CardPadding),
        verticalArrangement = Arrangement.spacedBy(CardSpacing),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HeaderSpacing)) {
            AsyncImage(
                model = artwork,
                contentDescription = null,
                modifier = Modifier.size(ArtworkSize).clip(MaterialTheme.shapes.small),
                contentScale = ContentScale.Crop,
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.ink.copy(alpha = SECONDARY_INK),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(color = colors.ink.copy(alpha = DIVIDER_INK))
        lines.forEach { line ->
            Text(
                text = line,
                modifier = Modifier.fillMaxWidth(),
                style = lyricsShareLineStyle(MaterialTheme.typography.headlineSmall),
                color = colors.ink,
                textAlign = textAlign,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LogoSpacing)) {
            FlowLogo(modifier = Modifier.width(LogoWidth), shapeColor = colors.ink, glyphColor = colors.ground)
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = colors.ink,
            )
        }
    }
}
