package io.github.aedev.flow.data.video.downloader.collection

import io.github.aedev.flow.data.local.entity.CollectionItemState
import io.github.aedev.flow.data.local.entity.DownloadCollectionItemEntity
import io.github.aedev.flow.data.local.entity.DownloadCollectionKind

/** A playlist or album as the page that downloads it describes it. */
data class CollectionSpec(
    val id: String,
    val kind: DownloadCollectionKind,
    val title: String,
    val author: String? = null,
    val authorId: String? = null,
    val thumbnailUrl: String? = null,
    /** A playlist of the running profile's own, rather than one from YouTube; see [CollectionOwners]. */
    val ownPlaylist: Boolean = kind == DownloadCollectionKind.LOCAL_PLAYLIST,
)

/** What one download of a collection's current list changes in its membership. Pure, so it is unit tested. */
internal data class CollectionPlan(
    /** Members to write, with their position in the list as it is now. */
    val members: List<DownloadCollectionItemEntity>,
    /** Videos to queue: new to the collection, or wanted and not yet downloaded. */
    val toQueue: List<String>,
) {
    companion object {
        /**
         * [ids] is the list as loaded now. A video the user took out stays out; one no longer in a
         * list that was [complete] is marked as removed upstream, never deleted, and a truncated
         * load marks nothing, since what it did not reach may still be there. With [picked], the
         * listed videos outside it are excluded and those in it wanted again.
         */
        fun of(
            collectionId: String,
            ids: List<String>,
            existing: List<DownloadCollectionItemEntity>,
            isDownloaded: (String) -> Boolean,
            complete: Boolean,
            now: Long,
            picked: Set<String>? = null,
        ): CollectionPlan {
            val known = existing.associateBy { it.videoId }
            val listed = ids.distinct()
            val listedSet = listed.toSet()
            val members =
                listed.mapIndexed { position, videoId ->
                    val previous = known[videoId]
                    val choice = picked?.let { if (videoId in it) CollectionItemState.WANTED else CollectionItemState.EXCLUDED }
                    when {
                        previous == null -> {
                            val state = choice ?: CollectionItemState.WANTED
                            DownloadCollectionItemEntity(
                                collectionId = collectionId,
                                videoId = videoId,
                                position = position,
                                addedAt = now,
                                state = state,
                                ownsDownload = state == CollectionItemState.WANTED && !isDownloaded(videoId),
                            )
                        }

                        previous.state == CollectionItemState.REMOVED_UPSTREAM -> {
                            previous.copy(position = position, state = choice ?: CollectionItemState.WANTED)
                        }

                        previous.state == CollectionItemState.EXCLUDED && choice == CollectionItemState.WANTED -> {
                            previous.copy(
                                position = position,
                                state = CollectionItemState.WANTED,
                                ownsDownload = previous.ownsDownload || !isDownloaded(videoId),
                            )
                        }

                        else -> {
                            previous.copy(position = position, state = choice ?: previous.state)
                        }
                    }
                }
            val gone =
                if (complete) {
                    existing
                        .filter { it.videoId !in listedSet && it.state == CollectionItemState.WANTED }
                        .map { it.copy(state = CollectionItemState.REMOVED_UPSTREAM) }
                } else {
                    emptyList()
                }
            val toQueue =
                members
                    .filter { it.state == CollectionItemState.WANTED && !isDownloaded(it.videoId) }
                    .map { it.videoId }
            return CollectionPlan(members + gone, toQueue)
        }
    }
}
