package io.github.aedev.flow.data.recommendation

import io.github.aedev.flow.data.recommendation.FeedImpressionLedger.RECOUNT_WINDOW_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedImpressionLedgerTest {
    private val now = 10 * RECOUNT_WINDOW_MS

    @Test
    fun `a card never seen counts once however often it is reported`() {
        assertEquals(listOf("a"), FeedImpressionLedger.countable(listOf("a", "a"), emptyMap(), now))
    }

    @Test
    fun `a card seen within the window does not count again`() {
        val history = mapOf("a" to FeedEntry(lastShown = now - RECOUNT_WINDOW_MS + 1, showCount = 1))

        assertEquals(emptyList<String>(), FeedImpressionLedger.countable(listOf("a"), history, now))
    }

    @Test
    fun `a card passed again after the window counts again`() {
        val history = mapOf("a" to FeedEntry(lastShown = now - RECOUNT_WINDOW_MS, showCount = 1))

        assertEquals(listOf("a"), FeedImpressionLedger.countable(listOf("a"), history, now))
    }

    @Test
    fun `two sittings reach the seen-gate's long hide`() {
        val firstSitting = FeedEntry(lastShown = now - 7 * 3_600_000L, showCount = 1)
        assertEquals(listOf("a"), FeedImpressionLedger.countable(listOf("a"), mapOf("a" to firstSitting), now))

        val secondSitting = FeedEntry(lastShown = now, showCount = 2)
        assertTrue(NeuroScoring.isRecentlySeen(secondSitting, now + 24 * 3_600_000L))
    }
}
