package io.github.aedev.flow.player.cache

/**
 * How far ahead of playback the video cache is filled (#1696). The player's own buffer lives in
 * memory and stops at a few minutes; this writes further ahead to disk, through the same cache the
 * player reads, so a video keeps playing after the network drops.
 */
internal object DiskReadAheadPolicy {
    const val OFF = 0
    const val WHOLE_VIDEO = -1
    val OPTIONS_MINUTES = listOf(OFF, 5, 10, 20, WHOLE_VIDEO)

    /**
     * Media time left to the player past its own buffer, so the writer never locks a span the
     * player is about to load. The player would then fetch that span a second time, uncached.
     */
    const val PLAYER_MARGIN_MS = 15_000L

    /** One write covers this much media time per stream, so a cancel or network change acts soon. */
    const val STEP_MS = 60_000L

    /** Peak and average bitrates both undershoot busy scenes, so the byte estimate gets headroom. */
    private const val BITRATE_HEADROOM = 1.1

    private const val MS_PER_MINUTE = 60_000L
    private const val BITS_PER_BYTE = 8.0
    private const val MS_PER_SECOND = 1_000.0

    /** The media time range to write now, or null when there is nothing to do. */
    fun plan(
        minutes: Int,
        positionMs: Long,
        bufferedPositionMs: Long,
        durationMs: Long,
    ): LongRange? {
        if (minutes == OFF) return null
        val start = maxOf(positionMs, bufferedPositionMs) + PLAYER_MARGIN_MS
        val end =
            when {
                minutes == WHOLE_VIDEO && durationMs > 0 -> durationMs
                minutes == WHOLE_VIDEO -> return null
                durationMs > 0 -> minOf(positionMs + minutes * MS_PER_MINUTE, durationMs)
                else -> positionMs + minutes * MS_PER_MINUTE
            }
        return if (end > start) start until end else null
    }

    /** Whether playback has used up half of what was written, or none was written yet. */
    fun needsTopUp(
        minutes: Int,
        positionMs: Long,
        coveredUntilMs: Long,
        durationMs: Long,
    ): Boolean {
        if (minutes == OFF) return false
        if (durationMs > 0 && coveredUntilMs >= durationMs) return false
        if (minutes == WHOLE_VIDEO) return durationMs > 0
        return coveredUntilMs - positionMs < minutes * MS_PER_MINUTE / 2
    }

    /**
     * The byte range of a stream that holds [fromMs] to [toMs], estimated from its bitrate and
     * kept inside [contentLength] when the stream's length is known.
     */
    fun byteRange(
        fromMs: Long,
        toMs: Long,
        bitrate: Int,
        contentLength: Long? = null,
    ): LongRange? {
        if (bitrate <= 0 || toMs <= fromMs) return null
        val bytesPerMs = bitrate / BITS_PER_BYTE / MS_PER_SECOND
        val start = (fromMs * bytesPerMs).toLong()
        val estimatedEnd = (toMs * bytesPerMs * BITRATE_HEADROOM).toLong()
        val end = contentLength?.takeIf { it > 0 }?.let { minOf(estimatedEnd, it) } ?: estimatedEnd
        return if (end > start) start until end else null
    }

    /** At most half the cache, so one long video never evicts itself or everything else. */
    fun budgetBytes(cacheLimitBytes: Long): Long = if (cacheLimitBytes <= 0L) Long.MAX_VALUE else cacheLimitBytes / 2

    /** Only YouTube's own media hosts: SABR pipes, local files and other hosts are never written ahead. */
    fun isWritableStream(
        scheme: String?,
        host: String?,
    ): Boolean = (scheme == "https" || scheme == "http") && host?.endsWith(".googlevideo.com") == true
}
