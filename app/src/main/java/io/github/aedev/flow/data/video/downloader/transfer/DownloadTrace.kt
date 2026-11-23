package io.github.aedev.flow.data.video.downloader.transfer

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * What one run of a download cost, logged as a single `DownloadTrace` line when the run ends, so
 * block size, connections and concurrency can be compared on a real device.
 */
class DownloadTrace(
    val videoId: String,
    private val nanoClock: () -> Long = System::nanoTime,
) {
    private val startedAt = nanoClock()
    private val requests = AtomicInteger()
    private val firstByteTotalMs = AtomicLong()
    private val firstByteMaxMs = AtomicLong()
    private val networkBytes = AtomicLong()
    private val expiredDenials = AtomicInteger()
    private val gatedDenials = AtomicInteger()
    private val resolves = AtomicInteger()
    private val stalls = AtomicInteger()
    private val playbackWaitMs = AtomicLong()

    @Volatile var usedSabr = false

    @Volatile var remuxMs = 0L

    @Volatile var placeMs = 0L

    @Volatile var peakStagingBytes = 0L

    val bytesFetched: Long get() = networkBytes.get()

    fun now(): Long = nanoClock()

    fun onResponse(sentAtNanos: Long) {
        val ms = (nanoClock() - sentAtNanos) / NANOS_PER_MS
        requests.incrementAndGet()
        firstByteTotalMs.addAndGet(ms)
        firstByteMaxMs.accumulateAndGet(ms, ::maxOf)
    }

    fun onBytes(count: Int) {
        networkBytes.addAndGet(count.toLong())
    }

    fun onDenied(expired: Boolean) {
        (if (expired) expiredDenials else gatedDenials).incrementAndGet()
    }

    fun onResolve() {
        resolves.incrementAndGet()
    }

    fun onStall() {
        stalls.incrementAndGet()
    }

    fun onPlaybackWait(sinceNanos: Long) {
        playbackWaitMs.addAndGet((nanoClock() - sinceNanos) / NANOS_PER_MS)
    }

    fun line(
        outcome: String,
        settings: String,
    ): String {
        val wallMs = ((nanoClock() - startedAt) / NANOS_PER_MS).coerceAtLeast(1L)
        val bytes = networkBytes.get()
        val count = requests.get()
        val mbps = bytes * BITS_PER_BYTE / wallMs / MS_TO_MBPS
        return buildString {
            append("id=").append(videoId)
            append(" outcome=").append(outcome)
            append(" bytes=").append(bytes)
            append(" wallMs=").append(wallMs)
            append(" mbps=").append("%.2f".format(java.util.Locale.ROOT, mbps))
            append(" requests=").append(count)
            append(" ttfbAvgMs=").append(if (count > 0) firstByteTotalMs.get() / count else 0)
            append(" ttfbMaxMs=").append(firstByteMaxMs.get())
            append(" expired403=").append(expiredDenials.get())
            append(" gated403=").append(gatedDenials.get())
            append(" resolves=").append(resolves.get())
            append(" stalls=").append(stalls.get())
            append(" playbackWaitMs=").append(playbackWaitMs.get())
            append(" sabr=").append(usedSabr)
            append(" remuxMs=").append(remuxMs)
            append(" placeMs=").append(placeMs)
            append(" peakStagingMB=").append(peakStagingBytes / BYTES_PER_MB)
            append(' ').append(settings)
        }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val BITS_PER_BYTE = 8.0
        const val MS_TO_MBPS = 1_000.0
        const val BYTES_PER_MB = 1_048_576L
    }
}
