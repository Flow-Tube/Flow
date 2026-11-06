package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlin.math.floor
import kotlin.math.roundToInt

private const val MIN_SCRIM = 0.3f
private const val MAX_SCRIM = 0.9f
private const val SCRIM_STEP = 0.02f
private const val MIN_TEXT_CONTRAST = 7f
private const val CONTRAST_OFFSET = 0.05f
private const val WHITE_LUMINANCE = 1f
private const val OPAQUE = 0xFF shl 24
private const val CHANNEL_MASK = 0xFF
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

/** A cover reduced to a few colors, darkened so white text stays readable on its brightest cell. */
internal class LyricsCoverColors(
    val cells: IntArray,
    val brightest: Color,
)

internal fun lyricsCoverColors(pixels: IntArray): LyricsCoverColors {
    val scrim = lyricsCoverScrim(pixels)
    val cells = IntArray(pixels.size) { darkened(pixels[it], scrim) }
    return LyricsCoverColors(cells, Color(cells.maxBy { Color(it).luminance() }))
}

/** The least darkening at or above [MIN_SCRIM] that keeps white text readable on every cell. */
internal fun lyricsCoverScrim(pixels: IntArray): Float {
    val brightest = pixels.maxBy { Color(it).luminance() }
    var scrim = MIN_SCRIM
    while (scrim < MAX_SCRIM && contrastWithWhite(Color(darkened(brightest, scrim))) < MIN_TEXT_CONTRAST) {
        scrim += SCRIM_STEP
    }
    return scrim
}

/** Enlarges a square of [side] cells to [size] pixels with smoothed steps, which hides the grid bilinear scaling would show. */
internal fun lyricsCoverMesh(
    cells: IntArray,
    side: Int,
    size: Int,
): IntArray =
    IntArray(size * size) { index ->
        val across = gridPosition(index % size, size, side)
        val down = gridPosition(index / size, size, side)
        val left = floor(across).toInt().coerceAtMost(side - 2)
        val top = floor(down).toInt().coerceAtMost(side - 2)
        val x = smoothed(across - left)
        val y = smoothed(down - top)
        val upper = mixed(cells[top * side + left], cells[top * side + left + 1], x)
        val lower = mixed(cells[(top + 1) * side + left], cells[(top + 1) * side + left + 1], x)
        mixed(upper, lower, y)
    }

private fun gridPosition(
    pixel: Int,
    size: Int,
    side: Int,
): Float = pixel.toFloat() / (size - 1) * (side - 1)

private fun smoothed(t: Float): Float = t * t * (3 - 2 * t)

private fun contrastWithWhite(color: Color): Float = (WHITE_LUMINANCE + CONTRAST_OFFSET) / (color.luminance() + CONTRAST_OFFSET)

private fun darkened(
    argb: Int,
    scrim: Float,
): Int = mixed(argb, Color.Black.toArgb(), scrim)

private fun mixed(
    from: Int,
    to: Int,
    amount: Float,
): Int {
    fun channel(shift: Int): Int {
        val start = from shr shift and CHANNEL_MASK
        val end = to shr shift and CHANNEL_MASK
        return (start + (end - start) * amount).roundToInt()
    }
    return OPAQUE or (channel(RED_SHIFT) shl RED_SHIFT) or (channel(GREEN_SHIFT) shl GREEN_SHIFT) or channel(0)
}
