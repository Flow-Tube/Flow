package io.github.aedev.flow.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

/**
 * One in-flight lookup per key, run in [scope] rather than in the caller's. A caller that is
 * cancelled, such as a card flung off screen, stops waiting while the lookup finishes and caches its
 * answer, so coming back to the card does not fetch it again.
 */
internal class SharedLookups<T>(
    private val scope: CoroutineScope,
) {
    private val inFlight = ConcurrentHashMap<String, Deferred<T>>()

    suspend fun await(
        key: String,
        lookup: suspend () -> T,
    ): T = (inFlight[key] ?: start(key, lookup)).await()

    private fun start(
        key: String,
        lookup: suspend () -> T,
    ): Deferred<T> {
        val created = scope.async(start = CoroutineStart.LAZY) { lookup() }
        inFlight.putIfAbsent(key, created)?.let { running ->
            created.cancel()
            return running
        }
        created.invokeOnCompletion { inFlight.remove(key, created) }
        created.start()
        return created
    }
}
