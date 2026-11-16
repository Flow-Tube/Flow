// ============================================================================
// THIS IMPLEMENTATION WAS INSPIRED BY METROLIST
// ============================================================================

package io.github.aedev.flow.ui.components.musicplayer.controls

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.local.SliderStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin

// ============================================================================
// EXPRESSIVE SLIDER (M3 handle thumb + gapped track + stop indicator)
// ============================================================================

@Immutable
data class ExpressiveSliderSpec(
    val trackHeight: Dp,
    val thumbHeight: Dp,
    val thumbTrackGap: Dp,
)

/** Per-style anatomy for the M3 expressive slider; SQUIGGLY and EXPRESSIVE_WAVY have their own sliders. */
fun expressiveSliderSpec(style: SliderStyle): ExpressiveSliderSpec =
    when (style) {
        SliderStyle.THICK -> {
            ExpressiveSliderSpec(trackHeight = 26.dp, thumbHeight = 44.dp, thumbTrackGap = 6.dp)
        }

        SliderStyle.COMPACT -> {
            ExpressiveSliderSpec(trackHeight = 8.dp, thumbHeight = 26.dp, thumbTrackGap = 4.dp)
        }

        SliderStyle.SLIM -> {
            ExpressiveSliderSpec(trackHeight = 4.dp, thumbHeight = 0.dp, thumbTrackGap = 0.dp)
        }

        SliderStyle.DEFAULT, SliderStyle.SQUIGGLY, SliderStyle.EXPRESSIVE_WAVY -> {
            ExpressiveSliderSpec(trackHeight = 16.dp, thumbHeight = 36.dp, thumbTrackGap = 6.dp)
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressivePlayerSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    trackHeight: Dp,
    thumbHeight: Dp,
    thumbTrackGap: Dp,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val colors =
        SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
        )
    val sliderState = remember(valueRange) { SliderState(value, trackRange = valueRange) }
    sliderState.value = value
    Slider(
        state = sliderState,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        thumb = {
            if (thumbHeight > 0.dp) {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    colors = colors,
                    thumbSize = DpSize(width = 4.dp, height = thumbHeight),
                )
            }
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                colors = colors,
                thumbTrackGapSize = thumbTrackGap,
                modifier = Modifier.height(trackHeight),
            )
        },
        modifier = modifier.fillMaxWidth(),
    )
}

// ============================================================================
// WAVY SLIDERS (M3 Slider with a wave drawn as its track)
// ============================================================================

private val WavyStrokeWidth = 5.dp
private val WaveAmplitude = 3.dp
private val SquigglyWavelength = 32.dp
private val SquigglyWaveSpeed = 8.dp
private val SquigglyThumbSize = DpSize(width = 5.dp, height = 20.dp)
private const val SQUIGGLY_INACTIVE_TRACK_ALPHA = 0.3f
private const val EXPRESSIVE_INACTIVE_TRACK_ALPHA = 0.24f
private val ExpressiveThumbSize = 14.dp
private val ExpressiveThumbPressedSize = 20.dp
private val ExpressiveThumbGap = 4.dp
private val StopDotSize = 4.dp

/** Points along the wave per pixel of track; enough for a smooth curve at any density. */
private const val WAVE_STEP_PX = 2f

/**
 * Where a wave stands: its phase moves only while music plays and nobody drags the thumb, and its
 * height eases to flat when music stops. Once paused and flat nothing is left to draw, so the
 * frame loop is gone too. M3's LinearWavyProgressIndicator can't do this: its wave keeps moving
 * whatever the amplitude, and stopping its speed snaps the phase back, so a frozen wave would jump.
 */
private class WaveMotion {
    val phase = mutableFloatStateOf(0f)
}

@Composable
private fun rememberWaveMotion(
    isPlaying: Boolean,
    isMoving: Boolean,
    wavelength: Dp,
    speed: Dp,
): Pair<WaveMotion, () -> Float> {
    val motion = remember { WaveMotion() }
    val amplitude by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.slowEffectsSpec(),
        label = "waveAmplitude",
    )
    val cyclesPerSecond = speed / wavelength
    LaunchedEffect(isPlaying, isMoving, cyclesPerSecond) {
        if (!isPlaying || !isMoving) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { now ->
                motion.phase.floatValue = (motion.phase.floatValue + (now - last) / 1_000_000_000f * cyclesPerSecond) % 1f
                last = now
            }
        }
    }
    return motion to { amplitude }
}

/**
 * A track whose played part is a wave and whose rest is a straight line, both with round caps.
 * Everything that moves is read here, in draw, so a playing wave never recomposes the slider.
 */
