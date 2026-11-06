package io.github.aedev.flow.data.comments

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.innertube.pages.VideoCommentSort
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.schabi.newpipe.extractor.Page

/**
 * Pins the paging contract the player and Shorts now share: one request per video in flight, a
 * deduplicated list, an answer that is dropped when the surface has moved on, and a sort that is a
 * different continuation rather than a re-ordering of what is loaded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommentsPagerTest {
    private val testDispatcher = StandardTestDispatcher()
    private val repository: YouTubeRepository = mockk(relaxed = true)

    private fun comment(
        id: String,
        text: String = "text $id",
        repliesPage: Page? = null,
        continuationToken: String? = null,
        replies: List<Comment> = emptyList(),
    ) = Comment(
        id = id,
        author = "author",
        authorThumbnail = "",
        text = text,
        likeCount = 0,
        publishedTime = "",
        replies = replies,
        repliesPage = repliesPage,
        continuationToken = continuationToken,
    )

    private fun pager(
        scope: CoroutineScope,
        playbackState: MutableStateFlow<CommentsPlaybackState> = MutableStateFlow(CommentsPlaybackState.READY),
        isCurrentVideo: (String) -> Boolean = { true },
        fetchTimeoutMs: Long? = null,
        prefetchPages: Int = 0,
    ) = CommentsPager(
        repository = repository,
        scope = scope,
        playbackState = playbackState,
        isCurrentVideo = isCurrentVideo,
        fetchTimeoutMs = fetchTimeoutMs,
        prefetchPages = prefetchPages,
    )

    @Test
    fun `the first page is deduplicated by comment id and reports whether more is available`() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher)
            coEvery { repository.getVideoComments("vid_a", null) } returns
                CommentsPageResult(
                    comments = listOf(comment("c1"), comment("c1", text = "duplicate"), comment("c2")),
                    continuation = "page_2",
                )
            val pager = pager(scope)

            pager.load("vid_a")
            advanceUntilIdle()

            assertThat(pager.comments.value.map { it.id }).containsExactly("c1", "c2").inOrder()
            assertThat(
                pager.comments.value
                    .first()
                    .text,
            ).isEqualTo("text c1")
            assertThat(pager.hasMore.value).isTrue()
            assertThat(pager.isLoading.value).isFalse()
            scope.cancel()
        }

    @Test
    fun `the header count and the sort menu are published from the first page`() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher)
            coEvery { repository.getVideoComments("vid_a", null) } returns
                CommentsPageResult(
                    comments = listOf(comment("c1")),
                    sortOptions =
                        listOf(
                            VideoCommentSort("Top", "top_token", selected = true),
                            VideoCommentSort("Newest", "newest_token", selected = false),
                        ),
                    totalText = "699K",
                    totalCount = 699_091L,
                )
            val pager = pager(scope)

            pager.load("vid_a")
            advanceUntilIdle()

            assertThat(pager.sortOptions.value.map { it.title }).containsExactly("Top", "Newest").inOrder()
            assertThat(pager.totalText.value).isEqualTo("699K")
            assertThat(pager.totalCount.value).isEqualTo(699_091L)
            scope.cancel()
        }

    @Test
    fun `the next video does not keep the count or sort menu of the one before`() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher)
            coEvery { repository.getVideoComments("vid_a", null) } returns
                CommentsPageResult(
                    comments = listOf(comment("c1")),
                    sortOptions = listOf(VideoCommentSort("Top", "top_token", selected = true)),
                    totalText = "246",
                    totalCount = 246L,
                )
            coEvery { repository.getVideoComments("vid_b", null) } returns
                CommentsPageResult(comments = listOf(comment("c2")))
            val pager = pager(scope)

            pager.load("vid_a")
            advanceUntilIdle()
            pager.load("vid_b")
            advanceUntilIdle()

            assertThat(pager.totalText.value).isNull()
            assertThat(pager.totalCount.value).isNull()
            assertThat(pager.sortOptions.value).isEmpty()
            scope.cancel()
        }

    @Test
    fun `selecting a sort reloads the section through that continuation`() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher)
            coEvery { repository.getVideoComments("vid_a", null) } returns
                CommentsPageResult(comments = listOf(comment("top_1")))
            coEvery { repository.getVideoComments("vid_a", "newest_token") } returns
                CommentsPageResult(comments = listOf(comment("newest_1")))
            val pager = pager(scope)

            pager.load("vid_a")
            advanceUntilIdle()
            pager.selectSort("vid_a", VideoCommentSort("Newest", "newest_token", selected = false))
            advanceUntilIdle()

            assertThat(pager.comments.value.map { it.id }).containsExactly("newest_1")
            coVerify(exactly = 1) { repository.getVideoComments("vid_a", "newest_token") }
            scope.cancel()
        }

    @Test
    fun `the sort menu survives a page that does not repeat it`() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher)
            coEvery { repository.getVideoComments("vid_a", null) } returns
                CommentsPageResult(
                    comments = listOf(comment("c1")),
                    sortOptions = listOf(VideoCommentSort("Top", "top_token", selected = true)),
                )
            coEvery { repository.getVideoComments("vid_a", "top_token") } returns
                CommentsPageResult(comments = listOf(comment("c2")))
            val pager = pager(scope)

            pager.load("vid_a")
            advanceUntilIdle()
            pager.selectSort("vid_a", VideoCommentSort("Top", "top_token", selected = true))
            advanceUntilIdle()

            assertThat(pager.sortOptions.value.map { it.title }).containsExactly("Top")
            scope.cancel()
        }

    @Test
    fun `loadMore appends the next page without repeating a comment`() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher)
            coEvery { repository.getVideoComments("vid_a", null) } returns
                CommentsPageResult(comments = listOf(comment("c1")), continuation = "page_2")
            coEvery { repository.getMoreVideoComments("vid_a", "page_2") } returns
                CommentsPageResult(comments = listOf(comment("c1"), comment("c2")))
            val pager = pager(scope)

            pager.load("vid_a")
            advanceUntilIdle()
            pager.loadMore("vid_a")
            advanceUntilIdle()

            assertThat(pager.comments.value.map { it.id }).containsExactly("c1", "c2").inOrder()
            assertThat(pager.hasMore.value).isFalse()
            assertThat(pager.isLoadingMore.value).isFalse()
            scope.cancel()
        }

    @Test
    fun `loadMore without a continuation from the first fetch never reaches the repository`() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher)
            coEvery { repository.getVideoComments("vid_a", null) } returns
                CommentsPageResult(comments = listOf(comment("c1")))
            val pager = pager(scope)

            pager.load("vid_a")
            advanceUntilIdle()
            pager.loadMore("vid_a")
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.getMoreVideoComments(any(), any()) }
            scope.cancel()
        }

    @Test
    fun `the prefetch walks a bounded number of further pages and then stops`() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher)
            coEvery { repository.getVideoComments("vid_a", null) } returns
                CommentsPageResult(comments = listOf(comment("c1")), continuation = "page_2")
            coEvery { repository.getMoreVideoComments("vid_a", "page_2") } returns
                CommentsPageResult(comments = listOf(comment("c2")), continuation = "page_3")
            coEvery { repository.getMoreVideoComments("vid_a", "page_3") } returns
                CommentsPageResult(comments = listOf(comment("c3")), continuation = "page_4")
            coEvery { repository.getMoreVideoComments("vid_a", "page_4") } returns
                CommentsPageResult(comments = listOf(comment("c4")), continuation = "page_5")
            val pager = pager(scope, prefetchPages = 2)

            pager.load("vid_a")
            advanceUntilIdle()

            assertThat(pager.comments.value.map { it.id }).containsExactly("c1", "c2", "c3").inOrder()
            coVerify(exactly = 0) { repository.getMoreVideoComments("vid_a", "page_4") }
            assertThat(pager.hasMore.value).isTrue()
            scope.cancel()
        }

    @Test
    fun `the prefetch stops at the end of the section`() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher)
            coEvery { repository.getVideoComments("vid_a", null) } returns
                CommentsPageResult(comments = listOf(comment("c1")), continuation = "page_2")
            coEvery { repository.getMoreVideoComments("vid_a", "page_2") } returns
                CommentsPageResult(comments = listOf(comment("c2")))
            val pager = pager(scope, prefetchPages = 3)

            pager.load("vid_a")
            advanceUntilIdle()

            assertThat(pager.comments.value.map { it.id }).containsExactly("c1", "c2").inOrder()
            assertThat(pager.hasMore.value).isFalse()
            coVerify(exactly = 1) { repository.getMoreVideoComments("vid_a", "page_2") }
            scope.cancel()
        }

    @Test
    fun `timed reads both orders in turn, up to its budget, into one list`() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher)
            val orders = listOf(VideoCommentSort("Newest", "newest", false), VideoCommentSort("Top", "top", true))
            coEvery { repository.getMoreVideoComments("v", any()) } answers {
                val token = secondArg<String>()
                CommentsPageResult(comments = listOf(comment(token), comment("shared")), continuation = "$token+")
            }
            val pager = pager(scope)

            pager.selectTimed("v", orders)
            advanceUntilIdle()

            coVerify(exactly = 8) { repository.getMoreVideoComments("v", match { it.startsWith("newest") }) }
            coVerify(exactly = 8) { repository.getMoreVideoComments("v", match { it.startsWith("top") }) }
            assertThat(pager.comments.value.count { it.id == "shared" }).isEqualTo(1)
            assertThat(pager.isLoading.value).isFalse()
            assertThat(pager.isLoadingMore.value).isFalse()
            assertThat(pager.hasMore.value).isTrue()

            pager.selectTimed("v", orders)
            advanceUntilIdle()
            coVerify(exactly = 16) { repository.getMoreVideoComments("v", any()) }

            pager.loadMore("v")
            advanceUntilIdle()
            coVerify(exactly = 20) { repository.getMoreVideoComments("v", any()) }
            scope.cancel()
        }

    @Test
    fun `timed stops at the end of each order`() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher)
            coEvery { repository.getMoreVideoComments("v", "newest") } returns CommentsPageResult(comments = listOf(comment("a")))
            coEvery { repository.getMoreVideoComments("v", "top") } returns CommentsPageResult(comments = listOf(comment("b")))
            val pager = pager(scope)

            pager.selectTimed("v", listOf(VideoCommentSort("Newest", "newest", false), VideoCommentSort("Top", "top", true)))
            advanceUntilIdle()

            coVerify(exactly = 2) { repository.getMoreVideoComments("v", any()) }
            assertThat(pager.hasMore.value).isFalse()
            assertThat(pager.comments.value.map { it.id }).containsExactly("a", "b")
            scope.cancel()
        }

    @Test
    fun `another sort cancels what timed had left to read`() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher)
            val gate = CompletableDeferred<Unit>()
            coEvery { repository.getMoreVideoComments("v", any()) } coAnswers {
                gate.await()
                CommentsPageResult(comments = listOf(comment("late")), continuation = "more")
            }
            coEvery { repository.getVideoComments("v", "top") } returns CommentsPageResult(comments = listOf(comment("top")))
            val pager = pager(scope)
            val top = VideoCommentSort("Top", "top", true)

            pager.selectTimed("v", listOf(VideoCommentSort("Newest", "newest", false), top))
            runCurrent()
            pager.selectSort("v", top)
            gate.complete(Unit)
            advanceUntilIdle()

            assertThat(pager.comments.value.map { it.id }).containsExactly("top")
            assertThat(pager.isLoadingMore.value).isFalse()
            scope.cancel()
        }

    @Test
    fun `a repeat request for the same video while the first is in flight is dropped`() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher)
            val gate = CompletableDeferred<Unit>()
            coEvery { repository.getVideoComments("vid_a", null) } coAnswers {
                gate.await()
                CommentsPageResult(comments = listOf(comment("c1")))
            }
            val pager = pager(scope)

            pager.load("vid_a")
            runCurrent()
            pager.load("vid_a")
            pager.load("vid_a")
            runCurrent()

            coVerify(exactly = 1) { repository.getVideoComments("vid_a", null) }
            assertThat(pager.isLoading.value).isTrue()

            gate.complete(Unit)
            advanceUntilIdle()
            assertThat(pager.comments.value.map { it.id }).containsExactly("c1")
            assertThat(pager.isLoading.value).isFalse()
            scope.cancel()
        }

    @Test
    fun `a request for another video resets the list and supersedes the one in flight`() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher)
            val gateA = CompletableDeferred<Unit>()
            coEvery { repository.getVideoComments("vid_a", null) } coAnswers {
                gateA.await()
                CommentsPageResult(comments = listOf(comment("c_a")))
            }
            coEvery { repository.getVideoComments("vid_b", null) } returns
                CommentsPageResult(comments = listOf(comment("c_b")))
            val pager = pager(scope)

            pager.load("vid_a")
            runCurrent()
            pager.load("vid_b")
            advanceUntilIdle()

            assertThat(pager.comments.value.map { it.id }).containsExactly("c_b")
            assertThat(pager.isLoading.value).isFalse()

            gateA.complete(Unit)
            advanceUntilIdle()
            assertThat(pager.comments.value.map { it.id }).containsExactly("c_b")
            scope.cancel()
        }

    @Test
    fun `a result for a video the owner no longer shows never reaches the list`() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher)
            coEvery { repository.getVideoComments("vid_a", null) } returns
                CommentsPageResult(comments = listOf(comment("c1")))
            val pager = pager(scope, isCurrentVideo = { false })

            pager.load("vid_a")
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.getVideoComments(any(), any()) }
            assertThat(pager.comments.value).isEmpty()
            assertThat(pager.isLoading.value).isFalse()
            scope.cancel()
        }

    @Test
    fun `the startup gate holds the fetch until playback stops competing for the same video`() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher)
            val playbackState =
                MutableStateFlow(CommentsPlaybackState(isPlaybackLoading = true, currentVideoId = "vid_a"))
            coEvery { repository.getVideoComments("vid_a", null) } returns
                CommentsPageResult(comments = listOf(comment("c1")))
            val pager = pager(scope, playbackState = playbackState)

            pager.load("vid_a")
            runCurrent()
            coVerify(exactly = 0) { repository.getVideoComments(any(), any()) }

            playbackState.value = CommentsPlaybackState(isPlaybackLoading = false, currentVideoId = "vid_a")
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.getVideoComments("vid_a", null) }
            assertThat(pager.comments.value.map { it.id }).containsExactly("c1")
            scope.cancel()
        }

    @Test
    fun `a fetch that outruns the timeout leaves the list empty and stops loading`() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher)
            coEvery { repository.getVideoComments("vid_a", null) } coAnswers {
                CompletableDeferred<Unit>().await()
                CommentsPageResult(comments = listOf(comment("c1")))
            }
            val pager = pager(scope, fetchTimeoutMs = 10_000L)

            pager.load("vid_a")
            advanceUntilIdle()

            assertThat(pager.comments.value).isEmpty()
            assertThat(pager.isLoading.value).isFalse()
            scope.cancel()
        }

    @Test
    fun `replies replace the comment's list and a repeat while in flight is dropped`() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher)
            val parent = comment("c1", continuationToken = "replies_token")
            coEvery { repository.getVideoComments("vid_a", null) } returns
                CommentsPageResult(comments = listOf(parent))
            val gate = CompletableDeferred<Unit>()
            coEvery { repository.getVideoCommentReplies("vid_a", "replies_token") } coAnswers {
                gate.await()
                CommentsPageResult(comments = listOf(comment("r1"), comment("r1"), comment("r2")))
            }
            val pager = pager(scope)

            pager.load("vid_a")
            advanceUntilIdle()

            pager.loadReplies("vid_a", parent)
            runCurrent()
            pager.loadReplies("vid_a", parent)
            runCurrent()
            coVerify(exactly = 1) { repository.getVideoCommentReplies("vid_a", "replies_token") }

            gate.complete(Unit)
            advanceUntilIdle()
            assertThat(
                pager.comments.value
                    .single()
                    .replies
                    .map { it.id },
            ).containsExactly("r1", "r2").inOrder()
            assertThat(
                pager.comments.value
                    .single()
                    .continuationToken,
            ).isNull()
            scope.cancel()
        }

    @Test
    fun `loadMoreReplies merges the next page into the replies already shown`() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher)
            val parent = comment("c1", continuationToken = "replies_token", replies = listOf(comment("r1")))
            coEvery { repository.getVideoComments("vid_a", null) } returns
                CommentsPageResult(comments = listOf(parent))
            coEvery { repository.getVideoCommentReplies("vid_a", "replies_token") } returns
                CommentsPageResult(comments = listOf(comment("r1"), comment("r2")))
            val pager = pager(scope)

            pager.load("vid_a")
            advanceUntilIdle()
            pager.loadMoreReplies("vid_a", parent)
            advanceUntilIdle()

            assertThat(
                pager.comments.value
                    .single()
                    .replies
                    .map { it.id },
            ).containsExactly("r1", "r2").inOrder()
            scope.cancel()
        }

    @Test
    fun `a fallback page keeps paging and its replies on the extractor`() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher)
            val firstPage = Page("https://example.invalid/next")
            val repliesPage = Page("https://example.invalid/replies")
            val parent = comment("c1", repliesPage = repliesPage)
            coEvery { repository.getVideoComments("vid_a", null) } returns
                CommentsPageResult(comments = listOf(parent), legacyPage = firstPage)
            coEvery { repository.getMoreComments("vid_a", firstPage) } returns (listOf(comment("c2")) to null)
            coEvery { repository.getCommentReplies(any(), repliesPage) } returns (listOf(comment("r1")) to null)
            val pager = pager(scope)

            pager.load("vid_a")
            advanceUntilIdle()
            assertThat(pager.hasMore.value).isTrue()

            pager.loadMore("vid_a")
            advanceUntilIdle()
            assertThat(pager.comments.value.map { it.id }).containsExactly("c1", "c2").inOrder()
            assertThat(pager.hasMore.value).isFalse()

            pager.loadReplies("vid_a", parent)
            advanceUntilIdle()
            assertThat(
                pager.comments.value
                    .first()
                    .replies
                    .map { it.id },
            ).containsExactly("r1")
            coVerify(exactly = 0) { repository.getVideoCommentReplies(any(), any()) }
            scope.cancel()
        }

    @Test
    fun `clear empties the list and cancels the request in flight`() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher)
            val gate = CompletableDeferred<Unit>()
            coEvery { repository.getVideoComments("vid_a", null) } coAnswers {
                gate.await()
                CommentsPageResult(comments = listOf(comment("c1")))
            }
            val pager = pager(scope)

            pager.load("vid_a")
            runCurrent()
            pager.clear()

            assertThat(pager.comments.value).isEmpty()
            assertThat(pager.isLoading.value).isFalse()
            assertThat(pager.hasMore.value).isFalse()
            assertThat(pager.sortOptions.value).isEmpty()
            assertThat(pager.totalText.value).isNull()

            gate.complete(Unit)
            advanceUntilIdle()
            assertThat(pager.comments.value).isEmpty()
            scope.cancel()
        }
}
