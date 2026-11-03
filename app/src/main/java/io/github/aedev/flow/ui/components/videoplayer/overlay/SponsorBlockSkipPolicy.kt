package io.github.aedev.flow.ui.components.videoplayer.overlay

import io.github.aedev.flow.data.local.SponsorBlockAction
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.player.sponsorblock.SponsorBlockActionPolicy

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
    return sponsorSegments.find { segment ->
        positionSeconds >= segment.startTime &&
            positionSeconds < segment.endTime &&
            segment.uuid !in skippedUuids &&
            SponsorBlockActionPolicy.showsSkipButton(
                SponsorBlockActionPolicy.actionFor(segment, categoryActions),
                alreadySkippedOnce = segment.uuid in skippedOnceUuids,
            )
    }
}
