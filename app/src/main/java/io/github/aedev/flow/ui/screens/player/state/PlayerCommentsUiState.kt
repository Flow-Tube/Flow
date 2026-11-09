package io.github.aedev.flow.ui.screens.player.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.innertube.pages.VideoCommentSort
import io.github.aedev.flow.ui.components.shared.CommentSortFilter
import io.github.aedev.flow.ui.components.shared.CommentsTimedFollow
import io.github.aedev.flow.ui.components.shared.applyVideoCommentFilters
import io.github.aedev.flow.ui.components.shared.timedCommentMarks
import io.github.aedev.flow.ui.components.shared.timedCommentOrders
import io.github.aedev.flow.ui.components.shared.videoCommentSortFor
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModel

/**
 * Everything the three comment surfaces read: the loaded list, its paging flags, the section's own
 * total, and the orders it offers.
 *
 * Collected once in the host so the bottom sheet, the fullscreen drawer and the supporting pane all
 * show the same page rather than each collecting their own.
 */
@Immutable
data class PlayerCommentsUiState(
    val comments: List<Comment> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val totalText: String? = null,
    val sortOptions: List<VideoCommentSort> = emptyList(),
)

@Composable
fun rememberPlayerCommentsUiState(viewModel: VideoPlayerViewModel): PlayerCommentsUiState {
    val comments by viewModel.commentsState.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoadingComments.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMoreComments.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMoreComments.collectAsStateWithLifecycle()
    val totalText by viewModel.commentTotalText.collectAsStateWithLifecycle()
    val sortOptions by viewModel.commentSortOptions.collectAsStateWithLifecycle()
    return remember(comments, isLoading, isLoadingMore, hasMore, totalText, sortOptions) {
        PlayerCommentsUiState(
            comments = comments,
            isLoading = isLoading,
            isLoadingMore = isLoadingMore,
            hasMore = hasMore,
            totalText = totalText,
            sortOptions = sortOptions,
        )
    }
}

/** What the player's comment surfaces give the Timed list to follow the video with. */
@Composable
fun rememberPlayerCommentsTimedFollow(
    viewModel: VideoPlayerViewModel,
    screenState: PlayerScreenState,
): CommentsTimedFollow {
    val enabled by viewModel.timedCommentsFollow.collectAsStateWithLifecycle()
    val isVisible = screenState.isPlayerExpanded
    return remember(enabled, isVisible) {
        CommentsTimedFollow(
            enabled = enabled,
            onEnabledChange = viewModel::setTimedCommentsFollow,
            isSurfaceVisible = isVisible,
            positionMs = { screenState.currentPosition },
        )
    }
}

/** The rows the selected chip shows, out of the pages loaded so far. */
@Composable
fun PlayerCommentsUiState.visibleComments(screenState: PlayerScreenState): List<Comment> {
    val filter = screenState.commentSortFilter
    // Only Timed reads the length, so a live stream's growing duration recomposes nothing here.
    val durationMs = if (filter == CommentSortFilter.TIMED) screenState.duration else 0L
    return remember(comments, filter, durationMs) {
        applyVideoCommentFilters(comments, filter, durationMs)
    }
}

/**
 * Where the seek bar dots the loaded comments, for as long as Timed is the selected chip.
 *
 * A live stream's length is its playhead, which no comment's timestamp is measured against, and
 * reading it here would recompute the marks on every tick.
 */
@Composable
fun PlayerCommentsUiState.seekBarCommentMarks(
    screenState: PlayerScreenState,
    isLive: Boolean,
): List<Float> {
    val durationMs = if (screenState.commentSortFilter == CommentSortFilter.TIMED && !isLive) screenState.duration else 0L
    return remember(comments, durationMs) { timedCommentMarks(comments, durationMs) }
}

/**
 * Applies a chip.
 *
 * Top and Newest are separate continuations, so picking one re-requests the section. Oldest reads
 * the chronological continuation and reverses it locally, which is why it selects the same one as
 * Newest. Timed reads both.
 */
fun PlayerCommentsUiState.selectCommentSort(
    filter: CommentSortFilter,
    videoId: String,
    screenState: PlayerScreenState,
    viewModel: VideoPlayerViewModel,
) {
    screenState.changeCommentSort(filter)
    if (filter == CommentSortFilter.TIMED) {
        viewModel.selectTimedComments(videoId, timedCommentOrders(sortOptions))
        return
    }
    videoCommentSortFor(sortOptions, filter)?.let { sort ->
        viewModel.selectCommentSort(videoId, sort)
    }
}
