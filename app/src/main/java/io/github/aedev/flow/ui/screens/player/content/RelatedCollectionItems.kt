package io.github.aedev.flow.ui.screens.player.content

import androidx.compose.foundation.lazy.LazyListScope
import io.github.aedev.flow.data.model.RelatedCollection
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.shared.MediaShortsShelf

/** A run of the related lane: consecutive videos, or one collection between them. */
internal sealed interface RelatedLaneSegment {
    /** The videos at `start until start + count`. */
    data class Videos(
        val start: Int,
        val count: Int,
    ) : RelatedLaneSegment

    data class Collection(
        val collection: RelatedCollection,
    ) : RelatedLaneSegment
}

/**
 * The lane cut at each collection's position. A position past the end, left by a video the viewer
 * hid since, puts the collection after the last video.
 */
internal fun relatedLaneSegments(
    videoCount: Int,
    collections: List<RelatedCollection>,
): List<RelatedLaneSegment> {
    if (collections.isEmpty()) return if (videoCount > 0) listOf(RelatedLaneSegment.Videos(0, videoCount)) else emptyList()
    val segments = mutableListOf<RelatedLaneSegment>()
    var start = 0
    collections.sortedBy { it.position }.forEach { collection ->
        val at = collection.position.coerceIn(0, videoCount)
        if (at > start) segments += RelatedLaneSegment.Videos(start, at - start)
        start = maxOf(start, at)
        segments += RelatedLaneSegment.Collection(collection)
    }
    if (start < videoCount) segments += RelatedLaneSegment.Videos(start, videoCount - start)
    return segments
}

/** A playlist opens its page, a Mix included; a Short opens in the Shorts player through [onShortClick]. */
internal fun LazyListScope.relatedCollectionItem(
    collection: RelatedCollection,
    playlistMetrics: RelatedPlaylistMetrics,
    onPlaylistClick: (String) -> Unit,
    onShortClick: (Video) -> Unit,
) {
    when (collection) {
        is RelatedCollection.PlaylistCard -> {
            item(key = collection.key, contentType = "related_playlist") {
                KeyboardCardFocus {
                    RelatedPlaylistCard(card = collection, metrics = playlistMetrics, onClick = { onPlaylistClick(collection.playlist.id) })
                }
            }
        }

        is RelatedCollection.ShortsShelf -> {
            item(key = collection.key, contentType = "related_shorts") {
                MediaShortsShelf(
                    shorts = collection.shorts,
                    onShortClick = { _, tapped -> onShortClick(tapped) },
                    title = collection.title,
                )
            }
        }
    }
}
