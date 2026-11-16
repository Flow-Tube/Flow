@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.aedev.flow.ui.components.musicplayer.mini

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.local.MiniBarProgress
import kotlinx.coroutines.delay

private const val FILL_ALPHA = 0.22f
private const val TRACK_ALPHA = 0.12f
private val EdgeStroke = 3.dp

private val LineStroke = 3.dp
private val WavyLineHeight = 8.dp

/** M3's wavy indicator snaps its phase to 0 when its speed does, so the speed waits until the wave is flat. */
private const val WAVE_FLATTEN_MILLIS = 500L

/**
 * The part of a mini bar's progress drawn behind its content: a tonal [MiniBarProgress.FILL] that
 * grows from the start edge, or an [MiniBarProgress.EDGE] line that runs round the pill from its top
 * centre. Both read [progress] in draw, so a step repaints the bar without recomposing it.
 */
internal fun Modifier.miniBarProgress(
    style: MiniBarProgress,
    progress: () -> Float,
    color: Color,
    trackColor: Color,
): Modifier =
    when (style) {
        MiniBarProgress.FILL -> {
            drawWithCache {
                val fill = color.copy(alpha = FILL_ALPHA)
                onDrawBehind {
                    val width = size.width * progress().coerceIn(0f, 1f)
                    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
                    drawRect(fill, topLeft = Offset(left, 0f), size = size.copy(width = width))
                }
            }
        }

        MiniBarProgress.EDGE -> {
            drawWithCache {
                val strokePx = EdgeStroke.toPx()
                val outline = pillOutline(size.width, size.height, strokePx / 2f)
                val measure = PathMeasure().apply { setPath(outline, false) }
                val played = Path()
                val stroke = Stroke(width = strokePx, cap = StrokeCap.Round)
                val track = trackColor.copy(alpha = TRACK_ALPHA)
                onDrawBehind {
                    // Clockwise from the top centre, or anticlockwise where text runs right to left.
                    scale(if (layoutDirection == LayoutDirection.Rtl) -1f else 1f, 1f) {
                        drawPath(outline, track, style = stroke)
                        played.rewind()
                        measure.getSegment(0f, measure.length * progress().coerceIn(0f, 1f), played, true)
                        drawPath(played, color, style = stroke)
                    }
                }
            }
        }

        else -> {
            this
        }
    }

/** A pill's outline [inset] inside a [width] by [height] box, starting at the top centre and running clockwise. */
private fun pillOutline(
    width: Float,
    height: Float,
    inset: Float,
): Path {
    val radius = height / 2f - inset
    return Path().apply {
        moveTo(width / 2f, inset)
        lineTo(width - height / 2f, inset)
        arcTo(Rect(Offset(width - height / 2f, height / 2f), radius), -90f, 180f, false)
        lineTo(height / 2f, height - inset)
        arcTo(Rect(Offset(height / 2f, height / 2f), radius), 90f, 180f, false)
        close()
    }
}

/**
 * The line styles, under the song's title and artist: Material's linear indicator, or its wavy one,
 * which moves only while music plays and the bar is shown.
 */
@Composable
internal fun MiniBarProgressLine(
    style: MiniBarProgress,
    progress: () -> Float,
    isPlaying: Boolean,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = TRACK_ALPHA)
    val line = modifier.fillMaxWidth().clearAndSetSemantics {}
    when (style) {
        MiniBarProgress.LINE -> {
            LinearProgressIndicator(
                progress = progress,
                modifier = line.height(LineStroke),
                color = color,
                trackColor = trackColor,
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
            )
        }

        MiniBarProgress.WAVY_LINE -> {
            val waving = isPlaying && animationsEnabled
            var moving by remember { mutableStateOf(waving) }
            LaunchedEffect(waving) {
                if (!waving) delay(WAVE_FLATTEN_MILLIS)
                moving = waving
            }
            val stroke = with(LocalDensity.current) { Stroke(width = LineStroke.toPx(), cap = StrokeCap.Round) }
            LinearWavyProgressIndicator(
                progress = progress,
                modifier = line.height(WavyLineHeight),
                color = color,
                trackColor = trackColor,
                stroke = stroke,
                trackStroke = stroke,
                amplitude = { if (waving) 1f else 0f },
                waveSpeed = if (moving) WavyProgressIndicatorDefaults.LinearDeterminateWavelength else 0.dp,
                stopSize = 0.dp,
            )
        }

        else -> {
            Unit
        }
    }
}

/** Whether [style] draws its line over the bar's content rather than behind it. */
internal fun MiniBarProgress.isLine(): Boolean = this == MiniBarProgress.LINE || this == MiniBarProgress.WAVY_LINE
