package io.github.aedev.flow.data.shorts.queue

import io.github.aedev.flow.data.model.ShortVideo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortsQueueControllerTest {
    // Pinned: ShortVideo.timestamp defaults to System.currentTimeMillis(), so two fixtures for the
    // same id are unequal whenever they straddle a millisecond — which made the enrichment test fail
    // at random.
    private fun short(
        id: String,
        channelId: String = "ch",
    ) = ShortVideo(
        id = id,
        title = "t-$id",
        channelName = "c",
        channelId = channelId,
        thumbnailUrl = "https://i.ytimg.com/vi/$id/oar2.jpg",
        timestamp = 0L,
    )

    private fun shorts(vararg ids: String) = ids.map(::short)

    /** Serves pre-baked pages in order, then reports itself exhausted. */
    private class FakeLoader(
        private val pages: List<List<ShortVideo>>,
    ) : ShortsQueueLoader {
        var initialCalls = 0
            private set
        var moreCalls = 0
            private set

        override suspend fun initial(): ShortsQueuePage {
            initialCalls++
            return page(0)
        }

        override suspend fun more(cursor: String?): ShortsQueuePage {
            moreCalls++
            val next = (cursor?.toIntOrNull() ?: 0) + 1
            return page(next)
        }

        private fun page(index: Int): ShortsQueuePage {
            val items = pages.getOrNull(index).orEmpty()
            val isLast = index >= pages.lastIndex
            return ShortsQueuePage(
                items = items,
                cursor = if (isLast) null else index.toString(),
                exhausted = isLast,
            )
        }
    }

    private fun ids(controller: ShortsQueueController) = controller.items.value.map { it.id }

    // ── Opening position ──

    @Test
    fun `opens at the tapped short without reordering the list`() =
        runTest {
            // The shelf order has to survive so backward swipes still work.
            val controller = ShortsQueueController(FakeLoader(listOf(shorts("a", "b", "c", "d"))))

            controller.loadInitial(startVideoId = "c")

            assertEquals(listOf("a", "b", "c", "d"), ids(controller))
            assertEquals(2, controller.currentIndex.value)
            assertEquals("c", controller.currentItem?.id)
        }

    @Test
    fun `opens at the top when no start short is named`() =
        runTest {
            val controller = ShortsQueueController(FakeLoader(listOf(shorts("a", "b"))))

            controller.loadInitial(startVideoId = null)

            assertEquals(0, controller.currentIndex.value)
        }

    // #1123: opening at the top played a short the user never tapped.
    @Test
    fun `a start short the source does not hold opens as a placeholder`() =
        runTest {
            val controller = ShortsQueueController(FakeLoader(listOf(shorts("a", "b"))))

            controller.loadInitial(startVideoId = "missing")

            assertEquals(listOf("missing", "a", "b"), ids(controller))
            assertEquals("missing", controller.currentItem?.id)
            assertEquals("https://i.ytimg.com/vi/missing/oar2.jpg", controller.currentItem?.thumbnailUrl)
        }

    @Test
    fun `an empty source still opens on the tapped short`() =
        runTest {
            val controller = ShortsQueueController(FakeLoader(listOf(emptyList())))

            controller.loadInitial(startVideoId = "tapped")

            assertEquals(listOf("tapped"), ids(controller))
            assertEquals(0, controller.currentIndex.value)
        }

    @Test
    fun `the anchor is never searched for in a continuation`() =
        runTest {
            val feed = FakeLoader(listOf(shorts("f1", "f2")))
            val controller = ShortsQueueController(FakeLoader(listOf(shorts("a"))), continuations = listOf(feed))

            controller.loadInitial(startVideoId = "missing")

            assertEquals(0, feed.initialCalls)
            assertEquals("missing", controller.currentItem?.id)
        }

    @Test
    fun `a placeholder anchor is not appended again when a later page returns it`() =
        runTest {
            val feed = FakeLoader(listOf(shorts("missing", "f1")))
            val controller = ShortsQueueController(FakeLoader(listOf(shorts("a"))), continuations = listOf(feed))
            controller.loadInitial(startVideoId = "missing")

            controller.loadMore()

            assertEquals(listOf("missing", "a", "f1"), ids(controller))
        }

    // #1123: the tapped reel resolved to a blocked channel and was removed, so another played instead.
    @Test
    fun `a resolve-time filter never removes the short the queue opened on`() =
        runTest {
            val controller = ShortsQueueController(FakeLoader(listOf(shorts("a", "b", "c"))))
            controller.loadInitial(startVideoId = "b")

            assertEquals(ShortsQueueChange.None, controller.dropFiltered("b"))
            assertEquals(ShortsQueueChange.ListOnly, controller.dropFiltered("c"))

            assertEquals(listOf("a", "b"), ids(controller))
            assertEquals("b", controller.currentItem?.id)
        }

    @Test
    fun `a user removal still takes the short the queue opened on`() =
        runTest {
            val controller = ShortsQueueController(FakeLoader(listOf(shorts("a", "b"))))
            controller.loadInitial(startVideoId = "b")

            assertEquals(ShortsQueueChange.CurrentItemChanged, controller.remove("b"))
        }

    @Test
    fun `duplicates in the first page are dropped`() =
        runTest {
            val controller = ShortsQueueController(FakeLoader(listOf(shorts("a", "b", "a"))))

            controller.loadInitial(null)

            assertEquals(listOf("a", "b"), ids(controller))
        }

    // ── Paging and the hand-over to the feed ──

    @Test
    fun `finite source with no continuation stops at the end`() =
        runTest {
            val controller = ShortsQueueController(FakeLoader(listOf(shorts("a", "b"))))
            controller.loadInitial(null)

            assertFalse(controller.hasMore)
            controller.loadMore()

            assertEquals(listOf("a", "b"), ids(controller))
        }

    @Test
    fun `exhausted shelf hands over to the feed`() =
        runTest {
            val shelf = FakeLoader(listOf(shorts("s1", "s2")))
            val feed = FakeLoader(listOf(shorts("f1", "f2"), shorts("f3")))
            val controller = ShortsQueueController(primary = shelf, continuations = listOf(feed))

            controller.loadInitial(null)
            assertEquals(listOf("s1", "s2"), ids(controller))
            assertTrue("shelf is finite but a feed follows it", controller.hasMore)

            controller.loadMore()
            assertEquals(listOf("s1", "s2", "f1", "f2"), ids(controller))
            assertEquals("continuation must start with initial(), not more()", 1, feed.initialCalls)

            controller.loadMore()
            assertEquals(listOf("s1", "s2", "f1", "f2", "f3"), ids(controller))
            assertFalse(controller.hasMore)
        }

    @Test
    fun `paginated primary is drained before the continuation is touched`() =
        runTest {
            val primary = FakeLoader(listOf(shorts("p1"), shorts("p2")))
            val feed = FakeLoader(listOf(shorts("f1")))
            val controller = ShortsQueueController(primary, listOf(feed))

            controller.loadInitial(null)
            controller.loadMore()

            assertEquals(listOf("p1", "p2"), ids(controller))
            assertEquals(0, feed.initialCalls)

            controller.loadMore()
            assertEquals(listOf("p1", "p2", "f1"), ids(controller))
        }

    // The subscriptions queue: recent reels, then the subscribed channels' own older reels, and only
    // then anything algorithmic. Each leg has to be spent before the next is touched at all.
    @Test
    fun `continuations are walked in order, one at a time`() =
        runTest {
            val recent = FakeLoader(listOf(shorts("r1")))
            val deep = FakeLoader(listOf(shorts("d1"), shorts("d2")))
            val feed = FakeLoader(listOf(shorts("f1")))
            val controller = ShortsQueueController(recent, listOf(deep, feed))

            controller.loadInitial(null)
            assertEquals(listOf("r1"), ids(controller))

            controller.loadMore()
            assertEquals(listOf("r1", "d1"), ids(controller))
            assertEquals("the feed must not be touched while the deep tier has pages", 0, feed.initialCalls)

            controller.loadMore()
            assertEquals(listOf("r1", "d1", "d2"), ids(controller))
            assertTrue(controller.hasMore)

            controller.loadMore()
            assertEquals(listOf("r1", "d1", "d2", "f1"), ids(controller))
            assertFalse(controller.hasMore)
        }

    @Test
    fun `a queue with a gated continuation switched off ends at its own last short`() =
        runTest {
            val shelf = FakeLoader(listOf(shorts("s1")))
            val feed = FakeLoader(listOf(shorts("f1")))
            val controller = ShortsQueueController(shelf, listOf(GatedShortsLoader(enabled = { false }, delegate = feed)))

            controller.loadInitial(null)
            controller.loadMore()

            assertEquals(listOf("s1"), ids(controller))
            assertEquals(0, feed.initialCalls)
            assertFalse(controller.hasMore)
        }

    @Test
    fun `append never re-adds an id already in the queue`() =
        runTest {
            val primary = FakeLoader(listOf(shorts("a"), shorts("a", "b")))
            val controller = ShortsQueueController(primary)

            controller.loadInitial(null)
            controller.loadMore()

            assertEquals(listOf("a", "b"), ids(controller))
        }

    @Test
    fun `an all-duplicate page does not end paging`() =
        runTest {
            val primary = FakeLoader(listOf(shorts("a"), shorts("a"), shorts("b")))
            val controller = ShortsQueueController(primary)

            controller.loadInitial(null)
            controller.loadMore()

            assertEquals("should have skipped the duplicate page", listOf("a", "b"), ids(controller))
        }

    // ── Removal ──

    @Test
    fun `removing the current short reports that the position now holds another`() =
        runTest {
            val controller = ShortsQueueController(FakeLoader(listOf(shorts("a", "b", "c"))))
            controller.loadInitial(null)
            controller.setCurrentIndex(1)

            val change = controller.remove("b")

            assertEquals(ShortsQueueChange.CurrentItemChanged, change)
            assertEquals(listOf("a", "c"), ids(controller))
            assertEquals(1, controller.currentIndex.value)
            assertEquals("c", controller.currentItem?.id)
        }

    @Test
    fun `removing another short leaves the current one alone`() =
        runTest {
            val controller = ShortsQueueController(FakeLoader(listOf(shorts("a", "b", "c"))))
            controller.loadInitial(null)
            controller.setCurrentIndex(1)

            val change = controller.remove("c")

            assertEquals(ShortsQueueChange.ListOnly, change)
            assertEquals("b", controller.currentItem?.id)
        }

    @Test
    fun `removing the last short clamps the position`() =
        runTest {
            val controller = ShortsQueueController(FakeLoader(listOf(shorts("a", "b"))))
            controller.loadInitial(null)
            controller.setCurrentIndex(1)

            controller.remove("b")

            assertEquals(0, controller.currentIndex.value)
            assertEquals("a", controller.currentItem?.id)
        }

    @Test
    fun `a removed short does not come back on the next append`() =
        runTest {
            val primary = FakeLoader(listOf(shorts("a", "b"), shorts("b", "c")))
            val controller = ShortsQueueController(primary)
            controller.loadInitial(null)

            controller.remove("b")
            controller.loadMore()

            assertEquals(listOf("a", "c"), ids(controller))
        }

    @Test
    fun `removing a channel drops every one of its shorts and keeps the position on the same short`() =
        runTest {
            val page = listOf(short("a", "UCx"), short("b"), short("c", "UCx"), short("d"), short("e", "UCx"))
            val controller = ShortsQueueController(FakeLoader(listOf(page)))
            controller.loadInitial(null)
            controller.setCurrentIndex(3)

            val change = controller.removeChannel("UCx")

            assertEquals(ShortsQueueChange.ListOnly, change)
            assertEquals(listOf("b", "d"), ids(controller))
            assertEquals("d", controller.currentItem?.id)
            assertEquals(1, controller.currentIndex.value)
        }

    @Test
    fun `removing the current short's channel reports the position change`() =
        runTest {
            val controller = ShortsQueueController(FakeLoader(listOf(listOf(short("a"), short("b", "UCx"), short("c")))))
            controller.loadInitial(null)
            controller.setCurrentIndex(1)

            assertEquals(ShortsQueueChange.CurrentItemChanged, controller.removeChannel("UCx"))
            assertEquals("c", controller.currentItem?.id)
            assertEquals(ShortsQueueChange.None, controller.removeChannel(""))
        }

    @Test
    fun `removing an unknown id changes nothing`() =
        runTest {
            val controller = ShortsQueueController(FakeLoader(listOf(shorts("a"))))
            controller.loadInitial(null)

            assertEquals(ShortsQueueChange.None, controller.remove("nope"))
        }

    // ── Enrichment and discovery ──

    @Test
    fun `enrichment replaces in place`() =
        runTest {
            val controller = ShortsQueueController(FakeLoader(listOf(shorts("a", "b"))))
            controller.loadInitial(null)

            val change = controller.applyEnrichment(listOf(short("a").copy(title = "enriched")))

            assertEquals(ShortsQueueChange.ListOnly, change)
            assertEquals(listOf("a", "b"), ids(controller))
            assertEquals(
                "enriched",
                controller.items.value
                    .first()
                    .title,
            )
        }

    @Test
    fun `enrichment that changes nothing is reported as no change`() =
        runTest {
            val controller = ShortsQueueController(FakeLoader(listOf(shorts("a"))))
            controller.loadInitial(null)

            assertEquals(ShortsQueueChange.None, controller.applyEnrichment(listOf(short("a"))))
        }

    @Test
    fun `discovery merges after the current position and never before it`() =
        runTest {
            val controller = ShortsQueueController(FakeLoader(listOf(shorts("a", "b", "c"))), acceptsDiscovery = true)
            controller.loadInitial(null)
            controller.setCurrentIndex(1)

            controller.mergeDiscovery(shorts("d"))

            val result = ids(controller)
            assertEquals("watched items must not move", listOf("a", "b"), result.take(2))
            assertTrue("discovery item should be present", "d" in result)
        }

    @Test
    fun `discovery ignores ids already queued`() =
        runTest {
            val controller = ShortsQueueController(FakeLoader(listOf(shorts("a", "b"))), acceptsDiscovery = true)
            controller.loadInitial(null)

            assertEquals(ShortsQueueChange.None, controller.mergeDiscovery(shorts("a", "b")))
        }

    // Saved Shorts and a channel tab are not the feed. Background discovery finishes on its own
    // schedule, so without this guard a pass started on the Shorts tab lands in whatever is open.
    @Test
    fun `discovery is refused by a queue that is not the algorithmic feed`() =
        runTest {
            val controller = ShortsQueueController(FakeLoader(listOf(shorts("a", "b"))))
            controller.loadInitial(null)

            assertEquals(ShortsQueueChange.None, controller.mergeDiscovery(shorts("x", "y")))
            assertEquals(listOf("a", "b"), ids(controller))
        }

    // ── Removal ──

    @Test
    fun `removing a short above the cursor keeps the same short playing`() =
        runTest {
            val controller = ShortsQueueController(FakeLoader(listOf(shorts("a", "b", "c", "d"))))
            controller.loadInitial(null)
            controller.setCurrentIndex(2)

            val change = controller.remove("a")

            assertEquals(ShortsQueueChange.ListOnly, change)
            assertEquals("c", controller.currentItem?.id)
        }

    @Test
    fun `removing a short below the cursor leaves the position alone`() =
        runTest {
            val controller = ShortsQueueController(FakeLoader(listOf(shorts("a", "b", "c", "d"))))
            controller.loadInitial(null)
            controller.setCurrentIndex(1)

            val change = controller.remove("d")

            assertEquals(ShortsQueueChange.ListOnly, change)
            assertEquals("b", controller.currentItem?.id)
        }

    // ── Start anchor on a later page ──

    @Test
    fun `pages forward to find a start short the first page did not contain`() =
        runTest {
            // A channel's Shorts grid pages as the user scrolls, so the tap can be well past page one.
            val loader = FakeLoader(listOf(shorts("a", "b"), shorts("c", "d"), shorts("e", "f")))
            val controller = ShortsQueueController(loader)

            controller.loadInitial(startVideoId = "e")

            assertEquals(listOf("a", "b", "c", "d", "e", "f"), ids(controller))
            assertEquals("e", controller.currentItem?.id)
        }

    @Test
    fun `gives up paging and opens on a placeholder when the start short never appears`() =
        runTest {
            val loader = FakeLoader(listOf(shorts("a"), shorts("b"), shorts("c"), shorts("d"), shorts("e")))
            val controller = ShortsQueueController(loader)

            controller.loadInitial(startVideoId = "zzz")

            assertEquals(3, loader.moreCalls)
            assertEquals(0, controller.currentIndex.value)
            assertEquals(listOf("zzz", "a", "b", "c", "d"), ids(controller))
        }

    private class LeadingLoader(
        private val page: List<ShortVideo>,
    ) : ShortsQueueLoader {
        override val leadsWithStart = true

        override suspend fun initial() = ShortsQueuePage(items = page, cursor = null, exhausted = true)

        override suspend fun more(cursor: String?) = ShortsQueuePage(emptyList(), exhausted = true)
    }

    @Test
    fun `a loader that leads with the start opens on it before the page`() =
        runTest {
            val controller = ShortsQueueController(primary = LeadingLoader(shorts("tapped", "a", "b")))

            assertTrue(controller.openOnStartEarly("tapped"))
            assertEquals(listOf("tapped"), ids(controller))

            controller.loadInitial("tapped")
            assertEquals(listOf("tapped", "a", "b"), ids(controller))
            assertEquals(0, controller.currentIndex.value)
        }

    @Test
    fun `a title learned before the page survives the bare copy in it`() =
        runTest {
            val bare = ShortVideo(id = "tapped", thumbnailUrl = "", timestamp = 0L)
            val controller = ShortsQueueController(primary = LeadingLoader(listOf(bare) + shorts("a")))
            controller.openOnStartEarly("tapped")
            controller.applyEnrichment(listOf(short("tapped")))

            controller.loadInitial("tapped")

            assertEquals(
                "t-tapped",
                controller.items.value
                    .first()
                    .title,
            )
        }

    @Test
    fun `a loader that keeps its own order never opens early`() =
        runTest {
            val controller = ShortsQueueController(primary = FakeLoader(listOf(shorts("a", "tapped"))))

            assertFalse(controller.openOnStartEarly("tapped"))
            assertTrue(controller.items.value.isEmpty())
        }
}
