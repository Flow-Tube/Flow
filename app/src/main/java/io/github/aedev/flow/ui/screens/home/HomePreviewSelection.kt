package io.github.aedev.flow.ui.screens.home

/** One laid-out grid item, in the grid's own pixel coordinates along the scroll axis. */
internal data class PreviewSlot(
    val key: Any,
    val top: Int,
    val height: Int,
)

/** The part of the viewport that shows content: below the top padding, above the bottom one. */
internal data class PreviewWindow(
    val top: Int,
    val bottom: Int,
) {
    val height: Int get() = bottom - top
}

internal object HomePreviewSelection {
    const val MIN_VISIBLE_FRACTION = 0.8f
    const val TOP_REGION_FRACTION = 0.4f

    /** A preview already playing keeps going while the list moves, until it is less visible than this. */
    const val KEEP_VISIBLE_FRACTION = 0.5f

    /**
     * The card to preview: the first [previewable] slot at least 80 % visible whose top edge lies in
     * the top 40 % of [window], as the reference picks. While [scrolling] no new card is picked, so a
     * fling never starts one; the [current] card is kept as long as half of it stays in view.
     */
    fun select(
        slots: List<PreviewSlot>,
        window: PreviewWindow,
        scrolling: Boolean,
        current: Any?,
        previewable: (Any) -> Boolean,
    ): Any? {
        if (window.height <= 0) return null
        if (scrolling) {
            val kept = slots.firstOrNull { it.key == current } ?: return null
            return current.takeIf { visibleFraction(kept, window) >= KEEP_VISIBLE_FRACTION }
        }
        val regionBottom = window.top + window.height * TOP_REGION_FRACTION
        return slots
            .asSequence()
            .filter { previewable(it.key) }
            .filter { it.top < regionBottom && visibleFraction(it, window) >= MIN_VISIBLE_FRACTION }
            .minByOrNull { it.top }
            ?.key
    }

    fun visibleFraction(
        slot: PreviewSlot,
        window: PreviewWindow,
    ): Float {
        if (slot.height <= 0) return 0f
        val shown = minOf(slot.top + slot.height, window.bottom) - maxOf(slot.top, window.top)
        return (shown.coerceAtLeast(0).toFloat() / slot.height).coerceAtMost(1f)
    }
}
