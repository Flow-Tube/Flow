package io.github.aedev.flow.player.live

import io.github.aedev.flow.player.stream.LiveSegmentPosition

/**
 * What a live segment response says about the stream's newest segment: its sequence number, its
 * media time, and the server's clock when it answered.
 */
data class LiveHead(
    val sequence: Long,
    val mediaTimeMs: Long,
    val serverTimeMs: Long,
)

/**
 * Maps a live stream's segment numbers to a timeline Media3 can play, from the head of the stream.
 *
 * YouTube numbers live segments from zero and gives the newest one's media time. Segments are close
 * to, but not exactly, the target duration (5.000 s on a radio stream, 4.976 s on average on a news
 * stream, unevenly), so the template uses the stream's measured average: the head's media time over
 * its number, in microseconds. The timeline is anchored to the head, so the newest segments line up
 * exactly and the error grows only with depth into the window, as YouTube's own sequence estimator
 * does.
 *
 * Availability is re-anchored to every head the CDN reports, so the newest segment Media3 asks for
 * stays at or behind the real one however the average and the recent segments differ. A head seen
 * late in its segment would put the edge in the future, so the earliest anchor seen within
 * [ANCHOR_WINDOW_MS] wins.
 */
class LiveSegmentClock(
    initialHead: LiveHead,
    targetDurationMs: Long,
    maxDvrDurationMs: Long,
) {
    val segmentDurationUs: Long = segmentDurationUs(initialHead, targetDurationMs)

    /** Media time of segment 0 on the template's timeline, so the first head lines up exactly. */
    val presentationTimeOffsetUs: Long = initialHead.mediaTimeMs * 1_000 - initialHead.sequence * segmentDurationUs

    private val maxDepthMs = maxDvrDurationMs.coerceAtLeast(segmentDurationUs / 1_000)
    private val anchors = ArrayDeque<Pair<Long, Long>>()

    @Volatile
    var head: LiveHead = initialHead
        private set

    init {
        record(initialHead)
    }

    /** Server time at which segment 0 became available, on the template's grid. */
    @get:Synchronized
    val availabilityStartTimeMs: Long
        get() = anchors.minOf { it.second }

    /** How far back the window reaches: YouTube's DVR limit, or the whole stream if it is younger. */
    val timeShiftBufferDepthMs: Long
        get() = minOf(maxDepthMs, (head.sequence + 1) * segmentDurationUs / 1_000)

    /**
     * The segment under [positionMs] on a player window that starts [windowStartInPeriodUs] into
     * the period. Segment N starts at N segment durations into the period, as the manifest says.
     */
    fun segmentAt(
        windowStartInPeriodUs: Long,
        positionMs: Long,
    ): LiveSegmentPosition? {
        val periodUs = windowStartInPeriodUs + positionMs * 1_000
        if (periodUs < 0 || segmentDurationUs <= 0) return null
        return LiveSegmentPosition(sequence = periodUs / segmentDurationUs, headSequence = head.sequence)
    }

    @Synchronized
    fun observe(observed: LiveHead) {
        if (observed.sequence < head.sequence) return
        head = observed
        record(observed)
    }

    private fun record(observed: LiveHead) {
        val start = observed.serverTimeMs - (observed.sequence + 1) * segmentDurationUs / 1_000
        anchors.addLast(observed.serverTimeMs to start)
        while (anchors.size > 1 && observed.serverTimeMs - anchors.first().first > ANCHOR_WINDOW_MS) {
            anchors.removeFirst()
        }
    }

    companion object {
        const val ANCHOR_WINDOW_MS = 60_000L

        /** Shortest template segment, as a share of the target: guards a head whose time is not counted from sequence 0. */
        private const val MIN_DURATION_SHARE = 0.98

        fun segmentDurationUs(
            head: LiveHead,
            targetDurationMs: Long,
        ): Long {
            val targetUs = targetDurationMs * 1_000
            if (head.sequence <= 0 || head.mediaTimeMs <= 0) return targetUs
            val averageUs = head.mediaTimeMs * 1_000 / head.sequence
            return averageUs.coerceIn((targetUs * MIN_DURATION_SHARE).toLong(), targetUs)
        }
    }
}
