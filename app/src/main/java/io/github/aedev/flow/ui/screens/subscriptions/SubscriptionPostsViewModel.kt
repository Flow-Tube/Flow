package io.github.aedev.flow.ui.screens.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.community.PostCommentsLoader
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.subscriptions.SubscriptionPost
import io.github.aedev.flow.data.subscriptions.SubscriptionPostsPagingSource
import io.github.aedev.flow.data.subscriptions.SubscriptionPostsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject

/** Which subscriptions the Posts view reads: a group's channels, or every one when [channelIds] is null. */
private data class PostsScope(
    val channelIds: Set<String>?,
)

/** The Subscriptions Posts view. Nothing loads until [show] is called by the visible view. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SubscriptionPostsViewModel
    @Inject
    constructor(
        private val repository: SubscriptionPostsRepository,
        private val neuroEngine: FlowNeuroEngine,
    ) : ViewModel() {
        private val scope = MutableStateFlow<PostsScope?>(null)

        val postComments = PostCommentsLoader(viewModelScope)

        val posts: Flow<PagingData<SubscriptionPost>> =
            scope
                .filterNotNull()
                .flatMapLatest { postsScope ->
                    Pager(
                        config =
                            PagingConfig(
                                pageSize = SubscriptionPostsPagingSource.CHANNELS_PER_PAGE,
                                prefetchDistance = PREFETCH_DISTANCE,
                                initialLoadSize = SubscriptionPostsPagingSource.CHANNELS_PER_PAGE,
                                enablePlaceholders = false,
                            ),
                    ) {
                        SubscriptionPostsPagingSource(
                            loadChannels = { repository.channels(postsScope.channelIds, neuroEngine.feedExclusions()) },
                            loadRound = repository::recentPosts,
                        )
                    }.flow
                }.cachedIn(viewModelScope)

        fun show(channelIds: Set<String>?) {
            scope.value = PostsScope(channelIds)
        }

        /** Drops the cached posts so the next load fetches every channel again. */
        fun forgetCachedPosts() = repository.invalidate()

        private companion object {
            const val PREFETCH_DISTANCE = 4
        }
    }
