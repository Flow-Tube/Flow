package io.github.aedev.flow.data.video.downloader.work

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Why a download is waiting instead of transferring. */
enum class DownloadWaitReason {
    NETWORK,
}

/** A download held back after a failure that should clear on its own, and when it runs again. */
data class DownloadWait(
    val reason: DownloadWaitReason,
    val attempt: Int,
    val sinceMs: Long,
    val retryAtMs: Long,
)

/**
 * Downloads waiting out a failure instead of failing. Each failure in a row doubles the wait, from
 * 30 s up to an hour, and a download still failing a day after it first had to wait fails for good.
 * Kept in memory only: after the app restarts, every waiting download simply runs again.
 */
@Singleton
class DownloadWaits internal constructor(
    private val clock: () -> Long,
) {
    @Inject
    constructor() : this(System::currentTimeMillis)

    private val waits = ConcurrentHashMap<String, DownloadWait>()

    /**
     * Set by the host that ran the queue when the system stopped it for losing the network: at that
     * moment the dying network can still look connected, so the stop reason is the reliable signal.
     */
    @Volatile var stoppedForNetwork = false

    /** Holds [videoId] until its next retry, or returns null once it has been waiting too long. */
    fun hold(
        videoId: String,
        reason: DownloadWaitReason,
    ): DownloadWait? {
        val now = clock()
        val previous = waits[videoId]?.takeIf { it.reason == reason }
        val since = previous?.sinceMs ?: now
        if (now - since >= GIVE_UP_AFTER_MS) {
            waits.remove(videoId)
            return null
        }
        val attempt = (previous?.attempt ?: 0) + 1
        return DownloadWait(reason, attempt, since, now + delayMs(attempt)).also { waits[videoId] = it }
    }

    fun clear(videoId: String) {
        waits.remove(videoId)
    }

    /** The downloads whose wait has not run out yet. */
    fun held(): Set<String> {
        val now = clock()
        return waits.filterValues { it.retryAtMs > now }.keys
    }

    /** How long until the soonest held download may run, or null when none is held. */
    fun nextRetryInMs(): Long? {
        val now = clock()
        return waits.values
            .map { it.retryAtMs - now }
            .filter { it > 0 }
            .minOrNull()
    }

    internal companion object {
        const val FIRST_DELAY_MS = 30_000L
        const val MAX_DELAY_MS = 3_600_000L
        const val GIVE_UP_AFTER_MS = 24 * 3_600_000L
        private const val MAX_DOUBLINGS = 7

        fun delayMs(attempt: Int): Long = (FIRST_DELAY_MS shl (attempt - 1).coerceIn(0, MAX_DOUBLINGS)).coerceAtMost(MAX_DELAY_MS)
    }
}
