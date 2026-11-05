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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.model.distinctByNonBlankKey

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
) {
    val latestOnLoadMore by rememberUpdatedState(onLoadMore)
    val uniqueComments =
        remember(comments) {
            comments.distinctByNonBlankKey(Comment::id)
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
        } else if (uniqueComments.isEmpty()) {
            item(key = "empty") {
                Box(
                    modifier =
                        Modifier
                            .fillParentMaxWidth()
                            .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(if (selectedFilter == CommentSortFilter.TIMED) R.string.no_timed_comments else R.string.no_comments_yet),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(
                items = uniqueComments,
                key = { comment -> "${selectedFilter.name}_${comment.id}" },
            ) { comment ->
                FlowCommentItem(
                    comment = comment,
                    tint = tint,
                    onSeekMs = onSeekMs,
                    onLoadReplies = onLoadReplies,
                    onLoadMoreReplies = onLoadMoreReplies,
                    onAuthorClick = onAuthorClick,
                    onAvatarClick = onAvatarClick,
                )
            }
            if (hasMore) {
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
