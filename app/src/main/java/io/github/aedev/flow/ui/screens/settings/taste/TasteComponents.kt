package io.github.aedev.flow.ui.screens.settings.taste

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.recommendation.FlowPersona
import io.github.aedev.flow.ui.components.settings.dpadFocusStop
import io.github.aedev.flow.ui.components.shared.flowArtistShape
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val HeroPadding = 20.dp
private val HeroSpacing = 16.dp
private val HeroEmblemSize = 72.dp
private val RadarMaxWidth = 280.dp
private val RadarStroke = 2.dp
private val RadarGridStroke = 1.dp
private val LegendDot = 10.dp
private val MeterSpacing = 6.dp
private const val RADAR_RINGS = 4
private const val PROFILE_FILL_ALPHA = 0.28f
private const val START_ANGLE = -PI / 2

/** The persona's glyph in the artist cookie shape, on a tonal container. */
@Composable
internal fun PersonaEmblem(
    persona: FlowPersona,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = flowArtistShape(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = persona.icon, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
internal fun PersonaHeroCard(
    persona: FlowPersona,
    maturity: Float,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().dpadFocusStop(MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(HeroPadding),
            verticalArrangement = Arrangement.spacedBy(HeroSpacing),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(HeroSpacing), verticalAlignment = Alignment.CenterVertically) {
                PersonaEmblem(persona, Modifier.size(HeroEmblemSize))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(persona.titleRes),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(persona.descriptionRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Meter(label = stringResource(R.string.profile_maturity_label), value = maturity)
        }
    }
}

@Composable
private fun Meter(
    label: String,
    value: Float,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MeterSpacing)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(value.percentLabel(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(progress = { value }, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Five traits drawn twice: the whole profile as a filled shape and the current time of day as an
 * outline. Paths are built once per size and data; nothing here animates.
 */
@Composable
internal fun TasteShapeCard(
    profile: TasteTraits,
    now: TasteTraits,
) {
    val description = stringResource(R.string.taste_shape_description)
    Surface(
        modifier = Modifier.fillMaxWidth().dpadFocusStop(MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(HeroPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(HeroSpacing),
        ) {
            TasteRadar(
                profile = profile,
                now = now,
                modifier =
                    Modifier
                        .widthIn(max = RadarMaxWidth)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clearAndSetSemantics { contentDescription = description },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(HeroSpacing)) {
                LegendItem(stringResource(R.string.taste_chart_profile), MaterialTheme.colorScheme.primary)
                LegendItem(stringResource(R.string.taste_chart_now), MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LegendItem(
    label: String,
    color: Color,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(MeterSpacing), verticalAlignment = Alignment.CenterVertically) {
        Surface(modifier = Modifier.size(LegendDot), shape = CircleShape, color = color) {}
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TasteRadar(
    profile: TasteTraits,
    now: TasteTraits,
    modifier: Modifier = Modifier,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val fill = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier =
            modifier.drawWithCache {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.minDimension / 2f * RADAR_FILL
                val axes = profile.values.size
                val rings = (1..RADAR_RINGS).map { ring -> polygon(center, radius * ring / RADAR_RINGS, List(axes) { 1f }) }
                val spokes = List(axes) { index -> center to center + direction(index, axes) * radius }
                val profilePath = polygon(center, radius, profile.values)
                val nowPath = polygon(center, radius, now.values)
                val gridStroke = Stroke(width = RadarGridStroke.toPx())
                val lineStroke = Stroke(width = RadarStroke.toPx())
                onDrawBehind {
                    rings.forEach { drawPath(it, grid, style = gridStroke) }
                    spokes.forEach { (from, to) -> drawLine(grid, from, to, strokeWidth = gridStroke.width) }
                    drawPath(profilePath, fill.copy(alpha = PROFILE_FILL_ALPHA))
                    drawPath(profilePath, fill, style = lineStroke)
                    drawPath(nowPath, outline, style = lineStroke)
                }
            },
    )
}

private const val RADAR_FILL = 0.92f

private fun direction(
    index: Int,
    count: Int,
): Offset {
    val angle = START_ANGLE + 2 * PI * index / count
    return Offset(cos(angle).toFloat(), sin(angle).toFloat())
}

private fun polygon(
    center: Offset,
    radius: Float,
    values: List<Float>,
): Path =
    Path().apply {
        values.forEachIndexed { index, value ->
            val point = center + direction(index, values.size) * (radius * value.coerceIn(0f, 1f))
            if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
        }
        close()
    }

/** A settings row whose supporting line is a meter, for a weight or a trait. On a remote, OK runs [onDpadClick]. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TasteMeterRow(
    title: String,
    value: Float?,
    shape: Shape,
    detail: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onDpadClick: (() -> Unit)? = null,
) {
    SegmentedListItem(
        modifier = Modifier.dpadFocusStop(shape, onDpadClick),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(MeterSpacing), modifier = Modifier.padding(top = MeterSpacing)) {
                value?.let { LinearProgressIndicator(progress = { it.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
                detail?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        },
        trailingContent = trailing,
    ) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A quiet line of text in place of a group that has nothing to list yet. */
@Composable
internal fun TasteNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = MeterSpacing),
    )
}

internal fun Float.percentLabel(): String = "${(coerceIn(0f, 1f) * PERCENT).roundToInt()}%"

/** Topic ids are lowercase tokens; this is how the list shows them. */
internal fun String.readableTopic(): String =
    replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .replaceFirstChar { it.titlecase() }

private const val PERCENT = 100
