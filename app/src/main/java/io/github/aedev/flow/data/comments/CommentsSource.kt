package io.github.aedev.flow.data.comments

import io.github.aedev.flow.data.repository.YouTubeRepository

/** Where a [CommentsPager] reads one video's comments from. */
internal interface CommentsSource {
    /** The first page in the order [sortToken] names, or the section's own default when it is null. */
    suspend fun firstPage(
        videoId: String,
        sortToken: String?,
    ): CommentsPageResult

    suspend fun nextPage(
        videoId: String,
        continuation: String,
    ): CommentsPageResult

    suspend fun replies(
        videoId: String,
        continuation: String,
    ): CommentsPageResult

    /** YouTube's own Timed list, read through any token of the section; empty when there is none. */
    suspend fun timed(
        videoId: String,
        sectionToken: String,
    ): CommentsPageResult
}

/** A video's comment section, opened from the watch response the player already holds. */
internal class VideoCommentsSource(
    private val repository: YouTubeRepository,
) : CommentsSource {
    override suspend fun firstPage(
        videoId: String,
        sortToken: String?,
    ) = repository.getVideoComments(videoId, sortToken)

    override suspend fun nextPage(
        videoId: String,
        continuation: String,
    ) = repository.getMoreVideoComments(videoId, continuation)

    override suspend fun replies(
        videoId: String,
        continuation: String,
    ) = repository.getVideoCommentReplies(videoId, continuation)

    override suspend fun timed(
        videoId: String,
        sectionToken: String,
    ) = repository.getTimedComments(videoId, sectionToken)
}
