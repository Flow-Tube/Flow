package io.github.aedev.flow.notification

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BreakClockTest {
    private val min = 60_000L
    private val thirty = 30 * min

    private fun started() = BreakClock().apply { onShown(0L) }

    @Test
    fun `time on screen counts down`() {
        assertThat(started().remainingMs(thirty, 10 * min)).isEqualTo(20 * min)
    }

    @Test
    fun `nothing counts while Flow is away`() {
        val clock = started()
        clock.onHidden(10 * min)
        assertThat(clock.remainingMs(thirty, 12 * min)).isEqualTo(20 * min)
    }

    @Test
    fun `a short look away keeps the count`() {
        val clock = started()
        clock.onHidden(10 * min)
        clock.onShown(14 * min)
        assertThat(clock.remainingMs(thirty, 14 * min)).isEqualTo(20 * min)
    }

    @Test
    fun `five minutes away is the break`() {
        val clock = started()
        clock.onHidden(10 * min)
        clock.onShown(15 * min)
        assertThat(clock.remainingMs(thirty, 15 * min)).isEqualTo(thirty)
    }

    @Test
    fun `short looks away add up only the time on screen`() {
        val clock = started()
        clock.onHidden(10 * min)
        clock.onShown(12 * min)
        clock.onHidden(22 * min)
        clock.onShown(24 * min)
        assertThat(clock.remainingMs(thirty, 34 * min)).isEqualTo(0L)
    }

    @Test
    fun `a restart counts a full period again`() {
        val clock = started()
        clock.restart(thirty)
        assertThat(clock.remainingMs(thirty, thirty)).isEqualTo(thirty)
    }

    @Test
    fun `remaining time never goes below zero`() {
        assertThat(started().remainingMs(thirty, 90 * min)).isEqualTo(0L)
    }

    @Test
    fun `a repeated start keeps counting from the first`() {
        val clock = started()
        clock.onShown(10 * min)
        assertThat(clock.remainingMs(thirty, 10 * min)).isEqualTo(20 * min)
    }
}
