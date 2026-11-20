package io.github.aedev.flow.data.repository

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.RelatedCollection
import io.github.aedev.flow.innertube.pages.WatchRelatedResultsTest
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WatchRelatedPagesTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository: YouTubeRepository = mockk(relaxed = true)
    private val pages = WatchRelatedPages(repository, dispatcher)

    @Test
    fun `collections remember how many videos YouTube listed before them`() =
        runTest(dispatcher) {
            coEvery { repository.enrichLikelyCollabAvatarStacks(any(), any()) } answers { firstArg() }
            coEvery { repository.watchNextResponse("v") } returns
                WatchRelatedResultsTest.watch(
                    results =
                        listOf(
                            WatchRelatedResultsTest.videoLockup("a"),
                            WatchRelatedResultsTest.videoLockup("b"),
                            WatchRelatedResultsTest.mixLockup("RDb"),
                            WatchRelatedResultsTest.videoLockup("b"),
                            WatchRelatedResultsTest.videoLockup("c"),
                            WatchRelatedResultsTest.reelShelf("Shorts", "s1"),
                            WatchRelatedResultsTest.continuationItem("next"),
                        ),
                    autoplay = "[${WatchRelatedResultsTest.autoplaySet("c")}]",
                )

            val page = pages.firstPage("v")!!

            assertThat(page.videos.map { it.id }).containsExactly("a", "b", "c").inOrder()
            assertThat(page.upNextVideoId).isEqualTo("c")
            assertThat(page.continuation).isEqualTo("next")
            assertThat(page.collections.map { it.position }).containsExactly(2, 3).inOrder()
            val mix = page.collections[0] as RelatedCollection.PlaylistCard
            assertThat(mix.playlist.id).isEqualTo("RDb")
            assertThat(mix.labels.badge).isEqualTo("Mix")
            assertThat(mix.labels.byline).isEqualTo("Toto, Cyndi Lauper, and more")
            assertThat((page.collections[1] as RelatedCollection.ShortsShelf).shorts.map { it.id }).containsExactly("s1")
        }

    @Test
    fun `a watch response with no videos leaves the lane to the older lookup`() =
        runTest(dispatcher) {
            coEvery { repository.watchNextResponse("v") } returns
                WatchRelatedResultsTest.watch(results = listOf(WatchRelatedResultsTest.mixLockup("RDx")))

            assertThat(pages.firstPage("v")).isNull()
        }
}
