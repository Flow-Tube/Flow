package io.github.aedev.flow.data.model

/**
 * One page of a watch page's related lane, in YouTube's order. [upNextVideoId] is the video YouTube
 * would play next, which need not be the first one listed; [continuation] asks for the next page.
 */
data class WatchRelatedPage(
    val videos: List<Video>,
    val upNextVideoId: String? = null,
    val continuation: String? = null,
    val collections: List<RelatedCollection> = emptyList(),
)

/**
 * A related entry that is not a single video. [position] counts the videos listed before it: on a
 * page, the page's own; in the lane, the lane's, so it sits before the video at that index.
 * Autoplay never reads these.
 */
sealed interface RelatedCollection {
    val position: Int

    /** Unique in the lane and apart from every video id, so it can key a lazy list item. */
    val key: String

    fun at(position: Int): RelatedCollection

    data class PlaylistCard(
        override val position: Int,
        val playlist: Playlist,
    ) : RelatedCollection {
        override val key: String get() = "related_playlist:${playlist.id}"

        override fun at(position: Int) = copy(position = position)
    }

    data class ShortsShelf(
        override val position: Int,
        val title: String?,
        val shorts: List<Video>,
    ) : RelatedCollection {
        override val key: String get() = "related_shorts:${shorts.firstOrNull()?.id}"

        override fun at(position: Int) = copy(position = position)
    }
}
