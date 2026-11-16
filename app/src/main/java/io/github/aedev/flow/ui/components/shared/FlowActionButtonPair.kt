package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp

/** One half of a [FlowActionButtonPair]. */
class FlowPairAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

/**
 * A page's two main actions as a connected pair of medium buttons sharing the width: [primary]
 * filled, [secondary] tonal. Play all and Shuffle on a playlist; Resume and Edit on a note. A
 * [tertiary] action joins the group as an icon-only tonal button at the end.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FlowActionButtonPair(
    primary: FlowPairAction,
    secondary: FlowPairAction,
    modifier: Modifier = Modifier,
    tertiary: FlowPairAction? = null,
) {
    val height = ButtonDefaults.MediumContainerHeight
    val count = if (tertiary == null) 2 else 3
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        val leading = connectedButtonShapes(index = 0, count = count)
        val trailing = connectedButtonShapes(index = 1, count = count)
        Button(
            onClick = primary.onClick,
            shapes = ButtonShapes(leading.shape, leading.pressedShape),
            contentPadding = ButtonDefaults.contentPaddingFor(height),
            modifier = Modifier.weight(1f).heightIn(min = height),
        ) {
            PairActionLabel(primary.icon, primary.label, height)
        }
        FilledTonalButton(
            onClick = secondary.onClick,
            shapes = ButtonShapes(trailing.shape, trailing.pressedShape),
            contentPadding = ButtonDefaults.contentPaddingFor(height),
            modifier = Modifier.weight(1f).heightIn(min = height),
        ) {
            PairActionLabel(secondary.icon, secondary.label, height)
        }
        if (tertiary != null) {
            val end = connectedButtonShapes(index = 2, count = count)
            FilledTonalButton(
                onClick = tertiary.onClick,
                shapes = ButtonShapes(end.shape, end.pressedShape),
                contentPadding = ButtonDefaults.contentPaddingFor(height),
                modifier = Modifier.heightIn(min = height),
            ) {
                Icon(
                    imageVector = tertiary.icon,
                    contentDescription = tertiary.label,
                    modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)),
                )
            }
        }
    }
}

@Composable
private fun PairActionLabel(
    icon: ImageVector,
    label: String,
    height: Dp,
) {
    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
    Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
    Text(text = label, style = ButtonDefaults.textStyleFor(height), maxLines = 1)
}
