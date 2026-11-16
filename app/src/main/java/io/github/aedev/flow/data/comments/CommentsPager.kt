package io.github.aedev.flow.data.comments

import android.util.Log
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.model.distinctByNonBlankKey
import io.github.aedev.flow.data.model.mergeDistinctByNonBlankKey
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.innertube.pages.VideoCommentSort
import io.github.aedev.flow.player.PlaybackStartupPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "CommentsPager"

/** How long a comment fetch waits for playback to stop competing with it before giving up. */
private const val STARTUP_GATE_TIMEOUT_MS = 20_000L

/**
 * Pages fetched behind the first one, so a filter that reads the whole loaded set has something to
 * read. Bounded and sequential: each page waits for the one before it, and a user-driven load more
 * or a sort change cancels what is left.
 */
private const val DEFAULT_PREFETCH_PAGES = 3

/**
 * Pages Timed reads per order when it opens, and per order on each tap of Load more. Timed never
 * pages by itself past this: only a few comments in a hundred carry a timestamp, so following the
 * list's end could walk a video's whole comment section.
 */
private const val TIMED_PAGES_PER_ORDER = 8
private const val TIMED_MORE_PAGES_PER_ORDER = 2

/** What the pager needs to know about the surface's playback before it spends the network on comments. */
internal data class CommentsPlaybackState(
    val isPlaybackLoading: Boolean,
    val currentVideoId: String?,
) {
    companion object {
        /** For a surface whose playback never competes with the comment fetch. */
        val READY = CommentsPlaybackState(isPlaybackLoading = false, currentVideoId = null)
    }
}

/**
 * The comment list for one video: first page, further pages, and a comment's replies.
 *
 * The player and Shorts each had their own copy of this and drifted — only one of them deduplicated
 * by comment id, and neither guarded against a second request landing while the first was still in
 * flight, so re-opening the sheet fetched page one twice.
 *
 * One request per (video, page) is in flight at a time. A request for a different video supersedes
 * the one before it; a repeat of the request already running is dropped.
 *
 * [isCurrentVideo] is the owner's decision about whether the answer still belongs on screen, and it
 * is asked both before and after the fetch, because the fetch outlives a fast swipe to the next
 * video. [fetchTimeoutMs] bounds the fetch itself for a surface that wants one.
 */
