package io.github.aedev.flow.ui.components.videoplayer.ambient

import kotlin.math.abs
import kotlin.math.pow

internal const val SAMPLE_W = 96
internal const val SAMPLE_H = 54
internal const val DISPLAY_W = 32
internal const val DISPLAY_H = 18

/** 96/32 == 54/18 == 3, so decimation is an exact 3x3 box and needs no resampler. */
private const val DECIMATION = 3

internal const val BLUR_RADIUS_PX = 2
internal const val BLUR_PASSES = 3

private const val AMBIENT_GAIN = 0.28f

/**
 * A blurred, dimmed glow reads greyer than the picture it comes from, so its colour is lifted a
 * little. Grey stays grey and brightness is kept; see [boostChroma].
 */
internal const val AMBIENT_CHROMA_BOOST = 1.15f

/**
 * Below this mean per-channel delta (0..255, sRGB) the frame is treated as unchanged and the
 * smoothing target is left alone. Static shots are most of a watch session.
 */
internal const val FRAME_CHANGE_THRESHOLD = 4

/** Every Nth pixel is compared for change detection; the sample is already tiny. */
internal const val CHANGE_SAMPLE_STEP = 7

private const val LINEAR_LUT_SIZE = 4096

/** Exact sRGB EOTF; 256 entries covers every possible input byte. */
private val SRGB_TO_LINEAR =
    FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

/**
 * Inverse EOTF. 4096 entries rather than the ~2048 that would give half-LSB accuracy across most of
 * the range: the curve's slope is 12.92 near black, so a coarser table quantises visibly there —
 * and near-black is exactly where a dim glow lives.
 */
