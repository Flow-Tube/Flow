package io.github.aedev.flow.data.comments

import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.innertube.pages.VideoCommentSort

/**
 * One page of comments, the continuation that loads the next one, and the orders the section offers.
 *
 * [disabled] marks a video whose uploader turned comments off, as opposed to one nobody has
 * commented on yet.
 */
data class CommentsPageResult(
    val comments: List<Comment> = emptyList(),
    val continuation: String? = null,
    val sortOptions: List<VideoCommentSort> = emptyList(),
    val totalText: String? = null,
    val totalCount: Long? = null,
    val disabled: Boolean = false,
) {
    val hasMore: Boolean get() = continuation != null

    companion object {
        val EMPTY = CommentsPageResult()
        val DISABLED = CommentsPageResult(disabled = true)
    }
}
