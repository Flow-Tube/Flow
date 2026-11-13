package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified

internal const val MIN_LINE_SCALE = 0.6f
internal const val MAX_LINE_SCALE = 1.6f
private const val FIT_STEPS = 12

/** The largest scale in [min]..[max] that [fits], or [min] when even that is too big. */
internal fun largestFittingScale(
    min: Float,
    max: Float,
    fits: (Float) -> Boolean,
): Float {
    if (fits(max)) return max
    if (!fits(min)) return min
    var low = min
    var high = max
    repeat(FIT_STEPS) {
        val middle = (low + high) / 2
        if (fits(middle)) low = middle else high = middle
    }
    return low
}

/** The height of [heights] stacked with [spacing] between them. */
internal fun stackedHeight(
    heights: List<Int>,
    spacing: Int,
): Int = heights.sum() + spacing * (heights.size - 1).coerceAtLeast(0)

/** [this] with its type and line height scaled by [scale]. */
internal fun TextStyle.scaledBy(scale: Float): TextStyle =
    copy(
        fontSize = fontSize * scale,
        lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight,
    )
