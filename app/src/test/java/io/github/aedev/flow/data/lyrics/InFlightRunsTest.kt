package io.github.aedev.flow.data.lyrics

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InFlightRunsTest {
    @Test
    fun `a second caller waits for the running fetch instead of starting another`() =
        runTest {
            val runs = InFlightRuns<String, String>(backgroundScope)
            val gate = CompletableDeferred<String>()
            var started = 0

            val first =
                async {
                    runs.join("v") {
                        started++
                        gate.await()
                    }
                }
            val second =
                async {
                    runs.join("v") {
                        started++
                        "second"
                    }
                }
            runCurrent()
            gate.complete("first")

            assertThat(first.await()).isEqualTo("first")
            assertThat(second.await()).isEqualTo("first")
            assertThat(started).isEqualTo(1)
        }

    @Test
    fun `a finished key runs again on the next call`() =
        runTest {
            val runs = InFlightRuns<String, Int>(backgroundScope)
            var started = 0

            runs.join("v") { ++started }
            val again = runs.join("v") { ++started }

            assertThat(again).isEqualTo(2)
        }

    @Test
    fun `one caller leaving keeps the run going for the other`() =
        runTest {
            val runs = InFlightRuns<String, String>(backgroundScope)
            val gate = CompletableDeferred<String>()

            val leaving = launch { runs.join("v") { gate.await() } }
            val staying = async { runs.join("v") { "unused" } }
            runCurrent()
            leaving.cancel()
            runCurrent()
            gate.complete("lyrics")

            assertThat(staying.await()).isEqualTo("lyrics")
        }

    @Test
    fun `the run stops once every caller has left`() =
        runTest {
            val runs = InFlightRuns<String, String>(backgroundScope)
            var cancelled = false

            val only =
                launch {
                    runs.join("v") {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled = true
                        }
                    }
                }
            runCurrent()
            only.cancel()
            runCurrent()

            assertThat(cancelled).isTrue()
        }

    @Test
    fun `different keys run separately`() =
        runTest {
            val runs = InFlightRuns<String, String>(backgroundScope)

            val a = async { runs.join("a") { "A" } }
            val b = async { runs.join("b") { "B" } }

            assertThat(a.await() to b.await()).isEqualTo("A" to "B")
        }

    @Test
    fun `ending a run stops it for every caller and the next caller starts a new one`() =
        runTest {
            val runs = InFlightRuns<String, String>(backgroundScope)
            var started = 0
            val waiter =
                async {
                    runCatching {
                        runs.join("v") {
                            started++
                            awaitCancellation()
                        }
                    }
                }
            runCurrent()

            runs.cancelAndJoin("v")
            runCurrent()
            val next = runs.join("v") { "fresh" }

            assertThat(waiter.await().isFailure).isTrue()
            assertThat(started).isEqualTo(1)
            assertThat(next).isEqualTo("fresh")
        }
}
