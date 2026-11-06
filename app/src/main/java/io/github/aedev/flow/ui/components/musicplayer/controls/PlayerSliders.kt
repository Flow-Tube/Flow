

// ============================================================================
// THIS IMPLEMENTATION WAS INSPIRED BY METROLIST
// ============================================================================

package io.github.aedev.flow.ui.components.musicplayer.controls

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.local.SliderStyle
import kotlinx.coroutines.delay

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
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
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

private val WavyStrokeWidth = 5.dp

// ============================================================================
// EXPRESSIVE WAVY SLIDER (seekable LinearWavyProgressIndicator + round thumb)
// ============================================================================
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveWavySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val range = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.001f)
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val fraction =
        if (isDragging) {
            dragFraction
        } else {
            ((value - valueRange.start) / range).coerceIn(0f, 1f)
        }
    val animatedAmplitude by animateFloatAsState(
        targetValue = if (isPlaying && !isDragging) 1f else 0f,
        label = "expressiveWaveAmplitude",
    )
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    val density = LocalDensity.current
    val thumbRadius = 7.dp
    val stroke = remember(density) { Stroke(width = with(density) { WavyStrokeWidth.toPx() }, cap = StrokeCap.Round) }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(48.dp)
                .pointerInput(valueRange) {
                    detectTapGestures { offset ->
                        val tapped = (offset.x / size.width).coerceIn(0f, 1f)
                        onValueChange(valueRange.start + tapped * range)
                        onValueChangeFinished()
                    }
                }.pointerInput(valueRange) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                            onValueChange(valueRange.start + dragFraction * range)
                        },
                        onDragEnd = {
                            isDragging = false
                            onValueChangeFinished()
                        },
                        onDragCancel = { isDragging = false },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragFraction = (dragFraction + dragAmount / size.width).coerceIn(0f, 1f)
                            onValueChange(valueRange.start + dragFraction * range)
                        },
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        LinearWavyProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
            color = activeColor,
            trackColor = inactiveColor,
            stroke = stroke,
            trackStroke = stroke,
            gapSize = thumbRadius + 4.dp,
            amplitude = { progress -> if (progress > 0f) animatedAmplitude else 0f },
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = activeColor,
                radius = thumbRadius.toPx(),
                center = Offset(size.width * fraction, size.height / 2f),
            )
        }
    }
}

// ============================================================================
// SQUIGGLY SLIDER (M3 Slider with a wavy track and a bar thumb)
// ============================================================================
private val SquigglyWaveAmplitude = 3.dp
private val SquigglyWavelength = 32.dp
private val SquigglyWaveSpeed = 8.dp
private val SquigglyThumbSize = DpSize(width = 5.dp, height = 20.dp)
private const val SQUIGGLY_INACTIVE_TRACK_ALPHA = 0.3f

// How long LinearWavyProgressIndicator takes to flatten. Its wave phase snaps to 0 as soon as the
// speed reaches 0, so the speed has to outlast the flattening or the wave hops on pause.
private const val SQUIGGLY_FLATTEN_MILLIS = 500L

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
) {
    val isDragged by interactionSource.collectIsDraggedAsState()
    // A State, because the indicator reads amplitude in its draw cache and only a state read refreshes it.
    val isWaving by rememberUpdatedState(isPlaying && !isDragged)
    var waveSpeed by remember { mutableStateOf(if (isWaving) SquigglyWaveSpeed else 0.dp) }
    LaunchedEffect(isWaving) {
        if (isWaving) {
            waveSpeed = SquigglyWaveSpeed
        } else {
            delay(SQUIGGLY_FLATTEN_MILLIS)
            waveSpeed = 0.dp
        }
    }
    val colors =
        SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = SQUIGGLY_INACTIVE_TRACK_ALPHA),
        )
    val density = LocalDensity.current
    val stroke = remember(density) { Stroke(width = with(density) { WavyStrokeWidth.toPx() }, cap = StrokeCap.Round) }

    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        interactionSource = interactionSource,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interactionSource,
                colors = colors,
                thumbSize = SquigglyThumbSize,
            )
        },
        track = { sliderState ->
            LinearWavyProgressIndicator(
                progress = { sliderState.coercedValueAsFraction },
                // The Slider already reports the value and takes seeks; this would be a second, read-only node.
                modifier =
                    Modifier
                        .clearAndSetSemantics {}
                        .fillMaxWidth()
                        .height(WavyStrokeWidth + SquigglyWaveAmplitude * 2),
                color = colors.activeTrackColor,
                trackColor = colors.inactiveTrackColor,
                stroke = stroke,
                trackStroke = stroke,
                gapSize = 0.dp,
                stopSize = 0.dp,
                amplitude = { if (isWaving) 1f else 0f },
                wavelength = SquigglyWavelength,
                waveSpeed = waveSpeed,
            )
        },
        modifier = modifier.fillMaxWidth().height(48.dp),
    )
}
