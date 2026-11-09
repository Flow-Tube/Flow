package io.github.aedev.flow.data.subscriptions

import androidx.paging.PagingSource
import androidx.paging.PagingState
import java.io.IOException

/**
 * Pages through subscribed channels [CHANNELS_PER_PAGE] at a time; a page is those channels' recent
 * posts. The key is the page's index into the channel order, which is read once per generation.
 */
internal class SubscriptionPostsPagingSource(
    private val loadChannels: suspend () -> List<PostsChannel>,
    private val loadRound: suspend (List<PostsChannel>) -> SubscriptionPostsRound,
) : PagingSource<Int, SubscriptionPost>() {
    private var channels: List<PostsChannel>? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SubscriptionPost> {
        val index = params.key ?: 0
        val ordered = channels ?: loadChannels().also { channels = it }
        val batch = ordered.drop(index * CHANNELS_PER_PAGE).take(CHANNELS_PER_PAGE)
        if (batch.isEmpty()) return LoadResult.Page(emptyList(), prevKey = null, nextKey = null)

        val round = loadRound(batch)
        if (round.allFailed) return LoadResult.Error(IOException("No subscribed channel's posts could be loaded"))
        val nextKey = (index + 1).takeIf { it * CHANNELS_PER_PAGE < ordered.size }
        return LoadResult.Page(round.posts, prevKey = null, nextKey = nextKey)
    }

    override fun getRefreshKey(state: PagingState<Int, SubscriptionPost>): Int? = null

    companion object {
        const val CHANNELS_PER_PAGE = 8
    }
}
