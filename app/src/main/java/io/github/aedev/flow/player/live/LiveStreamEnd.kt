package io.github.aedev.flow.player.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Notices a live stream that stopped. Segments carry no end marker: when a broadcast ends its head
 * simply stops advancing. A head that has not moved for [STALLED_SEGMENTS] segments is checked once
 * against a fresh player response, and again every [RECHECK_MS] while it stays still, since an
 * encoder that pauses looks the same for a while.
 *
 * @param isStillLive whether the stream is still broadcasting; null when that could not be learned.
 */
class LiveStreamEnd(
    private val segmentDurationMs: Long,
    private val scope: CoroutineScope,
    private val isStillLive: suspend () -> Boolean?,
) {
    private val checking = AtomicBoolean(false)
    private var lastSequence = Long.MIN_VALUE
    private var lastAdvanceMs = 0L
    private var lastCheckMs = Long.MIN_VALUE

    /** The last segment of a stream that has ended, or null while it is live. */
    @Volatile
    var lastSequenceOfEndedStream: Long? = null
        private set

    @Synchronized
    fun observe(head: LiveHead) {
        if (lastSequenceOfEndedStream != null) return
        if (head.sequence > lastSequence) {
            lastSequence = head.sequence
            lastAdvanceMs = head.serverTimeMs
            return
        }
        if (head.serverTimeMs - lastAdvanceMs < STALLED_SEGMENTS * segmentDurationMs) return
        if (lastCheckMs != Long.MIN_VALUE && head.serverTimeMs - lastCheckMs < RECHECK_MS) return
        if (!checking.compareAndSet(false, true)) return
        lastCheckMs = head.serverTimeMs
        val sequence = head.sequence
        scope.launch {
            try {
                if (isStillLive() == false) lastSequenceOfEndedStream = sequence
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                checking.set(false)
            }
        }
    }

    companion object {
        const val STALLED_SEGMENTS = 3
        const val RECHECK_MS = 30_000L
    }
}
