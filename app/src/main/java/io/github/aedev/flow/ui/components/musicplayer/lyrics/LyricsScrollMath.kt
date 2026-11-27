package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.flow.first

/** Row heights to assume until a row has been measured, and the gap between main lines. */
internal class LyricsRowMetrics(
    val lineHeightPx: Float,
    val indicatorHeightPx: Float,
    val constraintLineHeightPx: Float,
    val gapPx: Float,
)

/** Background vocals and instrumental breaks hug the row above them. */
private fun LyricsListItem.hugsPrevious(): Boolean =
    (this as? LyricsListItem.Line)?.entry?.isBackground == true || this is LyricsListItem.Indicator

private fun LyricsListItem.fallbackHeight(
    lineHeightPx: Float,
    metrics: LyricsRowMetrics,
): Float = if (this is LyricsListItem.Indicator) metrics.indicatorHeightPx else lineHeightPx

/** Each row's top, relative to the active row's top, in px. */
internal fun lyricsRowOffsets(
    items: List<LyricsListItem>,
    heights: Map<Int, Int>,
    activeIndex: Int,
    metrics: LyricsRowMetrics,
): Map<Int, Float> {
    val map = mutableMapOf<Int, Float>()
    if (activeIndex == -1 || items.isEmpty()) return map

    map[activeIndex] = 0f
    var currentY = 0f
    for (i in activeIndex - 1 downTo 0) {
        val item = items[i]
        val height = heights[i]?.toFloat() ?: item.fallbackHeight(metrics.lineHeightPx, metrics)
        currentY -= height + if (item.hugsPrevious()) 0f else metrics.gapPx
        map[i] = currentY
    }
    currentY = 0f
    for (i in activeIndex until items.size - 1) {
        val height = heights[i]?.toFloat() ?: items[i].fallbackHeight(metrics.lineHeightPx, metrics)
        currentY += height + if (items[i + 1].hugsPrevious()) 0f else metrics.gapPx
        map[i + 1] = currentY
    }
    return map
}

/** How a row moves to a new target offset. */
internal enum class LyricsRowMove { SNAP, CASCADE, FOLLOW }

/**
 * While the user has scrolled away, the active row is held, so a new target can only come from a
 * row measured late; the rows still follow it, or a row that wraps lands on the one below (#2422).
 */
internal fun lyricsRowMove(
    isInitialLayout: Boolean,
    motionEnabled: Boolean,
    autoScroll: Boolean,
): LyricsRowMove =
    when {
        isInitialLayout || !motionEnabled -> LyricsRowMove.SNAP
        autoScroll -> LyricsRowMove.CASCADE
        else -> LyricsRowMove.FOLLOW
    }

/** How far the user may drag the list: the last row stays [bottomMarginPx] from the top edge. */
internal fun lyricsMinScrollOffset(
    items: List<LyricsListItem>,
    heights: Map<Int, Int>,
    activeIndex: Int,
    anchorY: Float,
    bottomMarginPx: Float,
    metrics: LyricsRowMetrics,
): Float {
    if (items.isEmpty() || activeIndex == -1) return 0f
    val totalBelow =
        (activeIndex until items.size - 1)
            .sumOf { i ->
                val height = heights[i]?.toFloat() ?: items[i].fallbackHeight(metrics.constraintLineHeightPx, metrics)
                (height + if (items[i + 1].hugsPrevious()) 0f else metrics.gapPx).toDouble()
            }.toFloat()
    val lastHeight = heights[items.size - 1]?.toFloat() ?: items.last().fallbackHeight(metrics.constraintLineHeightPx, metrics)
    return bottomMarginPx - anchorY - totalBelow - lastHeight
}

/** How far the user may drag the list down: the first row stays [topMarginPx] above the bottom. */
internal fun lyricsMaxScrollOffset(
    items: List<LyricsListItem>,
    heights: Map<Int, Int>,
    activeIndex: Int,
    anchorY: Float,
    viewportHeightPx: Float,
    topMarginPx: Float,
    metrics: LyricsRowMetrics,
): Float {
    if (items.isEmpty() || activeIndex == -1) return 0f
    val totalAbove =
        (0 until activeIndex)
            .sumOf { i ->
                val height = heights[i]?.toFloat() ?: items[i].fallbackHeight(metrics.constraintLineHeightPx, metrics)
                (height + if (items[i].hugsPrevious()) 0f else metrics.gapPx).toDouble()
            }.toFloat()
    return viewportHeightPx - topMarginPx - anchorY + totalAbove
}
