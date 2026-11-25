package io.github.aedev.flow.ui.components.videoplayer.controls

import kotlin.math.ceil
import kotlin.math.floor

/** One frame of the scrub strip: the moment it shows, and where its left edge falls. */
internal data class SeekStripFrame(
    val positionMs: Long,
    val leftPx: Float,
)

/**
 * The frames visible in a scrub strip, laid out as a magnified timeline around [positionMs].
 *
 * Time maps to x at [frameWidthPx] per [intervalMs] rather than across the whole video, so the strip
 * slides under the thumb an order of magnitude faster than the thumb crosses the bar. That
 * magnification is the point of showing a strip instead of a single frame.
 *
 * Frames before the start or past the end are left out rather than clamped: empty strip is the
 * honest way to show there is nothing further to scrub to.
 */
internal fun seekStripFrames(
    positionMs: Long,
    intervalMs: Long,
    frameCount: Int,
    thumbXPx: Float,
    frameWidthPx: Float,
    stripWidthPx: Float,
): List<SeekStripFrame> {
    if (intervalMs <= 0L || frameCount <= 0 || frameWidthPx < 1f || stripWidthPx <= 0f) return emptyList()
    val pxPerMs = frameWidthPx / intervalMs
    val firstMs = positionMs - ((thumbXPx + frameWidthPx) / pxPerMs).toDouble()
    val lastMs = positionMs + ((stripWidthPx - thumbXPx) / pxPerMs).toDouble()
    val first = floor(firstMs / intervalMs).toInt().coerceAtLeast(0)
    val last = ceil(lastMs / intervalMs).toInt().coerceAtMost(frameCount - 1)
    if (last < first) return emptyList()
    return (first..last).mapNotNull { index ->
        val frameMs = index * intervalMs
        val left = thumbXPx + (frameMs - positionMs) * pxPerMs
        SeekStripFrame(positionMs = frameMs, leftPx = left)
            .takeIf { left < stripWidthPx && left + frameWidthPx > 0f }
    }
}

/** The frames a strip can lay out; a live level has no count of its own and spans the DVR window. */
internal fun stripFrameCount(
    frameCount: Int,
    isLive: Boolean,
    intervalMs: Long,
    durationMs: Long,
): Int =
    when {
        !isLive -> frameCount
        intervalMs <= 0L || durationMs <= 0L -> 0
        else -> ceil(durationMs.toDouble() / intervalMs).toInt()
    }
