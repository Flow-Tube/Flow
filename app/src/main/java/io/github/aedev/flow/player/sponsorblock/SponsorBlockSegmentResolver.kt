package io.github.aedev.flow.player.sponsorblock

import io.github.aedev.flow.data.local.SponsorBlockAction
import io.github.aedev.flow.data.model.SponsorBlockSegment

/**
 * What to do at one position. For an automatic skip, [chain] is every auto-skip segment the jump
 * passes, [covered] every segment it jumps over, and [targetSec] where it lands.
 */
class SponsorBlockResolution(
    val segment: SponsorBlockSegment,
    val action: SponsorBlockAction,
    val skipsAutomatically: Boolean,
    val chain: List<SponsorBlockSegment>,
    val covered: List<SponsorBlockSegment>,
    val targetSec: Float,
) {
    val targetMs: Long get() = (targetSec * 1000).toLong()
}

/**
 * Picks the segment that decides what happens when several cover the same position, as two
 * categories often do on the same intro or outro: skipping beats muting, muting beats a button or a
 * notice, and a category the user switched off never hides another one.
 */
object SponsorBlockSegmentResolver {
    /** Segments closer than this form one skip, so playback never stops for a sliver in between. */
    const val CHAIN_GAP_SEC = 0.05f

    /** A skip only re-arms for a rewind this far before the segment, so a seek that lands slightly
     * short of the requested end (SABR rebuilds at a segment boundary) cannot loop the skip. */
    private const val REARM_MARGIN_SEC = 1f

    /** A segment starting within [REARM_MARGIN_SEC] of the video start has no room before it, so a
     * rewind into its first second re-arms it, unless that is also this close to its end. */
    private const val SHORT_LANDING_GUARD_SEC = 3f

    private const val RANK_AUTO_SKIP = 0
    private const val RANK_MUTE = 1
    private const val RANK_MANUAL = 2

    fun segmentsAt(
        segments: List<SponsorBlockSegment>,
        posSec: Float,
    ): List<SponsorBlockSegment> = segments.filter { it.isRange() && posSec >= it.startTime && posSec < it.endTime }

    fun resolve(
        segments: List<SponsorBlockSegment>,
        posSec: Float,
        categoryActions: Map<String, SponsorBlockAction>,
        skippedOnce: Set<String>,
        handled: Set<String> = emptySet(),
    ): SponsorBlockResolution? {
        val best =
            segmentsAt(segments, posSec)
                .filter { it.uuid !in handled }
                .mapNotNull { segment ->
                    val action = SponsorBlockActionPolicy.actionFor(segment, categoryActions)
                    rank(action, segment.uuid in skippedOnce)?.let { Triple(segment, action, it) }
                }.minByOrNull { it.third } ?: return null
        val (segment, action, rank) = best
        if (rank != RANK_AUTO_SKIP) {
            return SponsorBlockResolution(segment, action, false, emptyList(), emptyList(), segment.endTime)
        }

        val autoSkips =
            segments.filter {
                it.isRange() &&
                    SponsorBlockActionPolicy.skipsAutomatically(
                        SponsorBlockActionPolicy.actionFor(it, categoryActions),
                        alreadySkippedOnce = it.uuid in skippedOnce,
                    )
            }
        val chain = autoSkips.filter { posSec >= it.startTime && posSec < it.endTime }.toMutableList()
        var end = chain.maxOf { it.endTime }
        while (true) {
            val next = autoSkips.filter { it !in chain && it.startTime <= end + CHAIN_GAP_SEC && it.endTime > end }
            val sliverEnd =
                segments
                    .filter { it.isRange() && end >= it.startTime && end < it.endTime && it.endTime - end <= CHAIN_GAP_SEC }
                    .maxOfOrNull { it.endTime }
            if (next.isEmpty() && sliverEnd == null) break
            chain += next
            end = maxOf(end, next.maxOfOrNull { it.endTime } ?: end, sliverEnd ?: end)
        }
        val chainStart = chain.minOf { it.startTime }
        val covered = segments.filter { it.isRange() && it.startTime < end && it.endTime > chainStart }
        return SponsorBlockResolution(segment, action, true, chain, covered, end)
    }

    /** Whether playback at [posSec] is back before [segment], so reaching it should act again. */
    fun isRewoundBefore(
        posSec: Float,
        segment: SponsorBlockSegment,
    ): Boolean =
        posSec < segment.startTime - REARM_MARGIN_SEC ||
            (
                segment.startTime < REARM_MARGIN_SEC &&
                    posSec < segment.startTime + REARM_MARGIN_SEC &&
                    posSec < segment.endTime - SHORT_LANDING_GUARD_SEC
            )

    private fun rank(
        action: SponsorBlockAction,
        alreadySkippedOnce: Boolean,
    ): Int? =
        when {
            SponsorBlockActionPolicy.skipsAutomatically(action, alreadySkippedOnce) -> RANK_AUTO_SKIP
            action == SponsorBlockAction.MUTE -> RANK_MUTE
            SponsorBlockActionPolicy.showsSkipButton(action, alreadySkippedOnce) -> RANK_MANUAL
            else -> null
        }

    private fun SponsorBlockSegment.isRange(): Boolean = !isPoint && endTime > startTime
}
