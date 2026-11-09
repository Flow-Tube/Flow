package io.github.aedev.flow.data.community

import android.util.Log
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.renderer.CommunityCommentsPage
import io.github.aedev.flow.innertube.pages.renderer.CommunityPost
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PostCommentsState(
    val activePost: CommunityPost? = null,
    val comments: List<Comment> = emptyList(),
    val continuation: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
)

/** The comments of one open community post, with paging and reply threads, for any screen that shows posts. */
class PostCommentsLoader(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = PerformanceDispatcher.networkIO,
    private val fetchFirstPage: suspend (postId: String, params: String?) -> Result<CommunityCommentsPage> =
        YouTube::communityPostComments,
    private val fetchContinuation: suspend (continuation: String) -> Result<CommunityCommentsPage> =
        YouTube::communityPostCommentsContinuation,
) {
    private val _state = MutableStateFlow(PostCommentsState())
    val state: StateFlow<PostCommentsState> = _state.asStateFlow()

    fun open(post: CommunityPost) {
        _state.value = PostCommentsState(activePost = post, isLoading = true)
        scope.launch(dispatcher) {
            fetchFirstPage(post.id, post.commentEndpointParams).fold(
                onSuccess = { page ->
                    if (_state.value.activePost?.id != post.id) return@fold
                    _state.update {
                        it.copy(
                            comments = page.comments,
                            continuation = page.continuation,
                            isLoading = false,
                        )
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load community post comments", error)
                    if (_state.value.activePost?.id == post.id) {
                        _state.update { it.copy(isLoading = false) }
                    }
                },
            )
        }
    }

    fun close() {
        _state.value = PostCommentsState()
    }

    fun loadMore() {
        val continuation = _state.value.continuation ?: return
        val postId = _state.value.activePost?.id ?: return
        if (_state.value.isLoadingMore) return

        scope.launch(dispatcher) {
            _state.update { it.copy(isLoadingMore = true) }
            fetchContinuation(continuation).fold(
                onSuccess = { page ->
                    if (_state.value.activePost?.id != postId) return@fold
                    _state.update {
                        it.copy(
                            comments = (it.comments + page.comments).distinctBy(Comment::id),
                            continuation = page.continuation,
                            isLoadingMore = false,
                        )
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load more community post comments", error)
                    _state.update { it.copy(isLoadingMore = false) }
                },
            )
        }
    }

    fun loadReplies(comment: Comment) = loadReplies(comment, append = false)

    fun loadMoreReplies(comment: Comment) = loadReplies(comment, append = true)

    private fun loadReplies(
        comment: Comment,
        append: Boolean,
    ) {
        val continuation = comment.continuationToken ?: return
        scope.launch(dispatcher) {
            fetchContinuation(continuation).fold(
                onSuccess = { page ->
                    _state.update { state ->
                        state.copy(
                            comments =
                                state.comments.map { current ->
                                    if (current.id != comment.id) return@map current
                                    val replies =
                                        if (append) {
                                            (current.replies + page.comments).distinctBy(Comment::id)
                                        } else {
                                            page.comments
                                        }
                                    current.copy(
                                        replies = replies,
                                        continuationToken = page.continuation,
                                        replyCount = maxOf(current.replyCount, replies.size),
                                    )
                                },
                        )
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load community comment replies", error)
                },
            )
        }
    }

    private companion object {
        const val TAG = "PostComments"
    }
}
