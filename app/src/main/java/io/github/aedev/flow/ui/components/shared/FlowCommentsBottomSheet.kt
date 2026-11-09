package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.ui.components.shared.FlowBottomSheet
import io.github.aedev.flow.ui.components.shared.defaultSheetExpandedHeight
import io.github.aedev.flow.ui.components.shared.rememberFlowBottomSheetState

@Composable
fun FlowCommentsBottomSheet(
    comments: List<Comment>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSeekMs: (Long) -> Unit = {},
    onFilterChanged: (CommentSortFilter) -> Unit = {},
    onLoadReplies: (Comment) -> Unit = {},
    onLoadMoreReplies: (Comment) -> Unit = {},
    selectedFilter: CommentSortFilter = CommentSortFilter.TOP,
    totalText: String? = null,
    artworkUrl: String? = null,
    showTimed: Boolean = false,
    timedFollow: CommentsTimedFollow? = null,
    isLoadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    hasMore: Boolean = false,
    onAuthorClick: (String) -> Unit = {},
    onAvatarClick: (String) -> Unit = {},
    expandedHeight: Dp? = null,
    collapsedHeight: Dp = 0.dp,
    onSheetProgressChange: (Float) -> Unit = {},
    dismissOnOutsideTap: Boolean = false,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    threadState: CommentThreadState? = null,
) {
    val sheetState = rememberFlowBottomSheetState()
    val tint = rememberMediaArtworkTint(artworkUrl)

    FlowBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        state = sheetState,
        expandedHeight = expandedHeight ?: defaultSheetExpandedHeight(),
        collapsedHeight = collapsedHeight,
        dismissOnOutsideTap = dismissOnOutsideTap,
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.surface,
        onProgressChange = onSheetProgressChange,
        header = { dragModifier ->
            // Not FlowSheetHeader: the sort chips sit under the title row, inside the same padding
            // and above the divider, which no header parameter can express.
            CommentsSheetHeader(
                selectedFilter = selectedFilter,
                totalText = totalText,
                showTimed = showTimed,
                timedFollow = timedFollow,
                onFilterChanged = onFilterChanged,
                onClose = { sheetState.dismiss() },
                dragModifier = dragModifier,
            )
        },
    ) {
        FlowCommentsList(
            comments = comments,
            isLoading = isLoading,
            listState = listState,
            selectedFilter = selectedFilter,
            onSeekMs = onSeekMs,
            onLoadReplies = onLoadReplies,
            onLoadMoreReplies = onLoadMoreReplies,
            onAuthorClick = onAuthorClick,
            onAvatarClick = onAvatarClick,
            isLoadingMore = isLoadingMore,
            onLoadMore = onLoadMore,
            hasMore = hasMore,
            tint = tint,
            timedFollow = timedFollow,
            threadState = threadState,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentsSheetHeader(
    selectedFilter: CommentSortFilter,
    totalText: String?,
    showTimed: Boolean,
    timedFollow: CommentsTimedFollow?,
    onFilterChanged: (CommentSortFilter) -> Unit,
    onClose: () -> Unit,
    dragModifier: Modifier,
) {
    Column(modifier = dragModifier) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            BottomSheetDefaults.DragHandle()
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.comments),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!totalText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = totalText,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                }
            }
            CommentSortChips(
                selected = selectedFilter,
                onSelect = onFilterChanged,
                showTimed = showTimed,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (selectedFilter == CommentSortFilter.TIMED && timedFollow != null) {
            CommentsTimedFollowRow(timedFollow)
        }
        HorizontalDivider(
            color =
                MaterialTheme.colorScheme.outlineVariant
                    .copy(alpha = FlowSheetHeaderDefaults.DividerAlpha),
        )
    }
}
