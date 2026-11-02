package io.github.aedev.flow.ui.screens.home

import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo

/**
 * Decides when a Home card counts as seen: once it has stayed at least half on screen for [dwellMs],
 * whether or not the list is still moving. A card that leaves and comes back starts over; the engine
 * decides how often repeat sightings count.
 */
internal class ImpressionDwell(
    private val dwellMs: Long = IMPRESSION_DWELL_MS,
) {
    private val visibleSince = HashMap<String, Long>()
    private val recorded = HashSet<String>()

    /** Takes the keys on screen now and returns those that just reached the dwell time. */
    fun update(
        visible: Collection<String>,
        now: Long,
    ): List<String> {
        visibleSince.keys.retainAll(visible.toSet())
        recorded.retainAll(visibleSince.keys)
        visible.forEach { visibleSince.putIfAbsent(it, now) }
        return visibleSince
            .filter { (key, since) -> key !in recorded && now - since >= dwellMs }
            .keys
            .onEach(recorded::add)
            .toList()
    }

    /** How long until the next card on screen reaches the dwell time, or null when none is waiting. */
    fun millisUntilNextDue(now: Long): Long? =
        visibleSince
            .filterKeys { it !in recorded }
            .values
            .minOfOrNull { since -> (since + dwellMs - now).coerceAtLeast(0L) }
}

/** Keys of the grid items with at least half their height inside the viewport. */
internal fun LazyGridLayoutInfo.halfVisibleKeys(): List<String> {
    val top = viewportStartOffset
    val bottom = viewportEndOffset
    return visibleItemsInfo.mapNotNull { item ->
        val key = item.key as? String ?: return@mapNotNull null
        val height = item.size.height
        if (height <= 0) return@mapNotNull null
        val shown = minOf(bottom, item.offset.y + height) - maxOf(top, item.offset.y)
        key.takeIf { shown * 2 >= height }
    }
}

internal const val IMPRESSION_DWELL_MS = 1_000L
