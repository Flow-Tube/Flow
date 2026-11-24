package io.github.aedev.flow.data.community

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.innertube.pages.VideoCommentSort
import io.github.aedev.flow.innertube.pages.renderer.CommunityCommentsPage
import io.github.aedev.flow.innertube.pages.renderer.CommunityPost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostCommentsLoaderTest {
    private val dispatcher = StandardTestDispatcher()
    private val requestedPosts = mutableListOf<Pair<String, String?>>()
    private val requestedContinuations = mutableListOf<String>()
    private val firstPages = mutableMapOf<String, CompletableDeferred<Result<CommunityCommentsPage>>>()
    private val continuations = mutableMapOf<String, Result<CommunityCommentsPage>>()

    private fun TestScope.loader() =
        PostCommentsLoader(
            scope = this,
            dispatcher = dispatcher,
            fetchFirstPage = { id, params ->
                requestedPosts += id to params
                firstPages.getOrPut(id) { CompletableDeferred() }.await()
            },
            fetchContinuation = { token ->
                requestedContinuations += token
                continuations.getValue(token)
            },
        )

    @Test
    fun `opening a post loads its first page with the post's params`() =
        runTest(dispatcher) {
            val loader = loader()
            firstPages["p1"] = CompletableDeferred(Result.success(page(listOf(comment("c1")), "next")))

            loader.open(post("p1", params = "abc"))
            assertThat(loader.state.value.isLoading).isTrue()
            advanceUntilIdle()

            assertThat(requestedPosts).containsExactly("p1" to "abc")
            val state = loader.state.value
            assertThat(state.activePost?.id).isEqualTo("p1")
            assertThat(state.comments.map(Comment::id)).containsExactly("c1")
            assertThat(state.continuation).isEqualTo("next")
            assertThat(state.isLoading).isFalse()
        }

    @Test
    fun `a page that lands after another post opened is dropped`() =
        runTest(dispatcher) {
            val loader = loader()
            val slow = CompletableDeferred<Result<CommunityCommentsPage>>()
            firstPages["p1"] = slow
            firstPages["p2"] = CompletableDeferred(Result.success(page(listOf(comment("c2")))))

            loader.open(post("p1"))
            advanceUntilIdle()
            loader.open(post("p2"))
            advanceUntilIdle()
            slow.complete(Result.success(page(listOf(comment("c1")))))
            advanceUntilIdle()

            assertThat(loader.state.value.activePost).isEqualTo(post("p2"))
            assertThat(loader.commentIds()).containsExactly("c2")
        }

    @Test
    fun `more comments append without duplicates and a failure only clears the spinner`() =
        runTest(dispatcher) {
            val loader = loader()
            firstPages["p1"] = CompletableDeferred(Result.success(page(listOf(comment("c1")), "t1")))
            continuations["t1"] = Result.success(page(listOf(comment("c1"), comment("c2")), "t2"))
            continuations["t2"] = Result.failure(IllegalStateException("offline"))

            loader.open(post("p1"))
            advanceUntilIdle()
            loader.loadMore()
            advanceUntilIdle()
            assertThat(loader.commentIds()).containsExactly("c1", "c2").inOrder()

            loader.loadMore()
            advanceUntilIdle()
            assertThat(loader.state.value.comments).hasSize(2)
            assertThat(loader.state.value.continuation).isEqualTo("t2")
            assertThat(loader.state.value.isLoadingMore).isFalse()
        }

    @Test
    fun `replies replace the thread and then append to it`() =
        runTest(dispatcher) {
            val loader = loader()
            val parent = comment("c1", continuation = "r1")
            firstPages["p1"] = CompletableDeferred(Result.success(page(listOf(parent))))
            continuations["r1"] = Result.success(page(listOf(comment("a")), "r2"))
            continuations["r2"] = Result.success(page(listOf(comment("a"), comment("b"))))

            loader.open(post("p1"))
            advanceUntilIdle()
            loader.loadReplies(parent)
            advanceUntilIdle()
            loader.loadMoreReplies(loader.thread())
            advanceUntilIdle()

            val thread = loader.thread()
            assertThat(thread.replies.map(Comment::id)).containsExactly("a", "b").inOrder()
            assertThat(thread.continuationToken).isNull()
            assertThat(thread.replyCount).isEqualTo(2)
            assertThat(requestedContinuations).containsExactly("r1", "r2").inOrder()
        }

    @Test
    fun `closing forgets the post`() =
        runTest(dispatcher) {
            val loader = loader()
            firstPages["p1"] = CompletableDeferred(Result.success(page(listOf(comment("c1")))))
            loader.open(post("p1"))
            advanceUntilIdle()

            loader.close()

            assertThat(loader.state.value).isEqualTo(PostCommentsState())
        }

    @Test
    fun `a sort from the post's menu reloads its comments in that order`() =
        runTest(dispatcher) {
            val loader = loader()
            val top = VideoCommentSort("Top", "top", selected = true)
            val newest = VideoCommentSort("Newest", "newest", selected = false)
            firstPages["p1"] =
                CompletableDeferred(Result.success(page(listOf(comment("top1")), "t1").copy(sortOptions = listOf(top, newest))))
            continuations["newest"] = Result.success(page(listOf(comment("new1")), "n2"))

            loader.open(post("p1"))
            advanceUntilIdle()
            assertThat(loader.state.value.sortOptions).containsExactly(top, newest).inOrder()

            loader.selectSort(newest)
            assertThat(loader.state.value.isLoading).isTrue()
            advanceUntilIdle()

            assertThat(loader.commentIds()).containsExactly("new1")
            assertThat(loader.state.value.continuation).isEqualTo("n2")
            assertThat(loader.state.value.sortOptions).containsExactly(top, newest).inOrder()
            loader.selectSort(newest)
            assertThat(requestedContinuations).containsExactly("newest")
        }

    @Test
    fun `a page of the old order that lands after a sort change is dropped`() =
        runTest(dispatcher) {
            val loader = loader()
            val newest = VideoCommentSort("Newest", "newest", selected = false)
            firstPages["p1"] = CompletableDeferred(Result.success(page(listOf(comment("top1")), "t1")))
            continuations["t1"] = Result.success(page(listOf(comment("top2"))))
            continuations["newest"] = Result.success(page(listOf(comment("new1"))))

            loader.open(post("p1"))
            advanceUntilIdle()
            loader.loadMore()
            loader.selectSort(newest)
            advanceUntilIdle()

            assertThat(loader.commentIds()).containsExactly("new1")
        }

    @Test
    fun `oldest reads the post's newest order to its end before showing it`() =
        runTest(dispatcher) {
            val loader = loader()
            firstPages["p1"] = CompletableDeferred(Result.success(page(listOf(comment("top1")), "t1")))
            continuations["newest"] = Result.success(page(listOf(comment("n1")), "n2"))
            continuations["n2"] = Result.success(page(listOf(comment("n2"))))

            loader.open(post("p1"))
            advanceUntilIdle()
            loader.selectOldest(VideoCommentSort("Newest", "newest", selected = false))
            assertThat(loader.state.value.isLoading).isTrue()
            advanceUntilIdle()

            assertThat(loader.commentIds()).containsExactly("n1", "n2").inOrder()
            assertThat(loader.state.value.continuation).isNull()
            assertThat(loader.state.value.oldestCapped).isFalse()
            assertThat(loader.state.value.isLoading).isFalse()
        }

    private fun PostCommentsLoader.commentIds() = state.value.comments.map(Comment::id)

    private fun PostCommentsLoader.thread() = state.value.comments.single()

    private fun post(
        id: String,
        params: String? = null,
    ) = CommunityPost(
        id = id,
        authorName = "Author",
        authorAvatarUrl = "",
        text = "",
        attachment = null,
        likeCountText = "",
        commentCountText = "",
        commentEndpointParams = params,
        publishedTimeText = "1 day ago",
    )

    private fun comment(
        id: String,
        continuation: String? = null,
    ) = Comment(
        id = id,
        author = "a",
        authorThumbnail = "",
        text = "",
        likeCount = 0,
        publishedTime = "",
        continuationToken = continuation,
    )

    private fun page(
        comments: List<Comment>,
        continuation: String? = null,
    ) = CommunityCommentsPage(comments = comments, continuation = continuation)
}