private fun Modifier.wavyTrack(
    fraction: () -> Float,
    motion: WaveMotion,
    amplitude: () -> Float,
    wavelength: Dp,
    thumbWidth: Dp,
    gap: Dp,
    activeColor: Color,
    inactiveColor: Color,
    stopDot: Boolean,
): Modifier =
    drawWithCache {
        val strokePx = WavyStrokeWidth.toPx()
        val stroke = Stroke(width = strokePx, cap = StrokeCap.Round)
        val wavelengthPx = wavelength.toPx()
        val amplitudePx = WaveAmplitude.toPx()
        val thumbWidthPx = thumbWidth.toPx()
        val gapPx = gap.toPx()
        val dotRadius = StopDotSize.toPx() / 2f
        val path = Path()
        onDrawBehind {
            val mid = size.height / 2f
            val width = size.width
            val at = fraction()
            val start = WavyTrackGeometry.waveStart(strokePx)
            val end = WavyTrackGeometry.trackEnd(width, strokePx)
            val waveEnd = WavyTrackGeometry.waveEnd(width, at, thumbWidthPx, gapPx, strokePx)
            val restStart = WavyTrackGeometry.restStart(width, at, thumbWidthPx, gapPx, strokePx)
            if (restStart < end) {
                drawLine(inactiveColor, Offset(restStart, mid), Offset(end, mid), strokeWidth = strokePx, cap = StrokeCap.Round)
            }
            if (stopDot) drawCircle(activeColor, radius = dotRadius, center = Offset(end, mid))
            if (waveEnd <= start) return@onDrawBehind
            val height = amplitudePx * amplitude() * WavyTrackGeometry.heightFactor(waveEnd - start, wavelengthPx)
            val shift = motion.phase.floatValue * 2f * PI.toFloat()
            path.rewind()
            var x = start
            path.moveTo(x, mid + height * sin(x / wavelengthPx * 2f * PI.toFloat() - shift))
            while (x < waveEnd) {
                x = minOf(x + WAVE_STEP_PX, waveEnd)
                path.lineTo(x, mid + height * sin(x / wavelengthPx * 2f * PI.toFloat() - shift))
            }
            drawPath(path, activeColor, style = stroke)
        }
    }

/**
 * The seek bar with the round thumb, shown as "Squiggly": a wave that grows its thumb while dragged.
 * [waveMoving] false holds the wave still without flattening it, for previews.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveWavySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    waveMoving: Boolean = true,
) {
    val isDragged by interactionSource.collectIsDraggedAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val wavelength = WavyProgressIndicatorDefaults.LinearDeterminateWavelength
    val (motion, amplitude) = rememberWaveMotion(isPlaying, waveMoving && !isDragged, wavelength, speed = wavelength)
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = activeColor.copy(alpha = EXPRESSIVE_INACTIVE_TRACK_ALPHA)
    val thumbSize by animateDpAsState(
        targetValue = if (isDragged || isPressed) ExpressiveThumbPressedSize else ExpressiveThumbSize,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "wavyThumb",
    )
    val sliderState = remember(valueRange) { SliderState(value, trackRange = valueRange) }
    sliderState.value = value
    Slider(
        state = sliderState,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        thumb = {
            Box(
                Modifier
                    .size(ExpressiveThumbPressedSize)
                    .wrapThumb(thumbSize, activeColor),
            )
        },
        track = { state ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(WavyStrokeWidth + WaveAmplitude * 2)
                    .wavyTrack(
                        fraction = { state.coercedValueAsFraction },
                        motion = motion,
                        amplitude = amplitude,
                        wavelength = wavelength,
                        thumbWidth = ExpressiveThumbSize,
                        gap = ExpressiveThumbGap,
                        activeColor = activeColor,
                        inactiveColor = inactiveColor,
                        stopDot = true,
                    ),
            )
        },
        modifier = modifier.fillMaxWidth().height(48.dp),
    )
}

/** A round thumb [diameter] across, centred in its fixed slot so growing it never moves the track. */
private fun Modifier.wrapThumb(
    diameter: Dp,
    color: Color,
): Modifier =
    drawWithCache {
        onDrawBehind {
            drawCircle(color, radius = diameter.toPx().coerceAtLeast(0f) / 2f)
        }
    }

/** The seek bar with the bar thumb, shown as "Wavy": a slower, tighter wave. [waveMoving] as above. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SquigglySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    waveMoving: Boolean = true,
) {
    val isDragged by interactionSource.collectIsDraggedAsState()
    val (motion, amplitude) = rememberWaveMotion(isPlaying, waveMoving && !isDragged, SquigglyWavelength, SquigglyWaveSpeed)
    val colors =
        SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = SQUIGGLY_INACTIVE_TRACK_ALPHA),
        )
    val sliderState = remember(valueRange) { SliderState(value, trackRange = valueRange) }
    sliderState.value = value
    Slider(
        state = sliderState,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interactionSource,
                colors = colors,
                thumbSize = SquigglyThumbSize,
            )
        },
        track = { state ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(WavyStrokeWidth + WaveAmplitude * 2)
                    .wavyTrack(
                        fraction = { state.coercedValueAsFraction },
                        motion = motion,
                        amplitude = amplitude,
                        wavelength = SquigglyWavelength,
                        thumbWidth = SquigglyThumbSize.width,
                        gap = 0.dp,
                        activeColor = colors.activeTrackColor,
                        inactiveColor = colors.inactiveTrackColor,
                        stopDot = false,
                    ),
            )
        },
        modifier = modifier.fillMaxWidth().height(48.dp),
    )
}
