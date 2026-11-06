package io.github.aedev.flow.data.lyrics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin

/**
 * One run per key. A caller asking for a key that is already running waits for that run instead
 * of starting another; the run is cancelled only once every caller waiting on it has gone.
 */
internal class InFlightRuns<K : Any, V>(
    private val scope: CoroutineScope,
) {
    private class Run<V>(
        val result: Deferred<V>,
    ) {
        var waiters = 0
    }

    private val runs = HashMap<K, Run<V>>()

    /** Ends a run whatever is waiting on it, and returns once it has stopped. Its waiters see a cancellation. */
    suspend fun cancelAndJoin(key: K) {
        val run = synchronized(runs) { runs.remove(key) } ?: return
        run.result.cancelAndJoin()
    }

    suspend fun join(
        key: K,
        block: suspend () -> V,
    ): V {
        val run =
            synchronized(runs) {
                val running =
                    runs[key]?.takeIf { it.result.isActive }
                        ?: Run(scope.async { block() }).also { started ->
                            runs[key] = started
                            started.result.invokeOnCompletion {
                                synchronized(runs) { if (runs[key] === started) runs.remove(key) }
                            }
                        }
                running.also { it.waiters++ }
            }
        try {
            return run.result.await()
        } finally {
            val abandoned = synchronized(runs) { --run.waiters == 0 && run.result.isActive }
            if (abandoned) run.result.cancel()
        }
    }
}
