package io.github.aedev.flow.innertube.pages.renderer

import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.Video

/** Lockups carry no byline and continuations carry no header, so the caller threads this forward. */
data class FeedItemOwner(
    val id: String = "",
    val name: String = "",
    val avatarUrl: String = "",
    val handle: String? = null,
)

data class FeedShelf(
    val id: String,
    val title: String? = null,
    val subtitle: String? = null,
    val style: FeedShelfStyle = FeedShelfStyle.Carousel,
    val items: List<FeedItem> = emptyList(),
    /** How many items the shelf shows before its "show more" affordance; null when it shows them all. */
    val collapsedItemCount: Int? = null,
    val moreParams: String? = null,
    val morePlaylistId: String? = null,
)

enum class FeedShelfStyle {
    Trailer,
    Carousel,
    Grid,
    List,
}

sealed interface FeedItem {
    data class VideoItem(
        val video: Video,
    ) : FeedItem

    data class ShortItem(
        val video: Video,
    ) : FeedItem

    data class PlaylistItem(
        val playlist: Playlist,
    ) : FeedItem

    data class RelatedChannelItem(
        val channel: Channel,
    ) : FeedItem

    data class PostItem(
        val post: CommunityPost,
    ) : FeedItem
}

internal fun FeedItem.distinctKey(): String =
    when (this) {
        is FeedItem.VideoItem -> "v:${video.id}"
        is FeedItem.ShortItem -> "s:${video.id}"
        is FeedItem.PlaylistItem -> "p:${playlist.id}"
        is FeedItem.RelatedChannelItem -> "c:${channel.id}"
        is FeedItem.PostItem -> "b:${post.id}"
    }

internal fun FeedItem.videoOrNull(): Video? =
    when (this) {
        is FeedItem.VideoItem -> video
        is FeedItem.ShortItem -> video
        else -> null
    }
