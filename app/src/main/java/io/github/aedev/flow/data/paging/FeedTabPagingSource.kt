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
import kotlin.coroutines.CoroutineContext

/**
 * Any paged feed of [FeedItem] behind a browseId and a params token — a channel tab, or an explore
 * destination's shelf "see all". One source for all of them because they parse to the same
 * [ChannelTabContent]; what differs is the params token, which the response itself supplied.
 *
 * @param sortToken a chip from the tab's own sort bar. A chip token already encodes the channel, so
 *   with one chosen it *is* the first page rather than a filter applied to one.
 * @param onPageLoaded reports the sort bar and the resolved owner back once, so the screen can render
 *   chips without a second browse.
 *
 * The list ends when a page hands back the token it was loaded with, which Paging refuses as a key,
 * or after [MAX_EMPTY_LOADS] pages in a row that added nothing new.
 */
class FeedTabPagingSource(
    private val browseId: String,
    private val params: String,
    private val kind: ChannelTabKind,
    private val sortToken: String? = null,
    private var owner: FeedItemOwner = FeedItemOwner(id = browseId),
    private val onPageLoaded: (ChannelTabContent) -> Unit = {},
    private val loadPage: FeedTabPageLoader =
        FeedTabPageLoader { continuation, pageOwner ->
            if (continuation != null) {
                YouTube.channelTabContinuation(continuation, pageOwner, kind)
            } else {
                YouTube.channelTab(browseId, params, pageOwner, kind)
            }
        },
    private val loadContext: CoroutineContext = PerformanceDispatcher.networkIO,
) : PagingSource<String, FeedItem>() {
    private val seen = DistinctKeyTracker()
    private var emptyLoadsInARow = 0

    override fun getRefreshKey(state: PagingState<String, FeedItem>): String? = null

    override suspend fun load(params: LoadParams<String>): LoadResult<String, FeedItem> =
        withContext(loadContext) {
            val cursor = params.key
            val requested = cursor ?: sortToken
            val page = loadPage(requested, owner).getOrElse { return@withContext LoadResult.Error(it) }

            if (page.owner.id.isNotBlank()) owner = page.owner
            if (cursor == null || page.filters.isNotEmpty()) onPageLoaded(page)

            val items = seen.filter(page.items) { it.pagingKey() }
            emptyLoadsInARow = if (items.isEmpty()) emptyLoadsInARow + 1 else 0
            LoadResult.Page(
                data = items,
                prevKey = null,
                nextKey = page.continuation.takeUnless { it == requested || emptyLoadsInARow >= MAX_EMPTY_LOADS },
            )
        }

    private companion object {
        const val MAX_EMPTY_LOADS = 2
    }
}

/** One page of a feed tab: the first when [continuation] is null, otherwise the page it names. */
fun interface FeedTabPageLoader {
    suspend operator fun invoke(
        continuation: String?,
        owner: FeedItemOwner,
    ): Result<ChannelTabContent>
}

private fun FeedItem.pagingKey(): String =
    when (this) {
        is FeedItem.VideoItem -> video.id
        is FeedItem.ShortItem -> video.id
        is FeedItem.PlaylistItem -> playlist.id
        is FeedItem.RelatedChannelItem -> channel.id
        is FeedItem.PostItem -> post.id
    }
