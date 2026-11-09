package io.github.aedev.flow.ui.screens.channel

import android.util.Log
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.renderer.CommunityPost
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ChannelCommunityUiState(
    val posts: List<CommunityPost> = emptyList(),
    val postsContinuation: String? = null,
    val postsLoaded: Boolean = false,
    val isLoadingPosts: Boolean = false,
    val isLoadingMorePosts: Boolean = false,
    val postsErrorLog: String? = null,
)

internal class ChannelCommunityController(
    private val scope: CoroutineScope,
) {
    private data class ChannelContext(
        val id: String,
        val name: String,
        val avatarUrl: String,
    )

    private val _state = MutableStateFlow(ChannelCommunityUiState())
    val state: StateFlow<ChannelCommunityUiState> = _state.asStateFlow()

    private var channel: ChannelContext? = null

    fun reset(
        channelId: String,
        channelName: String,
        avatarUrl: String,
    ) {
        channel = ChannelContext(channelId, channelName, avatarUrl)
        _state.value = ChannelCommunityUiState()
    }

    fun ensurePostsLoaded() {
        val channelSnapshot = channel ?: return
        val stateSnapshot = _state.value
        if (stateSnapshot.postsLoaded || stateSnapshot.isLoadingPosts) return

        scope.launch(PerformanceDispatcher.networkIO) {
            _state.update { it.copy(isLoadingPosts = true, postsErrorLog = null) }
            YouTube
                .communityPosts(
                    channelSnapshot.id,
                    channelSnapshot.name,
                    channelSnapshot.avatarUrl,
                ).fold(
                    onSuccess = { page ->
                        if (channel?.id != channelSnapshot.id) return@fold
                        _state.update {
                            it.copy(
                                posts = page.posts,
                                postsContinuation = page.continuation,
                                postsLoaded = true,
                                isLoadingPosts = false,
                            )
                        }
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to load community posts", error)
                        if (channel?.id == channelSnapshot.id) {
                            _state.update {
                                it.copy(
                                    postsLoaded = true,
                                    isLoadingPosts = false,
                                    postsErrorLog =
                                        buildChannelRequestErrorLog(
                                            operation = "community_posts",
                                            channelId = channelSnapshot.id,
                                            error = error,
                                        ),
                                )
                            }
                        }
                    },
                )
        }
    }

    fun retryPosts() {
        _state.update { it.copy(postsLoaded = false, postsErrorLog = null) }
        ensurePostsLoaded()
    }

    fun loadMorePosts() {
        val channelSnapshot = channel ?: return
        val continuation = _state.value.postsContinuation ?: return
        if (_state.value.isLoadingMorePosts) return

        scope.launch(PerformanceDispatcher.networkIO) {
            _state.update { it.copy(isLoadingMorePosts = true) }
            YouTube
                .communityPostsContinuation(
                    continuation,
                    channelSnapshot.name,
                    channelSnapshot.avatarUrl,
                ).fold(
                    onSuccess = { page ->
                        if (channel?.id != channelSnapshot.id) return@fold
                        _state.update {
                            it.copy(
                                posts = (it.posts + page.posts).distinctBy(CommunityPost::id),
                                postsContinuation = page.continuation,
                                isLoadingMorePosts = false,
                            )
                        }
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to load more community posts", error)
                        _state.update { it.copy(isLoadingMorePosts = false) }
                    },
                )
        }
    }

    private companion object {
        const val TAG = "ChannelCommunity"
    }
}