internal class CommentsPager(
    private val repository: YouTubeRepository,
    private val scope: CoroutineScope,
    private val playbackState: Flow<CommentsPlaybackState> = flowOf(CommentsPlaybackState.READY),
    private val isCurrentVideo: (String) -> Boolean = { true },
    private val fetchTimeoutMs: Long? = null,
    private val prefetchPages: Int = DEFAULT_PREFETCH_PAGES,
) {
    private val _comments = MutableStateFlow<List<Comment>>(emptyList())
    val comments: StateFlow<List<Comment>> = _comments.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _sortOptions = MutableStateFlow<List<VideoCommentSort>>(emptyList())
    val sortOptions: StateFlow<List<VideoCommentSort>> = _sortOptions.asStateFlow()

    private val _totalText = MutableStateFlow<String?>(null)
    val totalText: StateFlow<String?> = _totalText.asStateFlow()

    private val _totalCount = MutableStateFlow<Long?>(null)
    val totalCount: StateFlow<Long?> = _totalCount.asStateFlow()

    private val _isDisabled = MutableStateFlow(false)
    val isDisabled: StateFlow<Boolean> = _isDisabled.asStateFlow()

    private var next: CommentsPageResult = CommentsPageResult.EMPTY
    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    private var loadingVideoId: String? = null
    private var activeSortToken: String? = null
    private var timedCursors: MutableList<CommentsPageResult> = mutableListOf()
    private var timedFromYouTube = false
    private var timedGeneration = 0
    private val repliesInFlight = mutableSetOf<String>()

    /** Empties the list, for a video with no comments to fetch and for a player being torn down. */
    fun clear() {
        loadJob?.cancel()
        prefetchJob?.cancel()
        loadJob = null
        prefetchJob = null
        loadingVideoId = null
        activeSortToken = null
        next = CommentsPageResult.EMPTY
        timedCursors = mutableListOf()
        timedFromYouTube = false
        timedGeneration++
        _comments.value = emptyList()
        _isLoading.value = false
        _hasMore.value = false
        _isLoadingMore.value = false
        _sortOptions.value = emptyList()
        _totalText.value = null
        _totalCount.value = null
        _isDisabled.value = false
    }

    fun load(videoId: String) {
        if (loadingVideoId == videoId && activeSortToken == null && loadJob?.isActive == true) return
        startLoad(videoId, sortToken = null)
    }

    /** Reloads the section in the order [sort] names, which is a different continuation entirely. */
    fun selectSort(
        videoId: String,
        sort: VideoCommentSort,
    ) {
        if (activeSortToken == sort.token && loadJob?.isActive == true) return
        startLoad(videoId, sortToken = sort.token)
    }

    /**
     * Timed: YouTube's own Timed list, complete in one request. When YouTube gives none, [orders] are
     * read in turn into one list instead, since no order has the timestamped comments on every video.
     * One page at a time, and any other sort or video cancels what is left.
     */
    fun selectTimed(
        videoId: String,
        orders: List<VideoCommentSort>,
    ) {
        if (orders.isEmpty()) return
        val key = orders.joinToString(separator = "|") { it.token }
        if (activeSortToken == key && loadingVideoId == videoId && (timedCursors.isNotEmpty() || timedFromYouTube)) return
        resetForLoad(videoId, key)
        timedCursors = orders.mapTo(mutableListOf()) { CommentsPageResult(continuation = it.token) }
        walkTimed(videoId, TIMED_PAGES_PER_ORDER, youTubeListToken = orders.first().token)
    }

    private fun walkTimed(
        videoId: String,
        pagesPerOrder: Int,
        youTubeListToken: String? = null,
    ) {
        val generation = ++timedGeneration
        loadJob =
            scope.launch {
                try {
                    awaitPlaybackGate(videoId)
                    if (youTubeListToken != null && readYouTubeTimedList(videoId, youTubeListToken)) return@launch
                    repeat(pagesPerOrder) {
                        timedCursors.indices.forEach { index ->
                            val cursor = timedCursors[index]
                            if (!cursor.hasMore || !isCurrentVideo(videoId)) return@forEach
                            val page = fetch { fetchNextPage(videoId, cursor) } ?: return@launch
                            if (!isCurrentVideo(videoId)) return@launch
                            timedCursors[index] = page
                            _comments.value = _comments.value.mergeDistinctByNonBlankKey(page.comments, Comment::id)
                            _hasMore.value = timedCursors.any { it.hasMore }
                            _isLoading.value = false
                            _isLoadingMore.value = true
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading timed comments", e)
                } finally {
                    if (generation == timedGeneration) {
                        _isLoading.value = false
                        _isLoadingMore.value = false
                    }
                }
            }
    }

    /** True when YouTube's Timed list came back and is now the whole list. */
    private suspend fun readYouTubeTimedList(
        videoId: String,
        sectionToken: String,
    ): Boolean {
        if (!isCurrentVideo(videoId)) return false
        val timed = fetch { repository.getTimedComments(videoId, sectionToken) }?.comments.orEmpty()
        if (timed.isEmpty() || !isCurrentVideo(videoId)) return false
        timedCursors = mutableListOf()
        timedFromYouTube = true
        _comments.value = timed.distinctByNonBlankKey(Comment::id)
        _hasMore.value = false
        return true
    }

    private fun startLoad(
        videoId: String,
        sortToken: String?,
    ) {
        resetForLoad(videoId, sortToken)
        loadJob =
            scope.launch {
                try {
                    awaitPlaybackGate(videoId)
                    if (!isCurrentVideo(videoId)) return@launch
                    val page = fetch { repository.getVideoComments(videoId, sortToken) } ?: return@launch
                    if (!isCurrentVideo(videoId)) return@launch
                    _comments.value = page.comments.distinctByNonBlankKey(Comment::id)
                    next = page
                    _hasMore.value = page.hasMore
                    if (page.sortOptions.isNotEmpty()) _sortOptions.value = page.sortOptions
                    page.totalText?.let { _totalText.value = it }
                    page.totalCount?.let { _totalCount.value = it }
                    _isDisabled.value = page.disabled
                    prefetchFollowingPages(videoId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading comments", e)
                } finally {
                    if (loadingVideoId == videoId) _isLoading.value = false
                }
            }
    }

    private fun resetForLoad(
        videoId: String,
        sortToken: String?,
    ) {
        loadJob?.cancel()
        prefetchJob?.cancel()
        // A re-sort keeps the header; a page without a count must not inherit the last video's (#1111).
        if (videoId != loadingVideoId) {
            _sortOptions.value = emptyList()
            _totalText.value = null
            _totalCount.value = null
            _isDisabled.value = false
        }
        loadingVideoId = videoId
        activeSortToken = sortToken
        _comments.value = emptyList()
        next = CommentsPageResult.EMPTY
        timedCursors = mutableListOf()
        timedFromYouTube = false
        timedGeneration++
        _hasMore.value = false
        _isLoadingMore.value = false
        _isLoading.value = true
    }

    private suspend fun awaitPlaybackGate(videoId: String) {
        withTimeoutOrNull(STARTUP_GATE_TIMEOUT_MS) {
            playbackState.first { state ->
                !PlaybackStartupPolicy.shouldDelaySecondaryContent(
                    isPlaybackLoading = state.isPlaybackLoading,
                    currentVideoId = state.currentVideoId,
                    requestedVideoId = videoId,
                )
            }
        }
    }

    fun loadMore(videoId: String) {
        if (timedCursors.isNotEmpty()) {
            if (_hasMore.value && !_isLoading.value && !_isLoadingMore.value) {
                _isLoadingMore.value = true
                walkTimed(videoId, TIMED_MORE_PAGES_PER_ORDER)
            }
            return
        }
        if (!next.hasMore) return
        if (_isLoadingMore.value) return
        prefetchJob?.cancel()
        scope.launch {
            _isLoadingMore.value = true
            try {
                appendNextPage(videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error loading more comments", e)
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    /**
     * Walks a bounded number of further pages in the background once the first one has landed, so
     * a filter over the loaded set is not deciding from twenty rows.
     */
    private fun prefetchFollowingPages(videoId: String) {
        if (prefetchPages <= 0) return
        prefetchJob?.cancel()
        prefetchJob =
            scope.launch {
                repeat(prefetchPages) {
                    if (!next.hasMore || !isCurrentVideo(videoId) || _isLoadingMore.value) return@launch
                    try {
                        appendNextPage(videoId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.d(TAG, "Comment prefetch stopped for $videoId: ${e.message}")
                        return@launch
                    }
                }
            }
    }

    private suspend fun fetchNextPage(
        videoId: String,
        cursor: CommentsPageResult,
    ): CommentsPageResult? = cursor.continuation?.let { repository.getMoreVideoComments(videoId, it) }

    private suspend fun appendNextPage(videoId: String) {
        val page = fetchNextPage(videoId, next) ?: return
        if (!isCurrentVideo(videoId)) return
        _comments.value = _comments.value.mergeDistinctByNonBlankKey(page.comments, Comment::id)
        next = page
        _hasMore.value = page.hasMore
    }

    fun loadReplies(
        videoId: String,
        comment: Comment,
    ) = loadReplies(videoId, comment, append = false)

    fun loadMoreReplies(
        videoId: String,
        comment: Comment,
    ) = loadReplies(videoId, comment, append = true)

    private fun loadReplies(
        videoId: String,
        comment: Comment,
        append: Boolean,
    ) {
        val continuation = comment.continuationToken ?: return
        if (!repliesInFlight.add(comment.id)) return
        scope.launch {
            try {
                val page = repository.getVideoCommentReplies(videoId, continuation)
                val replies = page.comments
                _comments.value =
                    _comments.value.map { current ->
                        if (current.id != comment.id) {
                            current
                        } else {
                            current.copy(
                                replies =
                                    if (append) {
                                        current.replies.mergeDistinctByNonBlankKey(replies, Comment::id)
                                    } else {
                                        replies.distinctByNonBlankKey(Comment::id)
                                    },
                                continuationToken = page.continuation,
                            )
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error loading replies", e)
            } finally {
                repliesInFlight.remove(comment.id)
            }
        }
    }

    private suspend fun fetch(block: suspend () -> CommentsPageResult?): CommentsPageResult? =
        if (fetchTimeoutMs == null) block() else withTimeoutOrNull(fetchTimeoutMs) { block() }
}
