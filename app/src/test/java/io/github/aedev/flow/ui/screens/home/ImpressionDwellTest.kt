package io.github.aedev.flow.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpressionDwellTest {
    @Test
    fun `a card counts once it has stayed on screen for the dwell time`() {
        val dwell = ImpressionDwell(dwellMs = 1_000)

        assertEquals(emptyList<String>(), dwell.update(listOf("a", "b"), now = 0))
        assertEquals(400L, dwell.millisUntilNextDue(now = 600))
        assertEquals(listOf("a", "b"), dwell.update(listOf("a", "b"), now = 1_000).sorted())
    }

    @Test
    fun `a slow steady scroll still records the cards it passes`() {
        val dwell = ImpressionDwell(dwellMs = 1_000)
        val recorded = mutableSetOf<String>()

        // A frame every 100 ms; four cards on screen, a new one entering every 300 ms, so each card
        // after the first few stays for 1.2 s while the list never stops moving.
        for (frame in 0..40) {
            val now = frame * 100L
            val first = (now / 300).toInt()
            recorded += dwell.update((first until first + 4).map { "v$it" }, now)
        }

        assertTrue(recorded.containsAll((3..10).map { "v$it" }))
        assertFalse("v0" in recorded)
    }

    @Test
    fun `a card flicked past is not counted`() {
        val dwell = ImpressionDwell(dwellMs = 1_000)

        dwell.update(listOf("a"), now = 0)
        dwell.update(listOf("b"), now = 200)

        assertEquals(emptyList<String>(), dwell.update(listOf("b"), now = 900))
    }

    @Test
    fun `a card counts again after it leaves and comes back`() {
        val dwell = ImpressionDwell(dwellMs = 1_000)

        dwell.update(listOf("a"), now = 0)
        assertEquals(listOf("a"), dwell.update(listOf("a"), now = 1_000))
        dwell.update(emptyList(), now = 2_000)
        dwell.update(listOf("a"), now = 3_000)

        assertEquals(listOf("a"), dwell.update(listOf("a"), now = 4_000))
    }

    @Test
    fun `nothing waits once every card on screen has counted`() {
        val dwell = ImpressionDwell(dwellMs = 1_000)

        dwell.update(listOf("a"), now = 0)
        dwell.update(listOf("a"), now = 1_000)

        assertNull(dwell.millisUntilNextDue(now = 1_500))
    }
}
