package io.github.aedev.flow.player

import kotlin.math.roundToInt

/** A rectangle in window pixels, kept free of android.graphics so the fitting stays unit-testable. */
internal data class PipBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * The part of the player view the video fills, centred as the player letterboxes it. PiP animates from
 * this rectangle into a window of [aspectRatio], so it has to match that ratio or the system falls back
 * to a jump or crossfade.
 */
internal fun pipSourceBounds(
    view: PipBounds,
    aspectRatio: Float,
): PipBounds? {
    if (view.width <= 0 || view.height <= 0 || aspectRatio <= 0f) return null
    val viewRatio = view.width.toFloat() / view.height
    return if (viewRatio > aspectRatio) {
        val width = (view.height * aspectRatio).roundToInt()
        val left = view.left + (view.width - width) / 2
        PipBounds(left, view.top, left + width, view.bottom)
    } else {
        val height = (view.width / aspectRatio).roundToInt()
        val top = view.top + (view.height - height) / 2
        PipBounds(view.left, top, view.right, top + height)
    }
}