private val LINEAR_TO_SRGB =
    IntArray(LINEAR_LUT_SIZE + 1) { i ->
        val c = i / LINEAR_LUT_SIZE.toFloat()
        val s = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
        (s * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

/** Once every channel is within this of its target (linear units) the filter stops emitting. */
private const val CONVERGENCE_EPS = 0.0015f

/** Advances [current] toward [target]; returns whether anything moved beyond the epsilon. */
internal fun step(
    current: FloatArray,
    target: FloatArray,
    alpha: Float,
): Boolean {
    var moved = false
    for (i in current.indices) {
        val delta = target[i] - current[i]
        if (abs(delta) > CONVERGENCE_EPS) {
            current[i] += delta * alpha
            moved = true
        } else {
            current[i] = target[i]
        }
    }
    return moved
}

/**
 * Fuses the 3x3 decimation with the sRGB to linear conversion in one pass.
 *
 * The previous implementation used `createScaledBitmap(..., filter = true)`, which is bilinear: it
 * samples 2x2 texels where a 3x reduction needs 3x3, so roughly half the source was discarded and
 * the aliasing the 96x54 capture existed to prevent came back as a crawling glow on fine detail.
 * Averaging in gamma-encoded sRGB was the other half — mixing saturated complementaries there lands
 * darker than either input.
 */
internal fun decimateToLinear(
    src: IntArray,
    dst: FloatArray,
) {
    val n = (DECIMATION * DECIMATION).toFloat()
    var o = 0
    for (y in 0 until DISPLAY_H) {
        for (x in 0 until DISPLAY_W) {
            var r = 0f
            var g = 0f
            var b = 0f
            for (dy in 0 until DECIMATION) {
                var idx = (y * DECIMATION + dy) * SAMPLE_W + x * DECIMATION
                for (dx in 0 until DECIMATION) {
                    val c = src[idx++]
                    r += SRGB_TO_LINEAR[(c shr 16) and 0xFF]
                    g += SRGB_TO_LINEAR[(c shr 8) and 0xFF]
                    b += SRGB_TO_LINEAR[c and 0xFF]
                }
            }
            dst[o++] = r / n
            dst[o++] = g / n
            dst[o++] = b / n
        }
    }
}

internal fun boxBlurLinear(
    buf: FloatArray,
    scratch: FloatArray,
    w: Int,
    h: Int,
    radius: Int,
    passes: Int,
) {
    if (radius <= 0 || w <= 0 || h <= 0) return
    repeat(passes) {
        blurAxisLinear(buf, scratch, w, h, radius, horizontal = true)
        blurAxisLinear(scratch, buf, w, h, radius, horizontal = false)
    }
}

private fun blurAxisLinear(
    src: FloatArray,
    dst: FloatArray,
    w: Int,
    h: Int,
    radius: Int,
    horizontal: Boolean,
) {
    val lines = if (horizontal) h else w
    val span = if (horizontal) w else h
    for (line in 0 until lines) {
        for (i in 0 until span) {
            var r = 0f
            var g = 0f
            var b = 0f
            var n = 0
            for (k in -radius..radius) {
                val j = (i + k).coerceIn(0, span - 1)
                val o = (if (horizontal) line * w + j else j * w + line) * 3
                r += src[o]
                g += src[o + 1]
                b += src[o + 2]
                n++
            }
            val o = (if (horizontal) line * w + i else i * w + line) * 3
            dst[o] = r / n
            dst[o + 1] = g / n
            dst[o + 2] = b / n
        }
    }
}

internal fun encodeToPixels(
    grid: FloatArray,
    out: IntArray,
) {
    for (i in out.indices) {
        val o = i * 3
        out[i] = (0xFF shl 24) or
            (linearToSrgb(grid[o] * AMBIENT_GAIN) shl 16) or
            (linearToSrgb(grid[o + 1] * AMBIENT_GAIN) shl 8) or
            linearToSrgb(grid[o + 2] * AMBIENT_GAIN)
    }
}

/**
 * Rounds rather than truncates into the table. Flooring costs up to a full table step, which near
 * black — where the EOTF slope is 12.92 — is enough to lose a whole output level and break the
 * round trip.
 */
internal fun linearToSrgb(v: Float): Int = LINEAR_TO_SRGB[(v.coerceIn(0f, 1f) * LINEAR_LUT_SIZE + 0.5f).toInt()]

internal fun srgbToLinear(byteValue: Int): Float = SRGB_TO_LINEAR[byteValue.coerceIn(0, 255)]

/** Mean absolute per-channel difference over a strided subset of the two buffers. */
internal fun meanAbsDiff(
    current: IntArray,
    previous: IntArray,
): Int {
    var sum = 0L
    var count = 0
    var i = 0
    while (i < current.size) {
        val a = current[i]
        val b = previous[i]
        sum += abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        sum += abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        sum += abs((a and 0xFF) - (b and 0xFF))
        count += 3
        i += CHANGE_SAMPLE_STEP
    }
    return if (count == 0) 0 else (sum / count).toInt()
}

/** Box-averages a [w] x [h] picture into the light grid in linear light; every source pixel counts once. */
internal fun areaToGrid(
    src: IntArray,
    w: Int,
    h: Int,
    dst: FloatArray,
) {
    var o = 0
    for (cy in 0 until DISPLAY_H) {
        val y0 = cy * h / DISPLAY_H
        val y1 = maxOf(y0 + 1, (cy + 1) * h / DISPLAY_H)
        for (cx in 0 until DISPLAY_W) {
            val x0 = cx * w / DISPLAY_W
            val x1 = maxOf(x0 + 1, (cx + 1) * w / DISPLAY_W)
            var r = 0f
            var g = 0f
            var b = 0f
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    val c = src[y * w + x]
                    r += SRGB_TO_LINEAR[(c shr 16) and 0xFF]
                    g += SRGB_TO_LINEAR[(c shr 8) and 0xFF]
                    b += SRGB_TO_LINEAR[c and 0xFF]
                }
            }
            val n = ((y1 - y0) * (x1 - x0)).toFloat()
            dst[o++] = r / n
            dst[o++] = g / n
            dst[o++] = b / n
        }
    }
}

/** Mean absolute difference of two grids, in linear units. */
internal fun gridDifference(
    a: FloatArray,
    b: FloatArray,
): Float {
    var sum = 0f
    for (i in a.indices) sum += abs(a[i] - b[i])
    return if (a.isEmpty()) 0f else sum / a.size
}

internal fun lerpGrid(
    from: FloatArray,
    to: FloatArray,
    t: Float,
    out: FloatArray,
) {
    for (i in out.indices) out[i] = from[i] + (to[i] - from[i]) * t
}

/** Scales each cell's colour away from its own luminance, in linear light, so brightness is kept. */
internal fun boostChroma(
    grid: FloatArray,
    boost: Float,
) {
    var i = 0
    while (i < grid.size) {
        val r = grid[i]
        val g = grid[i + 1]
        val b = grid[i + 2]
        val luminance = relativeLuminance(r, g, b)
        grid[i] = (luminance + (r - luminance) * boost).coerceIn(0f, 1f)
        grid[i + 1] = (luminance + (g - luminance) * boost).coerceIn(0f, 1f)
        grid[i + 2] = (luminance + (b - luminance) * boost).coerceIn(0f, 1f)
        i += 3
    }
}
