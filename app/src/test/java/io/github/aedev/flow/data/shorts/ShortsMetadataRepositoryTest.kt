package io.github.aedev.flow.data.shorts

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
}
