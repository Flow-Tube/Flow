package io.github.aedev.flow.data.community

import android.util.Log
import io.github.aedev.flow.data.comments.OLDEST_PAGE_LIMIT
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.VideoCommentSort
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
    val sortOptions: List<VideoCommentSort> = emptyList(),
    val sortToken: String? = null,
    /** Oldest stopped at [OLDEST_PAGE_LIMIT] with older comments left unread. */
    val oldestCapped: Boolean = false,
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
                            sortOptions = page.sortOptions,
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

    /** Reloads the open post's comments in the order [sort] names, one of the post's own sort menu. */
    fun selectSort(sort: VideoCommentSort) {
        val postId = _state.value.activePost?.id ?: return
        if (_state.value.sortToken == sort.token) return
        _state.update {
            it.copy(
                comments = emptyList(),
                continuation = null,
                isLoading = true,
                isLoadingMore = false,
                sortToken = sort.token,
                oldestCapped = false,
            )
        }
        scope.launch(dispatcher) {
            fetchContinuation(sort.token).fold(
                onSuccess = { page ->
                    if (!_state.value.shows(postId, sort.token)) return@fold
                    _state.update {
                        it.copy(
                            comments = page.comments,
                            continuation = page.continuation,
                            isLoading = false,
                            sortOptions = page.sortOptions.ifEmpty { it.sortOptions },
                        )
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to sort community post comments", error)
                    if (_state.value.shows(postId, sort.token)) _state.update { it.copy(isLoading = false) }
                },
            )
        }
    }

    /**
     * Reads the post's comments in the order [newest] names to their end or [OLDEST_PAGE_LIMIT] pages,
     * one request at a time, for Oldest to turn around; nothing more pages in after that.
     */
    fun selectOldest(newest: VideoCommentSort) {
        val postId = _state.value.activePost?.id ?: return
        val key = OLDEST_KEY_PREFIX + newest.token
        if (_state.value.sortToken == key) return
        _state.update {
            it.copy(
                comments = emptyList(),
                continuation = null,
                isLoading = true,
                isLoadingMore = false,
                sortToken = key,
                oldestCapped = false,
            )
        }
        scope.launch(dispatcher) {
            var comments = emptyList<Comment>()
            var continuation: String? = newest.token
            var pages = 0
            var options = emptyList<VideoCommentSort>()
            while (continuation != null && pages < OLDEST_PAGE_LIMIT && _state.value.shows(postId, key)) {
                val result = fetchContinuation(continuation)
                result.exceptionOrNull()?.let { Log.e(TAG, "Failed to read community post comments to their oldest", it) }
                val page = result.getOrNull() ?: break
                comments = (comments + page.comments).distinctBy(Comment::id)
                if (options.isEmpty()) options = page.sortOptions
                continuation = page.continuation
                pages++
            }
            if (!_state.value.shows(postId, key)) return@launch
            _state.update {
                it.copy(
                    comments = comments,
                    isLoading = false,
                    sortOptions = options.ifEmpty { it.sortOptions },
                    oldestCapped = continuation != null,
                )
            }
        }
    }

    fun loadMore() {
        val continuation = _state.value.continuation ?: return
        val postId = _state.value.activePost?.id ?: return
        val sortToken = _state.value.sortToken
        if (_state.value.isLoadingMore) return

        _state.update { it.copy(isLoadingMore = true) }
        scope.launch(dispatcher) {
            fetchContinuation(continuation).fold(
                onSuccess = { page ->
                    if (!_state.value.shows(postId, sortToken)) return@fold
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
                    if (_state.value.shows(postId, sortToken)) _state.update { it.copy(isLoadingMore = false) }
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

    private fun PostCommentsState.shows(
        postId: String,
        sortToken: String?,
    ): Boolean = activePost?.id == postId && this.sortToken == sortToken

    private companion object {
        const val TAG = "PostComments"
        const val OLDEST_KEY_PREFIX = "oldest:"
    }
}
