package io.github.aedev.flow.innertube.models

import kotlinx.serialization.Serializable

/**
 * A wide row with a title over a second, linked title.
 * Used in [MusicCarouselShelfRenderer] for podcast episodes, whose second title links the show.
 */
@Serializable
data class MusicMultiRowListItemRenderer(
    val title: Runs,
    val secondTitle: Runs?,
    val thumbnail: ThumbnailRenderer?,
    val onTap: NavigationEndpoint?,
)
