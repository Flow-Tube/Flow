package io.github.aedev.flow.ui.components.equalizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.audio.eq.BuiltInEqPresets
import io.github.aedev.flow.data.audio.eq.EqLimits
import io.github.aedev.flow.data.audio.eq.EqState
import io.github.aedev.flow.data.audio.eq.isEdited
import io.github.aedev.flow.ui.components.shared.FlowFilterChip
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.FlowRowGroup
import io.github.aedev.flow.ui.components.shared.FlowSwitchRow
import io.github.aedev.flow.ui.components.shared.flowRowGroupShape
import io.github.aedev.flow.ui.screens.equalizer.EqualizerViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

private const val ROWS = 5
private const val DISABLED_ALPHA = 0.4f
private val PreviewHeight = 112.dp
private val PreviewPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
private val RowPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

/**
 * The equalizer as the players show it: on/off, a picture of the curve, presets one tap away, bass
 * boost, and the way into the full page. The music Audio sheet and the video settings sheet both use it.
 */
@Composable
internal fun EqQuickPanel(
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EqualizerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FlowRowGroup(modifier = modifier) {
        FlowSwitchRow(
            title = stringResource(R.string.equalizer),
            supportingText = presetSummary(state),
            checked = state.enabled,
            onCheckedChange = viewModel::setEnabled,
            leadingIcon = Icons.Rounded.GraphicEq,
            shape = flowRowGroupShape(0, ROWS),
        )
        PanelRow(shape = flowRowGroupShape(1, ROWS), onClick = onEdit) {
            EqResponseGraph(
                bands = state.active.curve.bands,
                bassBoost = state.bassBoost,
                showPoints = false,
                showLabels = false,
                modifier = Modifier.fillMaxWidth().height(PreviewHeight).padding(PreviewPadding),
            )
        }
        PanelRow(shape = flowRowGroupShape(2, ROWS)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.userPresets, key = { it.id }) { preset ->
                    FlowFilterChip(
                        label = preset.name,
                        selected = state.active.presetId == preset.id,
                        onClick = { viewModel.selectPreset(preset.id) },
                    )
                }
                items(BuiltInEqPresets.all, key = { it.id }) { preset ->
                    FlowFilterChip(
                        label = stringResource(preset.nameRes),
                        selected = state.active.presetId == preset.id,
                        onClick = { viewModel.selectPreset(preset.id) },
                    )
                }
            }
        }
        PanelRow(shape = flowRowGroupShape(3, ROWS)) {
            EqBassBoostSlider(
                value = state.bassBoost,
                onValueChange = viewModel::setBassBoost,
                enabled = state.enabled,
                modifier = Modifier.padding(RowPadding),
            )
        }
        FlowNavRow(
            title = stringResource(R.string.eq_edit),
            onClick = onEdit,
            leadingIcon = Icons.Rounded.Tune,
            shape = flowRowGroupShape(4, ROWS),
        )
    }
}

@Composable
internal fun EqBassBoostSlider(
    value: Double,
    onValueChange: (Double) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA),
        ) {
            Text(stringResource(R.string.eq_bass_boost), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text = if (value > 0.0) formatGain(value) else stringResource(R.string.off),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().toDouble()) },
            valueRange = 0f..EqLimits.MAX_BASS_BOOST.toFloat(),
            enabled = enabled,
        )
    }
}

/**
 * The equalizer state, provided once by the app shell. The player sheets read it for their summary
 * rows instead of creating a ViewModel, so they still render in previews and tests without Hilt.
 */
val LocalEqualizerState = staticCompositionLocalOf<StateFlow<EqState>?> { null }

/** For a row that links to the equalizer: the playing preset, Off, or nothing when no state is provided. */
@Composable
internal fun equalizerSummary(): String? {
    val flow = LocalEqualizerState.current ?: return null
    val state by flow.collectAsStateWithLifecycle()
    return if (state.enabled) activePresetName(state) else stringResource(R.string.off)
}

/** The preset name, and "Edited" when the curve has moved away from it. */
@Composable
internal fun presetSummary(state: EqState): String {
    val name = activePresetName(state)
    return if (state.isEdited && state.active.presetId != null) {
        stringResource(R.string.eq_band_line, name, stringResource(R.string.eq_edited))
    } else {
        name
    }
}

/** A free-form row inside a [FlowRowGroup], on the same container and shape as the kit's rows. */
@Composable
internal fun PanelRow(
    shape: Shape,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = color, modifier = modifier.fillMaxWidth()) { content() }
    } else {
        Surface(shape = shape, color = color, modifier = modifier.fillMaxWidth()) { content() }
    }
}
