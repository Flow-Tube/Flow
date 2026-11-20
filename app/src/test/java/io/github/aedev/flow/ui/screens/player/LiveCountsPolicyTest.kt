package io.github.aedev.flow.ui.screens.player

import io.github.aedev.flow.innertube.pages.LiveMetadataUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCountsPolicyTest {
    @Test
    fun `the interval follows the server but never drops under the floor`() {
        assertEquals(60_000L, LiveCountsPolicy.intervalMs(60_000L))
        assertEquals(5_000L, LiveCountsPolicy.intervalMs(1_000L))
        assertEquals(5_000L, LiveCountsPolicy.intervalMs(null))
    }

    @Test
    fun `the first request waits for the server's initial delay`() {
        val cursor = LiveCountsPolicy.start("v", initialDelayMs = 30_000L, nowMs = 1_000L)

        assertEquals(31_000L, cursor.dueAtMs)
        assertEquals(null, cursor.continuation)
        assertEquals(30_000L, LiveCountsPolicy.waitMs(cursor, nowMs = 1_000L))
    }

    @Test
    fun `a page back on screen waits only for what is left, or not at all`() {
        val cursor = LiveCountsCursor(videoId = "v", dueAtMs = 10_000L)

        assertEquals(4_000L, LiveCountsPolicy.waitMs(cursor, nowMs = 6_000L))
        assertEquals(0L, LiveCountsPolicy.waitMs(cursor, nowMs = 60_000L))
    }

    @Test
    fun `a live answer schedules the next round on its continuation`() {
        val cursor = LiveCountsCursor(videoId = "v", dueAtMs = 0L)

        val next = LiveCountsPolicy.after(cursor, LiveMetadataUpdate(isLive = true, continuation = "c2", timeoutMs = 5_000L), nowMs = 100L)

        assertFalse(next.stopped)
        assertEquals("c2", next.continuation)
        assertEquals(5_100L, next.dueAtMs)
    }

    @Test
    fun `an answer without viewership keeps going on its continuation`() {
        val next = LiveCountsPolicy.after(LiveCountsCursor("v", dueAtMs = 0L), LiveMetadataUpdate(continuation = "c2"), nowMs = 0L)

        assertFalse(next.stopped)
    }

    @Test
    fun `a failure, a missing continuation or an ended stream stops for good`() {
        val cursor = LiveCountsCursor(videoId = "v", continuation = "c1", dueAtMs = 0L)

        assertTrue(LiveCountsPolicy.after(cursor, null, nowMs = 0L).stopped)
        assertTrue(LiveCountsPolicy.after(cursor, LiveMetadataUpdate(isLive = true), nowMs = 0L).stopped)
        assertTrue(LiveCountsPolicy.after(cursor, LiveMetadataUpdate(isLive = false, continuation = "c2"), nowMs = 0L).stopped)
    }
}
