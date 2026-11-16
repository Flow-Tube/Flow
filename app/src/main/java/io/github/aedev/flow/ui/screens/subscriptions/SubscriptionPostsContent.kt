package io.github.aedev.flow.ui.screens.subscriptions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.shared.FeedPagingFooter
import io.github.aedev.flow.ui.components.shared.FlowEmptyState
import io.github.aedev.flow.ui.components.shared.FlowErrorState
import io.github.aedev.flow.ui.components.shared.FlowLoadingIndicator
import io.github.aedev.flow.ui.components.shared.FlowPullToRefreshBox
import io.github.aedev.flow.ui.components.shared.MediaPostCard
import io.github.aedev.flow.ui.components.shared.MediaPostCommentsSheet
import io.github.aedev.flow.ui.components.shared.rememberPostShareAction

private val PostsMaxWidth = 760.dp
private val StatePadding = 48.dp

/**
 * Recent community posts from the subscribed channels in [channelIds] (every one when null), under
 * the same [header] as the video feed. Loading starts when this view is first shown.
 */
@Composable
internal fun SubscriptionPostsContent(
    channelIds: Set<String>?,
    listState: LazyListState,
    onChannelClick: (Channel) -> Unit,
    onVideoClick: (Video) -> Unit,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SubscriptionPostsViewModel = hiltViewModel(),
) {
    LaunchedEffect(channelIds) { viewModel.show(channelIds) }
    val posts = viewModel.posts.collectAsLazyPagingItems()
    val sharePost = rememberPostShareAction()
    val refreshState = posts.loadState.refresh
    val appendState = posts.loadState.append
    var userRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshState is LoadState.Loading) {
        if (refreshState !is LoadState.Loading) userRefreshing = false
    }

    Box(modifier = modifier.fillMaxSize()) {
        FlowPullToRefreshBox(
            isRefreshing = userRefreshing,
            onRefresh = {
                userRefreshing = true
                viewModel.forgetCachedPosts()
                posts.refresh()
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = flowBottomContentPadding()),
            ) {
                item(key = "posts_header", contentType = "header") { header() }

                if (posts.itemCount == 0) {
                    item(key = "posts_state", contentType = "state") {
                        val stateModifier = Modifier.fillParentMaxWidth().padding(vertical = StatePadding)
                        when {
                            refreshState is LoadState.Error || appendState is LoadState.Error -> {
                                FlowErrorState(
                                    error = stringResource(R.string.community_posts_load_failed),
                                    onRetry = posts::retry,
                                    modifier = stateModifier,
                                )
                            }

                            refreshState is LoadState.NotLoading && appendState.endOfPaginationReached -> {
                                FlowEmptyState(
                                    title = stringResource(R.string.subscription_posts_empty),
                                    icon = Icons.Outlined.ChatBubbleOutline,
                                    modifier = stateModifier,
                                )
                            }

                            else -> {
                                Box(stateModifier, contentAlignment = Alignment.Center) { FlowLoadingIndicator() }
                            }
                        }
                    }
                    return@LazyColumn
                }

                items(
                    count = posts.itemCount,
                    key = posts.itemKey { it.post.id },
                    contentType = posts.itemContentType { "post" },
                ) { index ->
                    val item = posts[index] ?: return@items
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        MediaPostCard(
                            post = item.post,
                            onAuthorClick = {
                                onChannelClick(
                                    Channel(
                                        id = item.channelId,
                                        name = item.post.authorName,
                                        thumbnailUrl = item.post.authorAvatarUrl,
                                        subscriberCount = 0L,
                                    ),
                                )
                            },
                            onCommentsClick = { viewModel.postComments.open(item.post) },
                            onShareClick = { sharePost(item.post) },
                            onVideoClick = onVideoClick,
                            modifier = Modifier.widthIn(max = PostsMaxWidth),
                        )
                    }
                }

                item(key = "posts_footer", contentType = "footer") {
                    FeedPagingFooter(appendState = appendState, itemCount = posts.itemCount, onRetry = posts::retry)
                }
            }
        }

        MediaPostCommentsSheet(
            loader = viewModel.postComments,
            onAuthorClick = { channelId -> onChannelClick(Channel(id = channelId, name = "", thumbnailUrl = "", subscriberCount = 0L)) },
        )
    }
}
