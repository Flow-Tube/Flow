package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One option of a [FlowConnectedToggleGroup]. */
data class FlowToggleOption<T>(
    val value: T,
    val label: String,
    val icon: ImageVector? = null,
)

private val ContentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
private val IconSize = 18.dp
private val IconSpacing = 8.dp

/**
 * A single-choice row of connected toggle buttons.
 *
 * This is the Material 3 Expressive form of a segmented control: the buttons share the group's
 * rounded ends, and each one morphs its corners as it is pressed and checked. Lifted out of the
 * subscriptions manager when the download dialog became its second caller.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> FlowConnectedToggleGroup(
    options: List<FlowToggleOption<T>>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ToggleButtonColors = ToggleButtonDefaults.toggleButtonColors(),
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option.value == selected,
                enabled = enabled,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelected(option.value)
                },
                shapes = connectedButtonShapes(index = index, count = options.size),
                colors = colors,
                contentPadding = ContentPadding,
                modifier = Modifier.weight(1f),
            ) {
                if (option.icon != null) {
                    Icon(
                        imageVector = option.icon,
                        contentDescription = null,
                        modifier = Modifier.size(IconSize),
                    )
                    Spacer(modifier = Modifier.width(IconSpacing))
                }
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The shapes of button [index] of [count] in a connected group: rounded outer ends, small inner corners. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun connectedButtonShapes(
    index: Int,
    count: Int,
): ToggleButtonShapes =
    when (index) {
        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
        count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
    }
