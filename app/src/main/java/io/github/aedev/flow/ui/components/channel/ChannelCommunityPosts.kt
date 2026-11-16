package io.github.aedev.flow.ui.components.channel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.innertube.pages.renderer.CommunityPost
import io.github.aedev.flow.ui.components.shared.FlowFeedProgress
import io.github.aedev.flow.ui.components.shared.FlowLoadingIndicator
import io.github.aedev.flow.ui.components.shared.MediaPostCard
import io.github.aedev.flow.ui.screens.channel.ChannelRequestErrorState

@Composable
internal fun ChannelCommunityPosts(
    posts: List<CommunityPost>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    errorLog: String?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onAuthorClick: () -> Unit,
    onCommentsClick: (CommunityPost) -> Unit,
    onShareClick: (CommunityPost) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        when {
            isLoading && posts.isEmpty() -> {
                item(key = "posts_loading") {
                    FlowLoadingIndicator(modifier = Modifier.fillParentMaxSize())
                }
            }

            errorLog != null && posts.isEmpty() -> {
                item(key = "posts_error") {
                    Box(
                        modifier =
                            Modifier
                                .fillParentMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        ChannelRequestErrorState(
                            message = stringResource(R.string.community_posts_load_failed),
                            errorLog = errorLog,
                            onRetry = onRetry,
                        )
                    }
                }
            }

            posts.isEmpty() -> {
                item(key = "posts_empty") {
                    Box(
                        modifier =
                            Modifier
                                .fillParentMaxSize()
                                .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.community_posts_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            else -> {
                items(
                    items = posts,
                    key = CommunityPost::id,
                ) { post ->
                    MediaPostCard(
                        post = post,
                        onAuthorClick = onAuthorClick,
                        onCommentsClick = { onCommentsClick(post) },
                        onShareClick = { onShareClick(post) },
                    )
                }
                if (hasMore) {
                    item(key = "posts_load_more") {
                        LaunchedEffect(posts.size) { onLoadMore() }
                        if (isLoadingMore) {
                            FlowFeedProgress()
                        } else {
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}
