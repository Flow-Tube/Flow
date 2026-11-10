package io.github.aedev.flow.ui.components.videoplayer.ambient

/** Immersive glow lights the whole page, so it carries more of the frame than the glow does. */
internal const val IMMERSIVE_GAIN_DARK = 0.45f
internal const val IMMERSIVE_MIX_LIGHT = 0.38f

/** Rows of light from the bottom of the player to the bottom of the page. */
internal const val IMMERSIVE_ROWS = 48

/** How many of the picture's bottom rows are mirrored down next to the player. */
private const val MIRRORED_ROWS = 6

/** The share of the light still left at the very bottom of the page. */
private const val BOTTOM_LIGHT = 0.6f

/**
 * Light for the whole page under the player, [rows] rows from its top to its bottom, in linear
 * light. Over the first [nearFraction] of the page, about one player height, it mirrors the
 * bottom of the picture so its shapes carry on past the video. Over the next as much it melts into
 * each column's average colour, and over the lower half it falls off a little toward the page.
 *
 * @param columns scratch for the per-column averages, `width * 3` long.
 */
internal fun pageLight(
    grid: FloatArray,
    width: Int,
    height: Int,
    rows: Int,
    nearFraction: Float,
    tone: AmbientGlowTone,
    out: FloatArray,
    columns: FloatArray,
) {
    columnAverages(grid, width, height, columns)
    val near = nearFraction.coerceIn(0.01f, 1f)
    val mirrored = minOf(MIRRORED_ROWS, height)
    for (y in 0 until rows) {
        val f = y / (rows - 1).coerceAtLeast(1).toFloat()
        val u = (f / near).coerceIn(0f, 1f)
        // Fractional source row, walking up from the bottom of the picture as the page goes down.
        val source = (height - 1) - u * (mirrored - 1)
        val upper = source.toInt().coerceIn(0, height - 1)
        val lower = (upper + 1).coerceAtMost(height - 1)
        val between = source - upper
        val melt = smoothStep((f - near) / near)
        val weight = 1f - (1f - BOTTOM_LIGHT) * smoothStep((f - 0.5f) / 0.5f)
        for (x in 0 until width) {
            val a = (upper * width + x) * 3
            val b = (lower * width + x) * 3
            val c = x * 3
            val r = grid[a] + (grid[b] - grid[a]) * between
            val g = grid[a + 1] + (grid[b + 1] - grid[a + 1]) * between
            val bl = grid[a + 2] + (grid[b + 2] - grid[a + 2]) * between
            tone.apply(
                r + (columns[c] - r) * melt,
                g + (columns[c + 1] - g) * melt,
                bl + (columns[c + 2] - bl) * melt,
                out,
                (y * width + x) * 3,
                weight,
            )
        }
    }
}

private fun columnAverages(
    grid: FloatArray,
    width: Int,
    height: Int,
    out: FloatArray,
) {
    out.fill(0f, 0, width * 3)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val o = (y * width + x) * 3
            out[x * 3] += grid[o]
            out[x * 3 + 1] += grid[o + 1]
            out[x * 3 + 2] += grid[o + 2]
        }
    }
    for (i in 0 until width * 3) out[i] /= height
}
