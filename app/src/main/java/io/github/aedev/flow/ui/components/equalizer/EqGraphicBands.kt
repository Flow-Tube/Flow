package io.github.aedev.flow.ui.components.equalizer

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalSlider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.audio.eq.GraphicEq
import kotlin.math.roundToInt

private val SliderHeight = 200.dp
private val ThumbSize = DpSize(28.dp, 4.dp)

/** Ten octave bands on M3 vertical sliders with a centred track, as on Flow Desktop. */
@Composable
internal fun EqGraphicBands(
    gains: List<Double>,
    onGainChange: (index: Int, gain: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        GraphicEq.FREQUENCIES.forEachIndexed { index, frequency ->
            GraphicBand(
                frequency = frequency,
                gain = gains.getOrElse(index) { 0.0 },
                onGainChange = { onGainChange(index, it) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GraphicBand(
    frequency: Double,
    gain: Double,
    onGainChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val max = GraphicEq.MAX_GAIN.toFloat()
    val state = remember { SliderState(value = gain.toFloat(), trackRange = -max..max) }
    val latest by rememberUpdatedState(onGainChange)
    val interactions = remember { MutableInteractionSource() }
    val label = formatFrequencyShort(frequency)
    val spoken = formatFrequency(frequency)

    LaunchedEffect(gain) { if (!state.isDragging) state.value = gain.toFloat() }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = signedNumber(state.value.toDouble(), 1),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        VerticalSlider(
            state = state,
            onValueChange = { raw ->
                val snapped = (raw / GraphicEq.STEP).roundToInt() * GraphicEq.STEP
                state.value = snapped.toFloat()
                latest(snapped)
            },
            topToBottom = false,
            interactionSource = interactions,
            thumb = { SliderDefaults.Thumb(interactionSource = interactions, isVertical = true, thumbSize = ThumbSize) },
            track = { SliderDefaults.CenteredTrack(sliderState = it) },
            modifier =
                Modifier
                    .height(SliderHeight)
                    .semantics { contentDescription = spoken },
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
