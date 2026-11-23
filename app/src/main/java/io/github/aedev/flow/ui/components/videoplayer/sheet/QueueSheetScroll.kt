package io.github.aedev.flow.ui.components.videoplayer.sheet

import kotlin.math.abs

private const val ROWS_ABOVE_CURRENT = 2
private const val ANIMATED_ROWS = 7

/** The row the queue sheet scrolls to: the current video with the two before it still in view. */
internal fun queueScrollTarget(currentIndex: Int): Int = (currentIndex - ROWS_ABOVE_CURRENT).coerceAtLeast(0)

/**
 * Where to jump before animating from [firstVisibleIndex] to [target], or null when the target is
 * near enough to animate the whole way. A far target lands [ANIMATED_ROWS] short, so the animation
 * runs the same short distance from either direction.
 */
internal fun queueScrollJump(
    target: Int,
    firstVisibleIndex: Int,
): Int? =
    when {
        abs(target - firstVisibleIndex) < ANIMATED_ROWS -> null
        target > firstVisibleIndex -> target - ANIMATED_ROWS
        else -> target + ANIMATED_ROWS
    }
