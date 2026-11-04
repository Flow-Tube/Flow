package io.github.aedev.flow.ui.components.videoplayer.overlay

import io.github.aedev.flow.data.local.SponsorBlockAction
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.player.sponsorblock.SponsorBlockActionPolicy

/** How long after a video's content begins the jump to its highlight is offered. */
private const val HIGHLIGHT_BUTTON_WINDOW_SEC = 10f

/** A segment this close to the content before it still counts as following straight on. */
private const val OPENING_SKIP_GAP_SEC = 1f

internal fun findActiveManualSponsorSegment(
    sponsorSegments: List<SponsorBlockSegment>,
    currentPositionMs: Long,
    skippedUuids: Set<String>,
    categoryActions: Map<String, SponsorBlockAction>,
    playbackEnded: Boolean,
    skippedOnceUuids: Set<String> = emptySet(),
): SponsorBlockSegment? {
    if (playbackEnded) return null

    val positionSeconds = currentPositionMs / 1000f
    val highlightWindowEnd = contentStart(sponsorSegments, categoryActions, skippedOnceUuids) + HIGHLIGHT_BUTTON_WINDOW_SEC
    return sponsorSegments.find { segment ->
        isManualWindow(segment, positionSeconds, highlightWindowEnd) &&
            segment.uuid !in skippedUuids &&
            SponsorBlockActionPolicy.showsSkipButton(
                SponsorBlockActionPolicy.actionFor(segment, categoryActions),
                alreadySkippedOnce = segment.uuid in skippedOnceUuids,
            )
    }
}

private fun isManualWindow(
    segment: SponsorBlockSegment,
    positionSeconds: Float,
    highlightWindowEnd: Float,
): Boolean =
    if (segment.isPoint) {
        positionSeconds < minOf(highlightWindowEnd, segment.startTime)
    } else {
        positionSeconds >= segment.startTime && positionSeconds < segment.endTime
    }

/** Where a viewer really starts watching: past any segments skipped automatically from the first second. */
private fun contentStart(
    segments: List<SponsorBlockSegment>,
    categoryActions: Map<String, SponsorBlockAction>,
    skippedOnceUuids: Set<String>,
): Float =
    segments
        .filter { it.endTime > it.startTime }
        .sortedBy { it.startTime }
        .fold(0f) { start, segment ->
            val skipped =
                SponsorBlockActionPolicy.skipsAutomatically(
                    SponsorBlockActionPolicy.actionFor(segment, categoryActions),
                    alreadySkippedOnce = segment.uuid in skippedOnceUuids,
                )
            if (skipped && segment.startTime <= start + OPENING_SKIP_GAP_SEC) maxOf(start, segment.endTime) else start
        }
