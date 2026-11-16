package io.github.aedev.flow.player.sponsorblock

import io.github.aedev.flow.data.local.SponsorBlockAction
import io.github.aedev.flow.data.model.SponsorBlockCategories
import io.github.aedev.flow.data.model.SponsorBlockSegment

/** What each [SponsorBlockAction] does, for the handler, the skip button and the seek bar alike. */
object SponsorBlockActionPolicy {
    private const val MUTE_ACTION_TYPE = "mute"

    /**
     * The action for [segment]. A segment submitted as a mute (a swear word, a short sound) is muted
     * where the category would skip it, because skipping it would cut speech around it.
     */
    fun actionFor(
        segment: SponsorBlockSegment,
        categoryActions: Map<String, SponsorBlockAction>,
    ): SponsorBlockAction {
        val action = categoryActions[segment.category] ?: SponsorBlockCategories.defaultAction(segment.category)
        val skips = action == SponsorBlockAction.SKIP || action == SponsorBlockAction.SKIP_ONCE
        return if (skips && segment.actionType == MUTE_ACTION_TYPE) SponsorBlockAction.MUTE else action
    }

    /** Whether reaching the segment jumps past it, given whether it was already skipped once this video. */
    fun skipsAutomatically(
        action: SponsorBlockAction,
        alreadySkippedOnce: Boolean,
    ): Boolean =
        when (action) {
            SponsorBlockAction.SKIP -> true
            SponsorBlockAction.SKIP_ONCE -> !alreadySkippedOnce
            else -> false
        }

    /** Whether the manual Skip button shows while playback is inside the segment. */
    fun showsSkipButton(
        action: SponsorBlockAction,
        alreadySkippedOnce: Boolean,
    ): Boolean =
        when (action) {
            SponsorBlockAction.SKIP_BUTTON, SponsorBlockAction.SHOW_TOAST, SponsorBlockAction.MUTE -> true
            SponsorBlockAction.SKIP_ONCE -> alreadySkippedOnce
            SponsorBlockAction.SKIP, SponsorBlockAction.IGNORE, SponsorBlockAction.DISABLED -> false
        }

    /** Whether the segment is drawn on the seek bar at all. */
    fun showsOnSeekBar(action: SponsorBlockAction): Boolean = action != SponsorBlockAction.DISABLED

    /** The segments the seek bar and the skip button see; disabled categories are left out. */
    fun visibleSegments(
        segments: List<SponsorBlockSegment>,
        categoryActions: Map<String, SponsorBlockAction>,
    ): List<SponsorBlockSegment> = segments.filter { showsOnSeekBar(actionFor(it, categoryActions)) }
}
