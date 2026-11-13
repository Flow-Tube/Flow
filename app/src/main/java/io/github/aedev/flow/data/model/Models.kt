package io.github.aedev.flow.data.model

import kotlinx.serialization.Serializable

@Serializable
data class VideoCollaborator(
    val name: String,
    val channelId: String = "",
    val thumbnailUrl: String = "",
    val subscriberCountText: String = "",
)

data class Video(
    val id: String,
    val title: String,
    val channelName: String,
    val channelId: String,
    val thumbnailUrl: String,
    val duration: Int, // in seconds
    val viewCount: Long,
    val likeCount: Long = 0,
    val uploadDate: String,
    val timestamp: Long = System.currentTimeMillis(),
    // True only when [timestamp] is a real publish time (RSS); list responses carry only "3 weeks ago".
    val timestampIsExact: Boolean = false,
    val description: String = "",
    val channelThumbnailUrl: String = "",
    val tags: List<String> = emptyList(),
    // YouTube's category, such as "Music"; kept out of [tags] so it never reads as a creator keyword.
    val category: String? = null,
    val isMusic: Boolean = false,
    val isLive: Boolean = false,
    val isShort: Boolean = false,
    val isUpcoming: Boolean = false,
    // An upcoming row that is a scheduled live stream rather than a premiere.
    val isScheduledLive: Boolean = false,
    // YouTube's own label, so it arrives translated; null on an ordinary video.
    val membersOnlyText: String? = null,
    val commentCountText: String = "",
    val channelThumbnailUrls: List<String> = emptyList(),
    val collaborators: List<VideoCollaborator> = emptyList(),
    val isVerifiedChannel: Boolean = false,
    // YouTube's own pills on a search result — "4K", "CC", "New" — already localised.
    val badges: List<String> = emptyList(),
    // The sentence from the description or transcript that matched the query, on search results only.
    val snippet: String = "",
    val snippetHighlights: List<IntRange> = emptyList(),
    // Transient: when this video was added to the playlist currently being viewed. Not persisted
    // on the video row — populated only by playlist-scoped queries.
    val addedAtInPlaylist: Long? = null,
)

data class Channel(
    val id: String,
    val name: String,
    val thumbnailUrl: String,
    val subscriberCount: Long,
    val description: String = "",
    val isSubscribed: Boolean = false,
    val isMusic: Boolean = false,
    val handle: String = "",
    val videoCount: Int = 0,
    val isVerified: Boolean = false,
    // Full channel URL for navigation
    val url: String = "",
)

data class Playlist(
    val id: String,
    val name: String,
    val thumbnailUrl: String,
    val videoCount: Int,
    val description: String = "",
    val videos: List<Video> = emptyList(),
    val isLocal: Boolean = true,
    val isCourse: Boolean = false,
)

data class Comment(
    val id: String,
    val author: String,
    val authorThumbnail: String,
    val text: String,
    val likeCount: Int,
    val publishedTime: String,
    val replies: List<Comment> = emptyList(),
    val replyCount: Int = 0,
    val isPinned: Boolean = false,
    val continuationToken: String? = null,
    val authorChannelId: String = "",
    val richText: RichText? = null,
    val likeCountText: String = "",
    val pinnedByText: String? = null,
    val isHearted: Boolean = false,
    val heartedByText: String? = null,
    val isVerified: Boolean = false,
    val isCreator: Boolean = false,
    val isArtist: Boolean = false,
    val image: CommentImage? = null,
)

/** A GIF or picture attached to a comment, with the size YouTube gives for it. */
data class CommentImage(
    val url: String,
    val width: Int,
    val height: Int,
)

enum class SearchFilter {
    ALL,
    VIDEOS,
    CHANNELS,
    PLAYLISTS,
}
