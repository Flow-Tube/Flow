package io.github.aedev.flow.ui.screens.playlists

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RemotePlaylistPagerTest {
    private fun video(id: String) = Video(id, id, "", "", "", 0, 0L, uploadDate = "")

    private fun videos(vararg ids: String) = ids.map(::video)

    private class Pages(
        val byToken: Map<String, Pair<List<Video>, String?>>,
    ) {
        val asked = mutableListOf<String>()
        var failNext = false

        suspend fun load(token: String): Pair<List<Video>, String?>? {
            asked += token
            if (failNext) {
                failNext = false
                return null
            }
            return byToken[token]
        }
    }

    @Test
    fun `loads one page at a time and remembers the token after each`() =
        runTest {
            val pages = Pages(mapOf("t2" to (videos("c", "d") to "t3"), "t3" to (videos("e") to null)))
            val pager = RemotePlaylistPager(videos("a", "b"), "t2", maxPages = 60, loadPage = pages::load)

            assertThat(pager.tokenAfter(2)).isEqualTo("t2")
            assertThat(pager.loadNext()).isTrue()
            assertThat(pager.videos.map { it.id }).containsExactly("a", "b", "c", "d").inOrder()
            assertThat(pager.tokenAfter(4)).isEqualTo("t3")
            assertThat(pages.asked).containsExactly("t2")
            assertThat(pager.isComplete).isFalse()
        }

    @Test
    fun `loadAll reads every page left and ends complete`() =
        runTest {
            val pages = Pages(mapOf("t2" to (videos("c") to "t3"), "t3" to (videos("d") to null)))
            val pager = RemotePlaylistPager(videos("a", "b"), "t2", maxPages = 60, loadPage = pages::load)
            val seen = mutableListOf<Int>()

            assertThat(pager.loadAll { seen += it.size }).isTrue()

            assertThat(seen).containsExactly(3, 4).inOrder()
            assertThat(pager.hasMore).isFalse()
            assertThat(pager.isComplete).isTrue()
        }

    @Test
    fun `a failed page is asked for again`() =
        runTest {
            val pages = Pages(mapOf("t2" to (videos("c") to null)))
            pages.failNext = true
            val pager = RemotePlaylistPager(videos("a"), "t2", maxPages = 60, loadPage = pages::load)

            assertThat(pager.loadNext()).isFalse()
            assertThat(pager.hasMore).isTrue()
            assertThat(pager.loadNext()).isTrue()
            assertThat(pager.isComplete).isTrue()
        }

    @Test
    fun `the page budget or a repeated token leaves the list incomplete`() =
        runTest {
            val loop = Pages(mapOf("t2" to (videos("c") to "t2")))
            val repeated = RemotePlaylistPager(videos("a"), "t2", maxPages = 60, loadPage = loop::load)
            assertThat(repeated.loadAll {}).isFalse()
            assertThat(repeated.hasMore).isFalse()

            val budget = RemotePlaylistPager(videos("a"), "t2", maxPages = 1, loadPage = Pages(emptyMap())::load)
            assertThat(budget.loadAll {}).isFalse()
        }

    @Test
    fun `near the end means within the look-ahead of the last row`() {
        assertThat(nearListEnd(lastVisible = 70, totalItems = 101)).isTrue()
        assertThat(nearListEnd(lastVisible = 69, totalItems = 101)).isFalse()
        assertThat(nearListEnd(lastVisible = -1, totalItems = 0)).isFalse()
    }
}
