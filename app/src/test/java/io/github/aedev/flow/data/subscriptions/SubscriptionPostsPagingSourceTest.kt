package io.github.aedev.flow.data.subscriptions

import androidx.paging.PagingSource
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.pages.renderer.CommunityPost
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SubscriptionPostsPagingSourceTest {
    private val channels = (1..20).map { PostsChannel(id = "c$it", name = "c$it", avatarUrl = "") }
    private val rounds = mutableListOf<List<String>>()
    private var channelReads = 0
    private var failAll = false

    private val source =
        SubscriptionPostsPagingSource(
            loadChannels = {
                channelReads++
                channels
            },
            loadRound = { batch ->
                rounds += batch.map(PostsChannel::id)
                SubscriptionPostsRound(
                    posts = batch.map { SubscriptionPost(post(it.id), it.id, publishedAt = 0L) },
                    allFailed = failAll,
                )
            },
        )

    private suspend fun load(key: Int?) = source.load(PagingSource.LoadParams.Append(key ?: 0, loadSize = 8, placeholdersEnabled = false))

    @Test
    fun `each page is one round of eight channels in order`() =
        runTest {
            val first = load(0) as PagingSource.LoadResult.Page
            val second = load(first.nextKey) as PagingSource.LoadResult.Page
            val last = load(second.nextKey) as PagingSource.LoadResult.Page

            assertThat(rounds.map { it.size }).containsExactly(8, 8, 4).inOrder()
            assertThat(rounds.first()).containsExactly("c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8").inOrder()
            assertThat(first.nextKey).isEqualTo(1)
            assertThat(second.nextKey).isEqualTo(2)
            assertThat(last.nextKey).isNull()
            assertThat(last.data.map { it.channelId }).containsExactly("c17", "c18", "c19", "c20").inOrder()
        }

    @Test
    fun `the channel order is read once per generation`() =
        runTest {
            load(0)
            load(1)

            assertThat(channelReads).isEqualTo(1)
        }

    @Test
    fun `a round where every channel failed is an error, not an empty page`() =
        runTest {
            failAll = true

            assertThat(load(0)).isInstanceOf(PagingSource.LoadResult.Error::class.java)
        }

    @Test
    fun `no channels is an empty last page without a round`() =
        runTest {
            val empty = SubscriptionPostsPagingSource(loadChannels = { emptyList() }, loadRound = { error("no round") })

            val page = empty.load(PagingSource.LoadParams.Refresh(null, 8, false)) as PagingSource.LoadResult.Page

            assertThat(page.data).isEmpty()
            assertThat(page.nextKey).isNull()
        }

    private fun post(id: String) =
        CommunityPost(
            id = "post-$id",
            authorName = id,
            authorAvatarUrl = "",
            text = "",
            attachment = null,
            likeCountText = "",
            commentCountText = "",
            commentEndpointParams = null,
            publishedTimeText = "1 day ago",
        )
}
