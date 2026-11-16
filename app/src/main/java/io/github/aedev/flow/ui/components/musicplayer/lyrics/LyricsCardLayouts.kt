package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.FlowLogo

private val CardPadding = 24.dp
private val CardSpacing = 14.dp
private val HeaderSpacing = 12.dp
private val ArtworkSize = 56.dp
private val FooterArtworkSize = 40.dp
private val ShapeArtworkSize = 72.dp
private val QuoteSize = 56.dp
private val DecorSize = 220.dp
private val DecorOverhang = 64.dp
private val LogoWidth = 28.dp
private val LogoSpacing = 8.dp
private const val SECONDARY_INK = 0.7f
private const val DIVIDER_INK = 0.2f
private const val QUOTE_INK = 0.55f
private const val DECOR_INK = 0.14f
private const val DECOR_TURN = 18f

/** The ways the card can be drawn. */
internal enum class LyricsCardLayout {
    CLASSIC,
    POSTER,
    SHAPE,
}

/** What every layout shows: the song, its cover and the chosen lines, in the card's colours. */
@Immutable
internal class LyricsCardContent(
    val title: String,
    val artist: String,
    val artwork: ImageRequest,
    val lines: List<String>,
    val colors: LyricsCardColors,
    val textAlign: TextAlign,
)

/** The song row on top, a rule, the lines, and the Flow mark. */
@Composable
internal fun ClassicCard(
    content: LyricsCardContent,
    fill: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().fillHeight(fill).padding(CardPadding),
        verticalArrangement = Arrangement.spacedBy(CardSpacing),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HeaderSpacing)) {
            CardArtwork(content.artwork, ArtworkSize, MaterialTheme.shapes.small)
            SongNames(content, Modifier, TextAlign.Start)
        }
        HorizontalDivider(color = content.colors.ink.copy(alpha = DIVIDER_INK))
        CardLines(content, MaterialTheme.typography.headlineSmall, fill)
        FlowMark(content.colors)
    }
}

/** A large quote mark over the lines, with the song and the Flow mark along the bottom. */
@Composable
internal fun PosterCard(
    content: LyricsCardContent,
    fill: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().fillHeight(fill).padding(CardPadding),
        verticalArrangement = Arrangement.spacedBy(CardSpacing),
    ) {
        Icon(
            imageVector = Icons.Filled.FormatQuote,
            contentDescription = null,
            modifier = Modifier.size(QuoteSize),
            tint = content.colors.ink.copy(alpha = QUOTE_INK),
        )
        CardLines(content, MaterialTheme.typography.headlineMedium, fill)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HeaderSpacing)) {
            CardArtwork(content.artwork, FooterArtworkSize, MaterialTheme.shapes.small)
            SongNames(content, Modifier.weight(1f), TextAlign.Start)
            FlowLogo(modifier = Modifier.width(LogoWidth), shapeColor = content.colors.ink, glyphColor = content.colors.ground)
        }
    }
}

/** An Expressive shape in the card's ink behind a centred cover, the song and the lines. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ShapeCard(
    content: LyricsCardContent,
    fill: Boolean,
) {
    Box(modifier = Modifier.fillMaxWidth().fillHeight(fill)) {
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = DecorOverhang, y = -DecorOverhang)
                    .size(DecorSize)
                    .rotate(DECOR_TURN)
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(content.colors.ink.copy(alpha = DECOR_INK)),
        )
        Column(
            modifier = Modifier.fillMaxWidth().fillHeight(fill).padding(CardPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CardSpacing),
        ) {
            CardArtwork(content.artwork, ShapeArtworkSize, MaterialShapes.Clover4Leaf.toShape())
            SongNames(content, Modifier.fillMaxWidth(), TextAlign.Center)
            CardLines(content, MaterialTheme.typography.headlineSmall, fill)
            FlowMark(content.colors)
        }
    }
}

@Composable
private fun ColumnScope.CardLines(
    content: LyricsCardContent,
    base: TextStyle,
    fill: Boolean,
) {
    val style = lyricsShareLineStyle(base).copy(lineBreak = LineBreak.Heading)
    if (!fill) {
        LineStack(content, style, Modifier.fillMaxWidth())
        return
    }
    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        val measurer = rememberTextMeasurer()
        val spacing = with(LocalDensity.current) { CardSpacing.roundToPx() }
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val scale =
            remember(content.lines, style, width, height, spacing) {
                largestFittingScale(MIN_LINE_SCALE, MAX_LINE_SCALE) { candidate ->
                    val scaled = style.scaledBy(candidate)
                    val heights =
                        content.lines.map { measurer.measure(it, scaled, constraints = Constraints(maxWidth = width)).size.height }
                    stackedHeight(heights, spacing) <= height
                }
            }
        LineStack(content, style.scaledBy(scale), Modifier.fillMaxWidth())
    }
}

@Composable
private fun LineStack(
    content: LyricsCardContent,
    style: TextStyle,
    modifier: Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(CardSpacing)) {
        content.lines.forEach { line ->
            Text(
                text = line,
                modifier = Modifier.fillMaxWidth(),
                style = style,
                color = content.colors.ink,
                textAlign = content.textAlign,
            )
        }
    }
}

@Composable
private fun CardArtwork(
    artwork: ImageRequest,
    size: Dp,
    shape: Shape,
) {
    AsyncImage(
        model = artwork,
        contentDescription = null,
        modifier = Modifier.size(size).clip(shape),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun SongNames(
    content: LyricsCardContent,
    modifier: Modifier,
    textAlign: TextAlign,
) {
    Column(modifier = modifier) {
        Text(
            text = content.title,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = content.colors.ink,
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = content.artist,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = content.colors.ink.copy(alpha = SECONDARY_INK),
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FlowMark(colors: LyricsCardColors) {
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

private fun Modifier.fillHeight(fill: Boolean): Modifier = if (fill) fillMaxHeight() else this
