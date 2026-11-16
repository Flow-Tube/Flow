package io.github.aedev.flow.ui.screens.settings.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.CrossfadeCurve
import io.github.aedev.flow.player.crossfade.CrossfadePolicy

private val ChartHeight = 112.dp
private val LineWidth = 3.dp
private val AxisWidth = 1.dp
private val LegendSampleWidth = 20.dp
private val LegendSampleHeight = 8.dp
private val Dash = 6.dp
private val DashGap = 5.dp
private val LegendGap = 6.dp
private val LegendSpacing = 16.dp
private val LegendTopPadding = 8.dp
private const val SAMPLES = 48

/**
 * Both songs' volume through a fade on [curve], drawn from the same gains the player uses, so the chart
 * can't disagree with what is heard. Material 3 has no chart component, so this is a plain Canvas. The
 * next song is dashed, since a monochrome palette gives the two theme colours almost the same tone.
 */
@Composable
internal fun CrossfadeCurveChart(
    curve: CrossfadeCurve,
    modifier: Modifier = Modifier,
) {
    val outgoing = MaterialTheme.colorScheme.primary
    val incoming = MaterialTheme.colorScheme.tertiary
    val axis = MaterialTheme.colorScheme.outlineVariant
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(ChartHeight),
        ) {
            val axisStroke = AxisWidth.toPx()
            drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), axisStroke)
            drawLine(axis, Offset(0f, 0f), Offset(0f, size.height), axisStroke)
            drawGain(outgoing, dashed = false) { CrossfadePolicy.fadeOutGain(it, curve) }
            drawGain(incoming, dashed = true) { CrossfadePolicy.fadeInGain(it, curve) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = LegendTopPadding),
            horizontalArrangement = Arrangement.spacedBy(LegendSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendItem(outgoing, dashed = false, stringResource(R.string.crossfade_chart_outgoing))
            LegendItem(incoming, dashed = true, stringResource(R.string.crossfade_chart_incoming))
        }
    }
}

private fun DrawScope.lineStroke(dashed: Boolean): Stroke =
    Stroke(
        width = LineWidth.toPx(),
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(Dash.toPx(), DashGap.toPx())) else null,
    )

private fun DrawScope.drawGain(
    color: Color,
    dashed: Boolean,
    gain: (Float) -> Float,
) {
    val inset = LineWidth.toPx() / 2
    val height = size.height - 2 * inset
    val path = Path()
    for (step in 0..SAMPLES) {
        val progress = step.toFloat() / SAMPLES
        val x = progress * size.width
        val y = inset + (1f - gain(progress)) * height
        if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = lineStroke(dashed))
}

@Composable
private fun LegendItem(
    color: Color,
    dashed: Boolean,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(LegendSampleWidth, LegendSampleHeight)) {
            val y = size.height / 2
            val path =
                Path().apply {
                    moveTo(0f, y)
                    lineTo(size.width, y)
                }
            drawPath(path, color, style = lineStroke(dashed))
        }
        Spacer(Modifier.width(LegendGap))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
