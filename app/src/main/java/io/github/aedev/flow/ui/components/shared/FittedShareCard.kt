package io.github.aedev.flow.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import kotlin.math.roundToInt

/**
 * Lays [content] out at its own full size and shows it scaled down to the space it is given. The
 * scale is applied around the content, so an image recorded from it keeps the full size whatever
 * the screen.
 */
@Composable
fun FittedShareCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val card = measurables.single().measure(Constraints())
        val scale = fitScale(card.width, card.height, constraints.maxWidth, constraints.maxHeight)
        val width = constraints.constrainWidth((card.width * scale).roundToInt())
        val height = constraints.constrainHeight((card.height * scale).roundToInt())
        layout(width, height) {
            card.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

/** The scale that fits a [width] × [height] card into [maxWidth] × [maxHeight], never above 1. */
fun fitScale(
    width: Int,
    height: Int,
    maxWidth: Int,
    maxHeight: Int,
): Float {
    if (width <= 0 || height <= 0) return 1f
    return minOf(1f, maxWidth.toFloat() / width, maxHeight.toFloat() / height)
}
