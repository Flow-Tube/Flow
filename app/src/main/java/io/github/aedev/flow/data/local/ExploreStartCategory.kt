package io.github.aedev.flow.data.local

import io.github.aedev.flow.innertube.pages.explore.ExploreDestination

/** Which category Explore opens on: the one last visited, or always the same one. */
enum class ExploreStartCategory(
    val destination: ExploreDestination?,
) {
    LAST(null),
    LIVE(ExploreDestination.LIVE),
    GAMING(ExploreDestination.GAMING),
    MUSIC(ExploreDestination.MUSIC),
    NEWS(ExploreDestination.NEWS),
    SPORTS(ExploreDestination.SPORTS),
    LEARNING(ExploreDestination.LEARNING),
    FASHION(ExploreDestination.FASHION),
    ;

    companion object {
        fun fromString(value: String?): ExploreStartCategory = entries.firstOrNull { it.name == value } ?: LAST
    }
}
