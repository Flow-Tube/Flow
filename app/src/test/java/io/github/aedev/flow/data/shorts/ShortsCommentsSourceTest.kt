package io.github.aedev.flow.data.shorts

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.comments.CommentsPageResult
import io.github.aedev.flow.data.comments.CommentsSource
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.innertube.pages.ReelCommentsPanel
import io.github.aedev.flow.innertube.pages.VideoCommentSort
import io.github.aedev.flow.innertube.pages.VideoCommentsPage
import io.github.aedev.flow.innertube.pages.VideoCommentsPanelHeader
import io.github.aedev.flow.innertube.pages.VideoCommentsSection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ShortsCommentsSourceTest {
    private val browsed = mutableListOf<String>()
    private val replies = mutableListOf<String>()
    private var panel: ReelCommentsPanel? =
        ReelCommentsPanel(
            section = VideoCommentsSection.Open("first"),
            header =
                VideoCommentsPanelHeader(
                    sortOptions = listOf(VideoCommentSort("Top", "top", true), VideoCommentSort("Newest", "newest", false)),
                    totalText = "145",
                    totalCount = 145L,
                ),
        )
    private val fallback: CommentsSource =
        mockk {
            coEvery { firstPage(any(), any()) } returns CommentsPageResult(comments = listOf(comment("watch")))
            coEvery { timed(any(), any()) } returns CommentsPageResult(comments = listOf(comment("timed")))
        }

    private fun comment(id: String) = Comment(id = id, author = "a", authorThumbnail = "", text = id, likeCount = 0, publishedTime = "")

    private val source =
        ShortsCommentsSource(
            panelFor = { panel },
            page = { token, _ ->
                browsed += token
                VideoCommentsPage(comments = listOf(comment("c_$token")), continuation = "$token+1")
            },
            replyPage = { token, _ ->
                replies += token
                VideoCommentsPage(comments = listOf(comment("r_$token")), continuation = null)
            },
            fallback = fallback,
        )

    @Test
    fun `the first page loads through browse with the panel's header`() =
        runTest {
            val page = source.firstPage("reel", sortToken = null)

            assertThat(browsed).containsExactly("first")
            assertThat(page.comments.map { it.id }).containsExactly("c_first")
            assertThat(page.continuation).isEqualTo("first+1")
            assertThat(page.sortOptions.map { it.token }).containsExactly("top", "newest").inOrder()
            assertThat(page.totalCount).isEqualTo(145L)
            coVerify(exactly = 0) { fallback.firstPage(any(), any()) }
        }

    @Test
    fun `reopening the sheet on a reel reads its first page from memory`() =
        runTest {
            source.firstPage("reel", sortToken = null)
            source.firstPage("reel", sortToken = null)

            assertThat(browsed).containsExactly("first")
        }

    @Test
    fun `later pages and replies of a reel go through browse too`() =
        runTest {
            source.firstPage("reel", sortToken = null)
            source.nextPage("reel", "first+1")
            source.replies("reel", "thread")

            assertThat(browsed).containsExactly("first", "first+1").inOrder()
            assertThat(replies).containsExactly("thread")
        }

    @Test
    fun `a reel never asks the watch-page Timed list`() =
        runTest {
            assertThat(source.timed("reel", "top").comments).isEmpty()
            coVerify(exactly = 0) { fallback.timed(any(), any()) }
        }

    @Test
    fun `comments turned off on a reel cost no request`() =
        runTest {
            panel = ReelCommentsPanel(section = VideoCommentsSection.TurnedOff, header = null)

            assertThat(source.firstPage("reel", sortToken = null).disabled).isTrue()
            assertThat(browsed).isEmpty()
        }

    @Test
    fun `a reel without a panel falls back to the watch page`() =
        runTest {
            panel = null

            assertThat(source.firstPage("reel", sortToken = null).comments.map { it.id }).containsExactly("watch")
            assertThat(source.timed("reel", "token").comments.map { it.id }).containsExactly("timed")
            assertThat(browsed).isEmpty()
        }
}
