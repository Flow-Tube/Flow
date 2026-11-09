package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.model.distinctByNonBlankKey
import kotlinx.coroutines.flow.drop

@Composable
fun FlowCommentsList(
    comments: List<Comment>,
    isLoading: Boolean,
    listState: LazyListState,
    selectedFilter: CommentSortFilter,
    onSeekMs: (Long) -> Unit,
    onLoadReplies: (Comment) -> Unit,
    onLoadMoreReplies: (Comment) -> Unit,
    onAuthorClick: (String) -> Unit,
    onAvatarClick: (String) -> Unit,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    hasMore: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 32.dp),
    tint: MediaArtworkTint? = null,
    timedFollow: CommentsTimedFollow? = null,
    threadState: CommentThreadState? = null,
) {
    val threads = threadState ?: rememberCommentThreadState(selectedFilter)
    val latestOnLoadMore by rememberUpdatedState(onLoadMore)
    val currentFilter by rememberUpdatedState(selectedFilter)
    LaunchedEffect(listState) {
        snapshotFlow { currentFilter }.drop(1).collect { listState.scrollToItem(0) }
    }
    val timed = selectedFilter == CommentSortFilter.TIMED
    val uniqueComments =
        remember(comments) {
            comments.distinctByNonBlankKey(Comment::id)
        }
    val followPosition = timedFollow?.takeIf { timed && it.enabled && it.isSurfaceVisible }?.positionMs
    val anchor = rememberTimedAnchor(uniqueComments, followPosition)
    val pause = remember { TimedFollowPause() }
    if (followPosition != null) {
        FollowTimedAnchor(listState, anchor, uniqueComments, pause)
    }
    val seekAndResume: (Long) -> Unit =
        remember(onSeekMs, pause) {
            { positionMs ->
                pause.resume()
                onSeekMs(positionMs)
            }
        }
    val loadRepliesAndPause: (Comment) -> Unit =
        remember(onLoadReplies, pause) {
            { comment ->
                pause.afterOpeningReplies()
                onLoadReplies(comment)
            }
        }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        if (isLoading) {
            item(key = "loading") {
                Column(Modifier.padding(16.dp)) {
                    repeat(6) { CommentSkeleton() }
                }
            }
        } else if (uniqueComments.isEmpty() && !(timed && isLoadingMore)) {
            item(key = "empty") {
                Box(
                    modifier =
                        Modifier
                            .fillParentMaxWidth()
                            .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(if (timed) R.string.no_timed_comments else R.string.no_comments_yet),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (timed && hasMore) item(key = "load_more_button") { CommentsLoadMoreButton(onLoadMore) }
        } else {
            itemsIndexed(
                items = uniqueComments,
                key = { _, comment -> "${selectedFilter.name}_${comment.id}" },
            ) { index, comment ->
                val highlighted by remember(index, anchor) { derivedStateOf { anchor.value == index } }
                TimedCommentBackdrop(highlighted) {
                    FlowCommentItem(
                        comment = comment,
                        tint = tint,
                        onSeekMs = seekAndResume,
                        onLoadReplies = loadRepliesAndPause,
                        onLoadMoreReplies = onLoadMoreReplies,
                        onAuthorClick = onAuthorClick,
                        onAvatarClick = onAvatarClick,
                        isRepliesVisible = threads.isRepliesOpen(comment.id),
                        onToggleReplies = { threads.toggleReplies(comment.id) },
                        isExpanded = threads.isTextExpanded(comment.id),
                        onExpand = { threads.expandText(comment.id) },
                    )
                }
            }
            if (timed) {
                when {
                    isLoadingMore -> item(key = "load_more_progress") { CommentsLoadingMoreRow() }
                    hasMore -> item(key = "load_more_button") { CommentsLoadMoreButton(onLoadMore) }
                }
            } else if (hasMore) {
                item(key = "load_more_trigger") {
                    LaunchedEffect(comments.size) {
                        latestOnLoadMore()
                    }
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isLoadingMore) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentsLoadingMoreRow() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun CommentsLoadMoreButton(onClick: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextButton(onClick = onClick) {
            Text(stringResource(R.string.comments_load_more))
        }
    }
}

/**
 * A still placeholder rather than a shimmering one: the comments sheet is kept composed behind the
 * player, and a looping animation there would keep producing frames while nothing is on screen.
 */
@Composable
fun CommentSkeleton() {
    val placeholder = MaterialTheme.colorScheme.surfaceVariant
    Row(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
        Box(modifier = Modifier.size(40.dp).background(placeholder, CircleShape))
        Spacer(modifier = Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(modifier = Modifier.width(100.dp).height(12.dp).background(placeholder, SkeletonShape))
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().height(12.dp).background(placeholder, SkeletonShape))
            Spacer(modifier = Modifier.height(4.dp))
            Box(modifier = Modifier.width(200.dp).height(12.dp).background(placeholder, SkeletonShape))
        }
    }
}

private val SkeletonShape = RoundedCornerShape(4.dp)
