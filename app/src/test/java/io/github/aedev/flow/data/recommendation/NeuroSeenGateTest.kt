/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.data.recommendation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NeuroSeenGateTest {
    private val now = 1_700_000_000_000L

    private fun items(n: Int): List<String> = (0 until n).map { "v$it" }

    private fun shownRecently(
        showCount: Int,
        hoursAgo: Double = 1.0,
    ) = FeedEntry(lastShown = now - (hoursAgo * 3_600_000L).toLong(), showCount = showCount)

    @Test
    fun `repeatedly shown items are filtered out of a rich pool`() {
        val pool = items(40)
        val history = mapOf("v0" to shownRecently(3), "v1" to shownRecently(2))
        val kept = NeuroScoring.applySeenGate(pool, history, now) { it }
        assertThat(kept).doesNotContain("v0")
        assertThat(kept).doesNotContain("v1")
        assertThat(kept).hasSize(38)
    }

    @Test
    fun `single showing is hidden briefly then returns`() {
        val pool = items(40)
        val justShown = mapOf("v0" to shownRecently(1, hoursAgo = 1.0))
        assertThat(NeuroScoring.applySeenGate(pool, justShown, now) { it }).doesNotContain("v0")

        val pastWindow =
            mapOf("v0" to shownRecently(1, hoursAgo = NeuroScoring.SEEN_GATE_SINGLE_SHOW_WINDOW_HOURS + 1))
        assertThat(NeuroScoring.applySeenGate(pool, pastWindow, now) { it }).contains("v0")
    }

    @Test
    fun `gate releases items after the recovery window`() {
        val pool = items(40)
        val history = mapOf("v0" to shownRecently(5, hoursAgo = NeuroScoring.SEEN_GATE_WINDOW_HOURS + 1))
        val kept = NeuroScoring.applySeenGate(pool, history, now) { it }
        assertThat(kept).contains("v0")
    }

    @Test
    fun `small pools skip the single-sighting hide but never keep repeats`() {
        val pool = items(NeuroScoring.SEEN_GATE_MIN_POOL - 1)
        val history = mapOf("v0" to shownRecently(1), "v1" to shownRecently(2))
        val kept = NeuroScoring.applySeenGate(pool, history, now) { it }
        assertThat(kept).contains("v0")
        assertThat(kept).doesNotContain("v1")
        assertThat(kept).hasSize(pool.size - 1)
    }

    @Test
    fun `the single-sighting hide backs off rather than starving the result`() {
        val pool = items(30)
        // All but 5 items seen once: hiding them would leave < SEEN_GATE_MIN_RESULTS.
        val history = pool.drop(5).associateWith { shownRecently(1) }
        val kept = NeuroScoring.applySeenGate(pool, history, now) { it }
        assertThat(kept).isEqualTo(pool)
    }

    @Test
    fun `repeats stay out even when that leaves little`() {
        val pool = items(30)
        val history = pool.drop(5).associateWith { shownRecently(4) }
        val kept = NeuroScoring.applySeenGate(pool, history, now) { it }
        assertThat(kept).isEqualTo(pool.take(5))
    }

    @Test
    fun `the single-item rule matches the gate`() {
        assertThat(NeuroScoring.isRecentlySeen(null, now)).isFalse()
        assertThat(NeuroScoring.isRecentlySeen(shownRecently(1, hoursAgo = 1.0), now)).isTrue()
        assertThat(NeuroScoring.isRecentlySeen(shownRecently(1, hoursAgo = 7.0), now)).isFalse()
        assertThat(NeuroScoring.isRecentlySeen(shownRecently(2, hoursAgo = 30.0), now)).isTrue()
        assertThat(NeuroScoring.isRecentlySeen(shownRecently(2, hoursAgo = 61.0), now)).isFalse()
    }
}
