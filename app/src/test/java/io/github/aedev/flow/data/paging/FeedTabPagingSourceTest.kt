package io.github.aedev.flow.data.paging

import androidx.paging.PagingSource
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.pages.channel.ChannelTabContent
import io.github.aedev.flow.innertube.pages.channel.ChannelTabKind
import io.github.aedev.flow.innertube.pages.renderer.FeedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FeedTabPagingSourceTest {
    private fun video(id: String) =
        FeedItem.VideoItem(
            Video(
                id = id,
                title = id,
                channelName = "c",
                channelId = "UCc",
                thumbnailUrl = "",
                duration = 60,
                viewCount = 1,
                uploadDate = "",
            ),
        )

    private class PageScript(
        private val pages: Map<String?, ChannelTabContent>,
    ) {
        val requested = mutableListOf<String?>()

        val loader =
            FeedTabPageLoader { continuation, _ ->
                requested += continuation
                Result.success(pages.getValue(continuation))
            }
    }

    private fun page(
        continuation: String?,
        vararg ids: String,
    ) = ChannelTabContent(kind = ChannelTabKind.Videos, items = ids.map(::video), continuation = continuation)

    private fun source(
        script: PageScript,
        sortToken: String? = null,
    ) = FeedTabPagingSource(
        browseId = "UCc",
        params = "videos",
        kind = ChannelTabKind.Videos,
        sortToken = sortToken,
        loadPage = script.loader,
        loadContext = Dispatchers.Unconfined,
    )

    private suspend fun FeedTabPagingSource.loadPage(key: String? = null): PagingSource.LoadResult.Page<String, FeedItem> {
        val params =
            if (key == null) {
                PagingSource.LoadParams.Refresh<String>(null, 20, false)
            } else {
                PagingSource.LoadParams.Append(key, 20, false)
            }
        return load(params) as PagingSource.LoadResult.Page
    }

    @Test
    fun `hands the next token back as the key`() =
        runTest {
            val script = PageScript(mapOf(null to page("t1", "a"), "t1" to page("t2", "b")))
            val paging = source(script)

            val first = paging.loadPage()
            val second = paging.loadPage(first.nextKey)

            assertThat(first.nextKey).isEqualTo("t1")
            assertThat(second.nextKey).isEqualTo("t2")
            assertThat(second.data).containsExactly(video("b"))
        }

    @Test
    fun `a page that hands back its own token ends the list`() =
        runTest {
            val script = PageScript(mapOf(null to page("t1", "a"), "t1" to page("t1", "b")))
            val paging = source(script)

            val second = paging.loadPage(paging.loadPage().nextKey)

            assertThat(second.data).containsExactly(video("b"))
            assertThat(second.nextKey).isNull()
        }

    @Test
    fun `a sort chip page that names its own token ends the list`() =
        runTest {
            val script = PageScript(mapOf("chip" to page("chip", "a")))

            val first = source(script, sortToken = "chip").loadPage()

            assertThat(script.requested).containsExactly("chip")
            assertThat(first.nextKey).isNull()
        }

    @Test
    fun `two pages in a row with nothing new end the list`() =
        runTest {
            val script =
                PageScript(
                    mapOf(
                        null to page("t1", "a"),
                        "t1" to page("t2", "a"),
                        "t2" to page("t3"),
                    ),
                )
            val paging = source(script)

            val first = paging.loadPage()
            val second = paging.loadPage(first.nextKey)
            val third = paging.loadPage(second.nextKey)

            assertThat(second.data).isEmpty()
            assertThat(second.nextKey).isEqualTo("t2")
            assertThat(third.nextKey).isNull()
        }

    @Test
    fun `an empty page between full ones keeps the list going`() =
        runTest {
            val script =
                PageScript(
                    mapOf(
                        null to page("t1", "a"),
                        "t1" to page("t2"),
                        "t2" to page("t3", "b"),
                        "t3" to page("t4"),
                    ),
                )
            val paging = source(script)

            var key: String? = null
            repeat(4) { key = paging.loadPage(key).nextKey }

            assertThat(key).isEqualTo("t4")
        }
}
