package io.github.aedev.flow.ui.components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.ui.components.shared.FlowBadgedTitle
import io.github.aedev.flow.ui.components.shared.FlowConnectedToggleGroup
import io.github.aedev.flow.ui.components.shared.FlowToggleOption

private val ControlRowHorizontalPadding = 16.dp
private val ControlRowVerticalPadding = 14.dp
private val ControlRowSpacing = 12.dp
private const val DISABLED_ALPHA = 0.4f

/**
 * A setting answered in place by a connected toggle group under its title — the Material 3
 * Expressive replacement for a segmented button row. A [preview] sits between the two when the
 * choice is easier to see than to read.
 */
@Composable
fun <T> SettingsToggleGroupRow(
    title: String,
    options: List<FlowToggleOption<T>>,
    selected: T,
    onSelected: (T) -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    preview: (@Composable () -> Unit)? = null,
    titleBadge: Boolean = false,
) {
    SettingsControlRowFrame(shape = shape, modifier = modifier) {
        SettingsControlRowTitle(title = title, summary = summary, enabled = enabled, badge = titleBadge)
        preview?.invoke()
        FlowConnectedToggleGroup(
            options = options,
            selected = selected,
            onSelected = onSelected,
            enabled = enabled,
        )
    }
}

/** A numeric setting on a slider that commits its value when the drag ends. */
@Composable
fun SettingsSliderRow(
    title: String,
    value: Float,
    onValueCommitted: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: @Composable (Float) -> String,
    shape: Shape,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    summary: String? = null,
    enabled: Boolean = true,
    titleBadge: Boolean = false,
    centered: Boolean = false,
) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    SettingsControlRowFrame(shape = shape, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                SettingsControlRowTitle(title = title, summary = summary, enabled = enabled, badge = titleBadge)
            }
            Text(
                text = valueLabel(draft),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA),
            )
        }
        if (centered) {
            Slider(
                value = draft,
                onValueChange = { draft = it },
                onValueChangeFinished = { onValueCommitted(draft) },
                valueRange = valueRange,
                steps = steps,
                enabled = enabled,
                track = { SliderDefaults.CenteredTrack(it, enabled = enabled) },
            )
        } else {
            Slider(
                value = draft,
                onValueChange = { draft = it },
                onValueChangeFinished = { onValueCommitted(draft) },
                valueRange = valueRange,
                steps = steps,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun SettingsControlRowFrame(
    shape: Shape,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = ControlRowHorizontalPadding, vertical = ControlRowVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(ControlRowSpacing),
    ) {
        content()
    }
}

@Composable
private fun SettingsControlRowTitle(
    title: String,
    summary: String?,
    enabled: Boolean,
    badge: Boolean,
) {
    Column(modifier = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA)) {
        FlowBadgedTitle(
            title = title,
            badge = badge,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (summary != null) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
