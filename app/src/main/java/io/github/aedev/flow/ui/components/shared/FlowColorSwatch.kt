package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * One colour to pick, as a circle. A swatch marks its selection with a check inside the colour rather than a ring
 * around it: an accent-coloured outline reads as decoration next to a row of colours. [fill] paints a choice that is
 * more than one colour inside the circle; [color] is then what the check is measured against.
 */
@Composable
fun FlowColorSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier =
            modifier
                .size(FlowColorSwatchDefaults.Size)
                .clip(CircleShape)
                .background(color)
                .border(FlowColorSwatchDefaults.BorderWidth, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        fill?.invoke()
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = if (color.luminance() > SWATCH_LUMINANCE_SPLIT) Color.Black else Color.White,
                modifier = Modifier.size(FlowColorSwatchDefaults.IconSize),
            )
        }
    }
}

/** A swatch's measurements, for a neighbouring control that has to line up with it. */
object FlowColorSwatchDefaults {
    val Size = 42.dp
    val BorderWidth = 1.dp
    val IconSize = 20.dp
}

private const val SWATCH_LUMINANCE_SPLIT = 0.5f
