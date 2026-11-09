package io.github.aedev.flow.ui.components.videoplayer.sheet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.ui.components.shared.CommentSortChips
import io.github.aedev.flow.ui.components.shared.CommentSortFilter
import io.github.aedev.flow.ui.components.shared.CommentThreadState
import io.github.aedev.flow.ui.components.shared.CommentsTimedFollow
import io.github.aedev.flow.ui.components.shared.CommentsTimedFollowRow
import io.github.aedev.flow.ui.components.shared.FlowCommentsList
import io.github.aedev.flow.ui.components.shared.rememberMediaArtworkTint

/**
 * Comments rendered as an inline panel rather than a modal sheet, so the video stays visible.
 * Shared by the fullscreen side drawer and the tablet landscape split layout.
 */
@Composable
fun PlayerCommentsPanel(
    comments: List<Comment>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    selectedFilter: CommentSortFilter,
    onFilterChanged: (CommentSortFilter) -> Unit,
    onSeekMs: (Long) -> Unit,
    onLoadReplies: (Comment) -> Unit,
    onLoadMoreReplies: (Comment) -> Unit,
    onAuthorClick: (String) -> Unit,
    onLoadMore: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    totalText: String? = null,
    artworkUrl: String? = null,
    showTimed: Boolean = false,
    timedFollow: CommentsTimedFollow? = null,
    listState: LazyListState = rememberLazyListState(),
    threadState: CommentThreadState? = null,
) {
    val tint = rememberMediaArtworkTint(artworkUrl)

    Column(modifier.fillMaxSize()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.comments),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!totalText.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = totalText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.close),
                )
            }
        }
        CommentSortChips(
            selected = selectedFilter,
            onSelect = onFilterChanged,
            showTimed = showTimed,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
        )
        if (selectedFilter == CommentSortFilter.TIMED && timedFollow != null) {
            CommentsTimedFollowRow(timedFollow)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
        FlowCommentsList(
            comments = comments,
            isLoading = isLoading,
            listState = listState,
            selectedFilter = selectedFilter,
            onSeekMs = onSeekMs,
            onLoadReplies = onLoadReplies,
            onLoadMoreReplies = onLoadMoreReplies,
            onAuthorClick = onAuthorClick,
            onAvatarClick = {},
            isLoadingMore = isLoadingMore,
            onLoadMore = onLoadMore,
            hasMore = hasMore,
            tint = tint,
            timedFollow = timedFollow,
            threadState = threadState,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}
