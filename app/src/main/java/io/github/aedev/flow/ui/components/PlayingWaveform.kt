package io.github.aedev.flow.ui.components

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val RestingBarFractions = listOf(0.35f, 0.9f, 0.55f, 0.75f)
private val AtRest = AnimationVector1D(0f)

// With the wait for the next vsync after it, this ticks at 24 fps on a 120 Hz screen.
private const val BAR_TICK_DELAY_MILLIS = 33L

/**
 * The "now playing" equaliser bars.
 *
 * Drawn on a single Canvas rather than a Row of Boxes with animated `height` modifiers. Height is a
 * layout property, so the previous implementations recomposed and re-laid out one node per bar on
 * every animation frame, continuously for as long as audio played — including inside list items
 * while the user scrolled. Reading the animation in the draw scope keeps each frame to a redraw.
 *
 * Each changed value still costs the whole app and compositor a frame, so the bars sample their
 * animation on a slow tick instead of at the display rate. `preferredFrameRate` is no substitute:
 * measured on a 120 Hz phone, the system received the request and the app still drew at 120.
 *
 * With [animate] false the bars sit at fixed heights and no frame work runs at all: callers pass
 * it whenever audio is paused or their layer is hidden, so a paused track does not keep every list
 * that shows it animating.
 *
 * This replaces three near-identical copies that had drifted apart in bar count, size and timing;
 * the call sites keep their own appearance through the parameters below.
 */
@Composable
fun PlayingWaveform(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    animate: Boolean = true,
    barCount: Int = 4,
    barWidth: Dp = 3.dp,
    barSpacing: Dp = 2.dp,
    minBarHeight: Dp = 6.dp,
    maxBarHeight: Dp = 16.dp,
    cycleMillis: Int = 350,
    staggerMillis: Int = 100,
) {
    val barHeights: List<() -> Float> =
        if (animate) {
            val tickNanos = remember { mutableLongStateOf(0L) }
            LaunchedEffect(Unit) {
                val start = withFrameNanos { it }
                while (true) {
                    withFrameNanos { tickNanos.longValue = it - start }
                    delay(BAR_TICK_DELAY_MILLIS)
                }
            }
            val shortest = remember(minBarHeight) { AnimationVector1D(minBarHeight.value) }
            val tallest = remember(maxBarHeight) { AnimationVector1D(maxBarHeight.value) }
            List(barCount) { index ->
                val bar =
                    remember(cycleMillis, staggerMillis, index) {
                        infiniteRepeatable<Float>(
                            animation =
                                tween(
                                    durationMillis = cycleMillis,
                                    delayMillis = index * staggerMillis,
                                    easing = FastOutSlowInEasing,
                                ),
                            repeatMode = RepeatMode.Reverse,
                        ).vectorize(Float.VectorConverter)
                    }
                ({ bar.getValueFromNanos(tickNanos.longValue, shortest, tallest, AtRest).value })
            }
        } else {
            List(barCount) { index ->
                val fraction = RestingBarFractions[index % RestingBarFractions.size]
                val height = minBarHeight.value + (maxBarHeight.value - minBarHeight.value) * fraction
                ({ height })
            }
        }

    Canvas(
        modifier =
            modifier.size(
                width = barWidth * barCount + barSpacing * (barCount - 1),
                height = maxBarHeight,
            ),
    ) {
        val barWidthPx = barWidth.toPx()
        val spacingPx = barSpacing.toPx()
        // Matches the RoundedCornerShape(barWidth / 2) the Box versions used.
        val cornerRadius = CornerRadius(barWidthPx / 2f)
        barHeights.forEachIndexed { index, barHeight ->
            val barHeightPx = barHeight().dp.toPx()
            drawRoundRect(
                color = color,
                topLeft =
                    Offset(
                        x = index * (barWidthPx + spacingPx),
                        y = (size.height - barHeightPx) / 2f,
                    ),
                size = Size(barWidthPx, barHeightPx),
                cornerRadius = cornerRadius,
            )
        }
    }
}
