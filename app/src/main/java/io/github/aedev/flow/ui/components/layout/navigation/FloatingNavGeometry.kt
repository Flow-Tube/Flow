package io.github.aedev.flow.ui.components.layout.navigation

import io.github.aedev.flow.data.local.NavBarLabels

/** Item sizes of the floating bar, in px. */
internal data class FloatingNavMetrics(
    val iconItem: Float,
    val icon: Float,
    val labelledStart: Float,
    val iconLabelGap: Float,
    val labelledEnd: Float,
    val namedItemMin: Float,
    val namedItemPadding: Float,
    val plainItem: Float,
    val gap: Float,
    val belowIndicatorWidth: Float,
)

/** Where a tab's name sits. */
internal enum class FloatingNavNames {
    /** Only the open tab is named, beside its icon. */
    BESIDE_OPEN,

    /** Every tab is named under its icon. */
    BELOW,

    /** No names. */
    NONE,
}

/** The bar at rest: each item's width and start, and the indicator's span over the open tab. */
internal data class FloatingNavGeometry(
    val widths: List<Float>,
    val starts: List<Float>,
    val names: FloatingNavNames,
    val width: Float,
    val indicatorStart: Float,
    val indicatorEnd: Float,
) {
    /** The item whose centre is nearest [x], ignoring items folded away. */
    fun itemAt(x: Float): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        widths.forEachIndexed { index, width ->
            if (width <= 0f) return@forEachIndexed
            val distance = kotlin.math.abs(x - (starts[index] + width / 2f))
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    /** The indicator's span when it sits on [index]. */
    fun indicatorSpan(
        index: Int,
        metrics: FloatingNavMetrics,
    ): ClosedFloatingPointRange<Float> {
        val start = starts[index]
        val width = widths[index]
        if (names != FloatingNavNames.BELOW) return start..(start + width)
        val centre = start + width / 2f
        return (centre - metrics.belowIndicatorWidth / 2f)..(centre + metrics.belowIndicatorWidth / 2f)
    }
}

/**
 * Lays out [nameWidths.size] tabs with [open] open inside [maxWidth]. The open tab's name goes beside its
 * icon when [labels] allows it and it fits, else the tab shows its icon alone. [collapsed] folds the bar
 * down to the open tab's icon.
 */
internal fun floatingNavGeometry(
    nameWidths: List<Float>,
    open: Int,
    labels: NavBarLabels,
    maxWidth: Float,
    collapsed: Boolean,
    metrics: FloatingNavMetrics,
): FloatingNavGeometry {
    val count = nameWidths.size
    val (widths, names) =
        when {
            collapsed -> List(count) { if (it == open) metrics.iconItem else 0f } to FloatingNavNames.NONE
            labels == NavBarLabels.ALWAYS -> namedBelow(nameWidths, maxWidth, metrics) to FloatingNavNames.BELOW
            labels == NavBarLabels.SELECTED -> nameBesideOpen(nameWidths, open, maxWidth, metrics)
            else -> plain(count, maxWidth, metrics) to FloatingNavNames.NONE
        }
    val starts = ArrayList<Float>(count)
    var x = 0f
    widths.forEachIndexed { index, width ->
        starts += x
        x += width
        if (index < count - 1 && width > 0f && widths.drop(index + 1).any { it > 0f }) x += metrics.gap
    }
    val geometry = FloatingNavGeometry(widths, starts, names, x, 0f, 0f)
    val span = geometry.indicatorSpan(open.coerceIn(0, count - 1), metrics)
    return geometry.copy(indicatorStart = span.start, indicatorEnd = span.endInclusive)
}

private fun totalWidth(
    widths: List<Float>,
    metrics: FloatingNavMetrics,
): Float = widths.sum() + metrics.gap * (widths.size - 1).coerceAtLeast(0)

private fun nameBesideOpen(
    nameWidths: List<Float>,
    open: Int,
    maxWidth: Float,
    metrics: FloatingNavMetrics,
): Pair<List<Float>, FloatingNavNames> {
    val openWidth = metrics.labelledStart + metrics.icon + metrics.iconLabelGap + nameWidths[open] + metrics.labelledEnd
    val named = nameWidths.indices.map { if (it == open) openWidth else metrics.iconItem }
    if (totalWidth(named, metrics) <= maxWidth) return named to FloatingNavNames.BESIDE_OPEN
    return plain(nameWidths.size, maxWidth, metrics) to FloatingNavNames.NONE
}

private fun namedBelow(
    nameWidths: List<Float>,
    maxWidth: Float,
    metrics: FloatingNavMetrics,
): List<Float> {
    val natural = nameWidths.map { maxOf(metrics.namedItemMin, it + metrics.namedItemPadding) }
    if (totalWidth(natural, metrics) <= maxWidth) return natural
    val share = (maxWidth - metrics.gap * (nameWidths.size - 1)) / nameWidths.size
    return List(nameWidths.size) { maxOf(metrics.iconItem, share) }
}

private fun plain(
    count: Int,
    maxWidth: Float,
    metrics: FloatingNavMetrics,
): List<Float> {
    val roomy = List(count) { metrics.plainItem }
    return if (totalWidth(roomy, metrics) <= maxWidth) roomy else List(count) { metrics.iconItem }
}
