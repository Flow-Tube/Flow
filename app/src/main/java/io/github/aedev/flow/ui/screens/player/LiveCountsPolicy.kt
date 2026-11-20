package io.github.aedev.flow.ui.screens.player

import io.github.aedev.flow.innertube.pages.LiveMetadataUpdate

/** Where a live page's count refresh stands: what to send next, when, or that it is over. */
internal data class LiveCountsCursor(
    val videoId: String,
    val continuation: String? = null,
    val dueAtMs: Long,
    val stopped: Boolean = false,
)

/**
 * When to ask `updated_metadata` again, and when to stop asking. The server names its own pace
 * (`timeoutMs`); the floor keeps a server that asks for less from turning this into a hot loop.
 */
internal object LiveCountsPolicy {
    const val MIN_INTERVAL_MS = 5_000L

    fun start(
        videoId: String,
        initialDelayMs: Long?,
        nowMs: Long,
    ): LiveCountsCursor = LiveCountsCursor(videoId = videoId, dueAtMs = nowMs + intervalMs(initialDelayMs))

    fun intervalMs(serverMs: Long?): Long = (serverMs ?: 0L).coerceAtLeast(MIN_INTERVAL_MS)

    /** What is left of the wait, so a page that comes back on screen does not ask early or late. */
    fun waitMs(
        cursor: LiveCountsCursor,
        nowMs: Long,
    ): Long = (cursor.dueAtMs - nowMs).coerceAtLeast(0L)

    /** A failed request, an answer with no continuation or a stream that has ended stops it for good. */
    fun after(
        cursor: LiveCountsCursor,
        update: LiveMetadataUpdate?,
        nowMs: Long,
    ): LiveCountsCursor {
        val continuation = update?.continuation
        if (continuation == null || update.isLive == false) return cursor.copy(stopped = true)
        return cursor.copy(continuation = continuation, dueAtMs = nowMs + intervalMs(update.timeoutMs))
    }
}
