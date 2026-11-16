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
    val sortedComments = remember(state.comments, sort) { sortCommentsByFilter(state.comments, sort) }

    FlowCommentsBottomSheet(
        comments = sortedComments,
        isLoading = state.isLoading,
        onDismiss = loader::close,
        selectedFilter = sort,
        onFilterChanged = { sort = it },
        isLoadingMore = state.isLoadingMore,
        onLoadMore = loader::loadMore,
        hasMore = state.continuation != null,
        onLoadReplies = loader::loadReplies,
        onLoadMoreReplies = loader::loadMoreReplies,
        onAuthorClick = { channelId -> if (channelId.isNotBlank()) onAuthorClick(channelId) },
    )
}
