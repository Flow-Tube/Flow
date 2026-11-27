package io.github.aedev.flow.ui.screens.categories

import io.github.aedev.flow.data.local.ExploreStartCategory
import io.github.aedev.flow.innertube.pages.explore.ExploreDestination

/**
 * The category Explore opens on. A name no tab carries any more, from an older build or a removed
 * destination, falls back to the first tab rather than to an empty screen.
 */
internal fun exploreStartDestination(
    start: ExploreStartCategory,
    lastVisited: String?,
): ExploreDestination {
    val wanted = start.destination?.name ?: lastVisited
    return CATEGORY_TABS.firstOrNull { it.destination.name == wanted }?.destination
        ?: CATEGORY_TABS.first().destination
}
