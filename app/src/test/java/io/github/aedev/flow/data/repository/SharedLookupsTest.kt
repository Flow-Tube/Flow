package io.github.aedev.flow.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SharedLookupsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lookups = SharedLookups<String>(scope)
    private val runs = AtomicInteger()

    @Test
    fun `a lookup finishes after the caller that started it leaves`() =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<String>()
            val caller =
                launch(Dispatchers.Default) {
                    lookups.await("id") {
                        runs.incrementAndGet()
                        release.await()
                        "answer".also { finished.complete(it) }
                    }
                }
            while (runs.get() == 0) Thread.sleep(5)

            caller.cancelAndJoin()
            release.complete(Unit)

            assertThat(withTimeout(5_000) { finished.await() }).isEqualTo("answer")
        }

    @Test
    fun `callers asking at the same time share one lookup`() =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val asks =
                (1..5).map {
                    async(Dispatchers.Default) {
                        lookups.await("id") {
                            runs.incrementAndGet()
                            release.await()
                            "answer"
                        }
                    }
                }
            while (runs.get() == 0) Thread.sleep(5)
            Thread.sleep(50)
            release.complete(Unit)

            assertThat(asks.awaitAll()).containsExactly("answer", "answer", "answer", "answer", "answer")
            assertThat(runs.get()).isEqualTo(1)
        }

    @Test
    fun `a finished lookup is not reused for the next ask`() =
        runBlocking {
            lookups.await("id") { "first".also { runs.incrementAndGet() } }
            Thread.sleep(20)
            val second = lookups.await("id") { "second".also { runs.incrementAndGet() } }

            assertThat(second).isEqualTo("second")
            assertThat(runs.get()).isEqualTo(2)
        }
}
