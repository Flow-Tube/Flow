package io.github.aedev.flow.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.data.community.PostCommentsLoader

/** The comments sheet of the post [loader] has open; nothing while no post is open. */
@Composable
fun MediaPostCommentsSheet(
    loader: PostCommentsLoader,
    onAuthorClick: (channelId: String) -> Unit,
) {
    val state by loader.state.collectAsStateWithLifecycle()
    val post = state.activePost ?: return
    var sort by rememberSaveable(post.id) { mutableStateOf(CommentSortFilter.TOP) }
    val comments = remember(state.comments, sort) { if (sort == CommentSortFilter.OLDEST) oldestFirst(state.comments) else state.comments }

    FlowCommentsBottomSheet(
        comments = comments,
        isLoading = state.isLoading,
        onDismiss = loader::close,
        selectedFilter = sort,
        sortOptions = state.sortOptions,
        sortNote = oldestCommentsNote(sort, state.oldestCapped, state.comments.size),
        onFilterChanged = { filter ->
            if (filter != sort) {
                videoCommentSortFor(state.sortOptions, filter)?.let { order ->
                    sort = filter
                    if (filter == CommentSortFilter.OLDEST) loader.selectOldest(order) else loader.selectSort(order)
                }
            }
        },
        isLoadingMore = state.isLoadingMore,
        onLoadMore = loader::loadMore,
        hasMore = state.continuation != null,
        onLoadReplies = loader::loadReplies,
        onLoadMoreReplies = loader::loadMoreReplies,
        onAuthorClick = { channelId -> if (channelId.isNotBlank()) onAuthorClick(channelId) },
    )
}
