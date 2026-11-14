package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync2.changes.PushSchedule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** A change goes out 10 s after the last one, at most once a minute, never two pushes at once. */
@OptIn(ExperimentalCoroutinesApi::class)
class PushScheduleTest {
    private class Pushes(
        private val scope: TestScope,
    ) {
        val at = mutableListOf<Long>()
        val sent = mutableListOf<Set<String>>()
        val connected = MutableStateFlow(true)
        var running = 0
        var mostAtOnce = 0
        var hold: CompletableDeferred<Unit>? = null
        var sends = true
        val schedule = PushSchedule({ scope.testScheduler.currentTime })

        fun start() =
            scope.launch {
                schedule.run(ready = { connected.first { it } }) { collections ->
                    running++
                    mostAtOnce = maxOf(mostAtOnce, running)
                    at += scope.testScheduler.currentTime
                    sent += collections
                    hold?.await()
                    running--
                    sends
                }
            }
    }

    @Test
    fun one_change_goes_out_ten_seconds_later() =
        runTest {
            val pushes = Pushes(this)
            val job = pushes.start()
            pushes.schedule.changed("likes")
            advanceTimeBy(9_999)
            assertEquals(emptyList<Long>(), pushes.at)
            advanceTimeBy(2)
            assertEquals(listOf(10_000L), pushes.at)
            assertEquals(listOf(setOf("likes")), pushes.sent)
            job.cancel()
        }

    @Test
    fun a_burst_of_changes_goes_out_once_after_the_last() =
        runTest {
            val pushes = Pushes(this)
            val job = pushes.start()
            pushes.schedule.changed("likes")
            advanceTimeBy(4_000)
            pushes.schedule.changed("playlists")
            advanceTimeBy(4_000)
            pushes.schedule.changed("likes")
            advanceTimeBy(30_000)
            assertEquals(listOf(18_000L), pushes.at)
            assertEquals(listOf(setOf("likes", "playlists")), pushes.sent)
            job.cancel()
        }

    @Test
    fun a_second_push_waits_a_minute_after_the_first() =
        runTest {
            val pushes = Pushes(this)
            val job = pushes.start()
            pushes.schedule.changed("likes")
            advanceTimeBy(20_000)
            pushes.schedule.changed("notes")
            advanceTimeBy(60_000)
            assertEquals(listOf(10_000L, 70_000L), pushes.at)
            job.cancel()
        }

    @Test
    fun changes_during_a_push_make_one_push_after_it() =
        runTest {
            val pushes = Pushes(this)
            pushes.hold = CompletableDeferred()
            val job = pushes.start()
            pushes.schedule.changed("likes")
            advanceTimeBy(10_001)
            pushes.schedule.changed("notes")
            advanceTimeBy(1_000)
            pushes.schedule.changed("playlists")
            advanceTimeBy(30_000)
            assertEquals(1, pushes.at.size)
            pushes.hold?.complete(Unit)
            advanceTimeBy(120_000)
            assertEquals(listOf(10_000L, 70_000L), pushes.at)
            assertEquals(setOf("notes", "playlists"), pushes.sent[1])
            assertEquals(1, pushes.mostAtOnce)
            job.cancel()
        }

    @Test
    fun nothing_goes_out_until_a_device_can_be_reached() =
        runTest {
            val pushes = Pushes(this)
            pushes.connected.value = false
            val job = pushes.start()
            pushes.schedule.changed("likes")
            advanceTimeBy(120_000)
            assertEquals(emptyList<Long>(), pushes.at)
            pushes.connected.value = true
            runCurrent()
            assertEquals(listOf(120_000L), pushes.at)
            job.cancel()
        }

    @Test
    fun a_push_that_sent_nothing_does_not_hold_the_next_back() =
        runTest {
            val pushes = Pushes(this)
            pushes.sends = false
            val job = pushes.start()
            pushes.schedule.changed("watch_history")
            advanceTimeBy(15_000)
            pushes.schedule.changed("likes")
            advanceTimeBy(15_000)
            assertEquals(listOf(10_000L, 25_000L), pushes.at)
            job.cancel()
        }

    @Test
    fun nothing_goes_out_once_cancelled() =
        runTest {
            val pushes = Pushes(this)
            val job = pushes.start()
            pushes.schedule.changed("likes")
            advanceTimeBy(5_000)
            job.cancel()
            advanceTimeBy(60_000)
            assertEquals(emptyList<Long>(), pushes.at)
        }
}
