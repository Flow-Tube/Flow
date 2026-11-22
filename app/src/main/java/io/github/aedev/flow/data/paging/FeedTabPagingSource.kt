package io.github.aedev.flow.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import io.github.aedev.flow.data.model.DistinctKeyTracker
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.channel.ChannelTabContent
import io.github.aedev.flow.innertube.pages.channel.ChannelTabKind
import io.github.aedev.flow.innertube.pages.renderer.FeedItem
import io.github.aedev.flow.innertube.pages.renderer.FeedItemOwner
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.withContext

/**
 * Any paged feed of [FeedItem] behind a browseId and a params token — a channel tab, or an explore
 * destination's shelf "see all". One source for all of them because they parse to the same
 * [ChannelTabContent]; what differs is the params token, which the response itself supplied.
 *
 * @param sortToken a chip from the tab's own sort bar. A chip token already encodes the channel, so
 *   with one chosen it *is* the first page rather than a filter applied to one.
 * @param onPageLoaded reports the sort bar and the resolved owner back once, so the screen can render
 *   chips without a second browse.
 */
class FeedTabPagingSource(
    private val browseId: String,
    private val params: String,
    private val kind: ChannelTabKind,
    private val sortToken: String? = null,
    private var owner: FeedItemOwner = FeedItemOwner(id = browseId),
    private val onPageLoaded: (ChannelTabContent) -> Unit = {},
) : PagingSource<String, FeedItem>() {
    private val seen = DistinctKeyTracker()

    override fun getRefreshKey(state: PagingState<String, FeedItem>): String? = null

    override suspend fun load(params: LoadParams<String>): LoadResult<String, FeedItem> =
        withContext(PerformanceDispatcher.networkIO) {
            val cursor = params.key
            val page =
                when {
                    cursor != null -> YouTube.channelTabContinuation(cursor, owner, kind)
                    sortToken != null -> YouTube.channelTabContinuation(sortToken, owner, kind)
                    else -> YouTube.channelTab(browseId, this@FeedTabPagingSource.params, owner, kind)
                }.getOrElse { return@withContext LoadResult.Error(it) }

            if (page.owner.id.isNotBlank()) owner = page.owner
            if (cursor == null || page.filters.isNotEmpty()) onPageLoaded(page)

            LoadResult.Page(
                data = seen.filter(page.items) { it.pagingKey() },
                prevKey = null,
                nextKey = page.continuation,
            )
        }
}

private fun FeedItem.pagingKey(): String =
    when (this) {
        is FeedItem.VideoItem -> video.id
        is FeedItem.ShortItem -> video.id
        is FeedItem.PlaylistItem -> playlist.id
        is FeedItem.RelatedChannelItem -> channel.id
        is FeedItem.PostItem -> post.id
    }
