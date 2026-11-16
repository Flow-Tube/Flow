package io.github.aedev.flow.data.video.downloader.transfer

import okhttp3.Call
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class StreamRole {
    VIDEO,
    AUDIO,
}

/** One stream of a download, fetched in fixed-size blocks into its own part file. */
class TransferStream(
    val role: StreamRole,
    val url: String,
    val file: File,
    val itag: Int,
    totalBytes: Long,
    // Every dub of a video shares the audio itag and often the size, so the track tells them apart.
    val trackId: String? = null,
) {
    @Volatile
    var totalBytes: Long = totalBytes
        internal set

    val completedBlocks: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    val partialBlockBytes: ConcurrentHashMap<Int, Long> = ConcurrentHashMap()
    internal val downloaded = AtomicLong(0L)
    internal val nextBlock = AtomicInteger(0)

    val downloadedBytes: Long get() = downloaded.get()
}

enum class TransferStatus {
    RUNNING,

    /** Stopped on purpose (a pause, a cancel, the system stopping the work); blocks so far are kept. */
    STOPPED,
    FAILED,
    FINISHED,
}

/** What one run of [ParallelDownloader] ended on. */
sealed interface TransferResult {
    data object Completed : TransferResult

    data object Stopped : TransferResult

    /** GVS refused [url] with a 403; the URL says why (expired, unattested, token refused). */
    data class Denied(
        val url: String,
    ) : TransferResult

    /** No bytes arrived for a while; the stream is resolved again without blaming its client. */
    data object Stalled : TransferResult

    data class Failed(
        val reason: TransferFailure,
        val detail: String? = null,
    ) : TransferResult
}

enum class TransferFailure {
    LENGTH_UNKNOWN,
    BLOCK_FAILED,
    DISK_FULL,
}

/** The streams of one download and the shared state its range workers coordinate through. */
class TransferJob(
    val videoId: String,
    val streams: List<TransferStream>,
    val threads: Int,
    val fallbackUserAgent: String,
) {
    @Volatile
    var status: TransferStatus = TransferStatus.RUNNING
        private set

    @Volatile
    var result: TransferResult? = null
        private set

    private val activeCalls: MutableList<Call> = Collections.synchronizedList(mutableListOf())

    val downloadedBytes: Long get() = streams.sumOf { it.downloadedBytes }
    val totalBytes: Long get() = streams.sumOf { it.totalBytes }

    val isRunning: Boolean get() = status == TransferStatus.RUNNING

    /** Stops every worker and in-flight request; what was written stays for a later resume. */
    fun stop() {
        finish(TransferStatus.STOPPED, TransferResult.Stopped)
    }

    internal fun fail(result: TransferResult) {
        finish(TransferStatus.FAILED, result)
    }

    internal fun complete() {
        synchronized(this) {
            if (status == TransferStatus.RUNNING) {
                status = TransferStatus.FINISHED
                result = TransferResult.Completed
            }
        }
    }

    private fun finish(
        next: TransferStatus,
        outcome: TransferResult,
    ) {
        val won =
            synchronized(this) {
                if (status != TransferStatus.RUNNING) return@synchronized false
                status = next
                result = outcome
                true
            }
        if (won) cancelActiveCalls()
    }

    internal fun track(call: Call) {
        activeCalls.add(call)
        // A stop that landed between the status check and this add would otherwise leave the call running.
        if (!isRunning) call.cancel()
    }

    internal fun untrack(call: Call) {
        activeCalls.remove(call)
    }

    private fun cancelActiveCalls() {
        val calls = synchronized(activeCalls) { activeCalls.toList().also { activeCalls.clear() } }
        calls.forEach { it.cancel() }
    }
}
