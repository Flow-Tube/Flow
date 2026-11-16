package io.github.aedev.flow.sync2.changes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** A push waits until nothing changed for this long, so a burst of edits leaves as one. */
internal const val PUSH_QUIET_MS = 10_000L

/** Pushes made while the app is open start at most this often. */
internal const val PUSH_SPACING_MS = 60_000L

private data class Waiting(
    val collections: Set<String> = emptySet(),
    val changes: Long = 0,
    val lastChangeAt: Long = 0,
)

/**
 * When the changes made on this device go out while the app is open: [PUSH_QUIET_MS] after the last
 * one, at most one push per [PUSH_SPACING_MS], one at a time. What changes while a push runs waits
 * for one push after it. [clock] is in milliseconds and only has to move forward.
 */
internal class PushSchedule(
    private val clock: () -> Long,
    private val quietMs: Long = PUSH_QUIET_MS,
    private val spacingMs: Long = PUSH_SPACING_MS,
) {
    private val waiting = MutableStateFlow(Waiting())

    fun changed(collection: String) {
        val at = clock()
        waiting.update { Waiting(it.collections + collection, it.changes + 1, at) }
    }

    /**
     * Pushes until cancelled. [ready] returns once a push can reach another device; [push] sends what
     * changed and returns whether it sent anything, since only a push that went out holds the next one back.
     */
    suspend fun run(
        ready: suspend () -> Unit,
        push: suspend (collections: Set<String>) -> Boolean,
    ): Nothing {
        var lastPushAt: Long? = null
        while (true) {
            waiting.first { it.collections.isNotEmpty() }
            ready()
            val seen = waiting.value
            val due = maxOf(seen.lastChangeAt + quietMs, lastPushAt?.plus(spacingMs) ?: Long.MIN_VALUE)
            val wait = due - clock()
            if (wait > 0) {
                withTimeoutOrNull(wait) { waiting.first { it.changes != seen.changes } }
                continue
            }
            val startedAt = clock()
            val collections = waiting.getAndUpdate { Waiting(changes = it.changes, lastChangeAt = it.lastChangeAt) }.collections
            if (push(collections)) lastPushAt = startedAt
        }
    }
}
