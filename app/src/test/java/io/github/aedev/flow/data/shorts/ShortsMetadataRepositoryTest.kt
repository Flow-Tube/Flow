package io.github.aedev.flow.data.shorts

import io.github.aedev.flow.innertube.pages.ReelCommentsPanel
import io.github.aedev.flow.innertube.pages.VideoCommentsSection
import io.github.aedev.flow.innertube.pages.VideoDescriptionPage
import io.github.aedev.flow.innertube.pages.reel.ReelItem
import io.github.aedev.flow.innertube.pages.reel.ReelOverlay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ShortsMetadataRepositoryTest {
    private val fetches = AtomicInteger()
    private var item: ReelItem? = ReelItem(overlay = ReelOverlay(title = "A reel", likeCount = 2_772L, commentCount = 62L))
    private val repository =
        ShortsMetadataRepository {
            fetches.incrementAndGet()
            item
        }

    @Test
    fun `a reel is fetched once per half hour`() =
        runBlocking {
            assertEquals(2_772L, repository.overlayFor("reel1234567", nowMillis = 0L)?.likeCount)
            assertEquals(2_772L, repository.overlayFor("reel1234567", nowMillis = 29 * 60_000L)?.likeCount)
            assertEquals(1, fetches.get())

            repository.overlayFor("reel1234567", nowMillis = 31 * 60_000L)
            assertEquals(2, fetches.get())
        }

    @Test
    fun `an unreadable overlay is not remembered`() =
        runBlocking {
            item = null
            assertNull(repository.overlayFor("reel1234567", nowMillis = 0L))
            assertNull(repository.overlayFor("reel1234567", nowMillis = 1L))
            assertEquals(2, fetches.get())
        }

    @Test
    fun `the overlay, the comment panel and the description share one request`() =
        runBlocking {
            item =
                ReelItem(
                    overlay = ReelOverlay(title = "A reel"),
                    comments = ReelCommentsPanel(VideoCommentsSection.Open("first"), header = null),
                    description = VideoDescriptionPage(viewCount = 496_093L),
                )

            assertEquals("A reel", repository.overlayFor("reel1234567", nowMillis = 0L)?.title)
            assertEquals(VideoCommentsSection.Open("first"), repository.commentsPanelFor("reel1234567", nowMillis = 1L)?.section)
            assertEquals(496_093L, repository.descriptionFor("reel1234567", nowMillis = 2L)?.viewCount)
            assertEquals(1, fetches.get())
        }
}
