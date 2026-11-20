package io.github.aedev.flow.data.repository

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.RelatedCollection
import io.github.aedev.flow.innertube.pages.WatchRelatedResultsTest
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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

    @Test
    fun `a pick the related list leaves out is built from the countdown card and heads the page`() =
        runTest(dispatcher) {
            coEvery { repository.enrichLikelyCollabAvatarStacks(any(), any()) } answers { firstArg() }
            val related =
                WatchRelatedResultsTest
                    .watch(
                        results =
                            listOf(
                                WatchRelatedResultsTest.videoLockup("a"),
                                WatchRelatedResultsTest.mixLockup("RDa"),
                                WatchRelatedResultsTest.videoLockup("b"),
                            ),
                        autoplay = "[${WatchRelatedResultsTest.autoplaySet("pick")}]",
                    ).jsonObject
            coEvery { repository.watchNextResponse("v") } returns
                JsonObject(related + ("playerOverlays" to Json.parseToJsonElement(OVERLAY)))

            val page = pages.firstPage("v")!!

            assertThat(page.videos.map { it.id }).containsExactly("pick", "a", "b").inOrder()
            assertThat(page.videos.first().title).isEqualTo("Together Forever")
            assertThat(page.videos.first().channelName).isEqualTo("Rick Astley")
            assertThat(page.upNextVideoId).isEqualTo("pick")
            assertThat(page.collections.single().position).isEqualTo(2)
        }

    @Test
    fun `a listed pick keeps its own card`() =
        runTest(dispatcher) {
            coEvery { repository.enrichLikelyCollabAvatarStacks(any(), any()) } answers { firstArg() }
            val related =
                WatchRelatedResultsTest
                    .watch(
                        results = listOf(WatchRelatedResultsTest.videoLockup("a"), WatchRelatedResultsTest.videoLockup("pick")),
                        autoplay = "[${WatchRelatedResultsTest.autoplaySet("pick")}]",
                    ).jsonObject
            coEvery { repository.watchNextResponse("v") } returns
                JsonObject(related + ("playerOverlays" to Json.parseToJsonElement(OVERLAY)))

            assertThat(pages.firstPage("v")!!.videos.map { it.id }).containsExactly("a", "pick").inOrder()
        }

    private companion object {
        val OVERLAY =
            """
            {"playerOverlayRenderer":{"autoplay":{"playerOverlayAutoplayRenderer":{
              "videoId":"pick",
              "videoTitle":{"simpleText":"Together Forever"},
              "byline":{"runs":[{"text":"Rick Astley","navigationEndpoint":{"browseEndpoint":{"browseId":"UCuAXFkgsw1L7xaCfnd5JJOw"}}}]},
              "background":{"thumbnails":[{"url":"https://i.ytimg.com/vi/pick/hqdefault.jpg","width":168,"height":94}]},
              "shortViewCountText":{"simpleText":"29M views"},
              "publishedTimeText":{"simpleText":"6 years ago"},
              "thumbnailOverlays":[{"thumbnailOverlayTimeStatusRenderer":{"text":{"simpleText":"3:24"}}}]
            }}}}
            """.trimIndent()
    }
}
