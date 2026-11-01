package io.github.aedev.flow.sync2.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

private const val WAIT_MS = 30_000L

/**
 * The first sync of a linked profile that opens with data still to merge (spec/protocol.md, Profiles). The start
 * destination waits for it, up to [WAIT_MS], so the profile opens with its data instead of on onboarding.
 */
object LinkedCatchUp {
    @Volatile
    private var catching: Deferred<Unit>? = null

    fun start(
        scope: CoroutineScope,
        catchUp: suspend () -> Unit,
    ) {
        catching = scope.async { catchUp() }
    }

    /** Returns once the catch-up, if one started, is done or failed, or after [WAIT_MS]. */
    suspend fun await() {
        withTimeoutOrNull(WAIT_MS) { runCatching { catching?.await() } }
    }
}
