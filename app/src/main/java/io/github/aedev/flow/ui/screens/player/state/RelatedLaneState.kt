package io.github.aedev.flow.ui.screens.player.state

import io.github.aedev.flow.data.model.RelatedCollection
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FeedExclusions
import io.github.aedev.flow.player.PlayerRelatedVideosPolicy

/** Whether the lane still waits on [continuation] for [videoId], so a late page can land. */
internal fun VideoPlayerUiState.awaitsRelatedPage(
    videoId: String,
    continuation: String,
): Boolean = cachedVideo?.id == videoId && relatedContinuation == continuation

/**
 * A further related page behind the lane. A video an earlier page already listed keeps its place,
 * and [continuation] replaces the one that was spent. [listed] is the page as YouTube sent it,
 * [kept] what survived the filters.
 */
internal fun VideoPlayerUiState.appendRelatedPage(
    listed: List<Video>,
    kept: List<Video>,
    collections: List<RelatedCollection>,
    continuation: String?,
): VideoPlayerUiState {
    val shown = relatedVideos.mapTo(HashSet()) { it.id }
    val fresh = kept.filterNot { it.id in shown }
    val placed = RelatedLanePlacement.place(listed, fresh, collections, upNextVideoId = null, offset = relatedVideos.size)
    return copy(
        relatedVideos = relatedVideos + fresh,
        relatedCollections = (relatedCollections + placed).distinctBy { it.key },
        relatedContinuation = continuation,
        relatedAppend = RelatedAppend.IDLE,
    )
}

internal object RelatedLanePlacement {
    /**
     * Each collection moved to the lane's index space: it stays behind the videos YouTube listed
     * before it that are still shown, plus YouTube's pick, which the lane moved to the top.
     */
    fun place(
        listed: List<Video>,
        shown: List<Video>,
        collections: List<RelatedCollection>,
        upNextVideoId: String?,
        offset: Int = 0,
    ): List<RelatedCollection> {
        if (collections.isEmpty()) return emptyList()
        val listedAt = listed.withIndex().associate { (index, video) -> video.id to index }
        return collections.distinctBy { it.key }.map { collection ->
            collection.at(offset + shown.count { it.id == upNextVideoId || (listedAt[it.id] ?: Int.MAX_VALUE) < collection.position })
        }
    }

    /** The Shorts shelf follows the hide-Shorts switch and the viewer's hidden videos; playlists stay. */
    fun visible(
        videoId: String,
        collections: List<RelatedCollection>,
        shortsEnabled: Boolean,
        exclusions: FeedExclusions,
    ): List<RelatedCollection> =
        collections.mapNotNull { collection ->
            when (collection) {
                is RelatedCollection.PlaylistCard -> {
                    collection
                }

                is RelatedCollection.ShortsShelf -> {
                    if (!shortsEnabled) return@mapNotNull null
                    collection
                        .copy(shorts = PlayerRelatedVideosPolicy.sanitize(videoId, collection.shorts, exclusions = exclusions))
                        .takeIf { it.shorts.isNotEmpty() }
                }
            }
        }
}
