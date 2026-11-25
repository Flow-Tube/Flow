package io.github.aedev.flow.ui.screens.player.state

import io.github.aedev.flow.player.stream.UpcomingPoll

/**
 * How the player watches a premiere or a scheduled stream that has not started, as YouTube's own
 * player does: one light `/player` call at the interval the server asks for, whether or not the
 * start time is known, and the full load only once that call says the video plays, the broadcast
 * behind it changed, or it stopped being upcoming at all.
 */
internal object UpcomingPollPolicy {
    /** What to do after one poll. */
    sealed interface Step {
        /**
         * Load the video for real. [keepPolling] is false when the video stopped being upcoming
         * without becoming playable: that load shows why, and asking again would not change it.
         */
        data class Load(
            val keepPolling: Boolean,
            val broadcastId: String?,
        ) : Step

        /** Ask again after [delayMs]; [broadcastId] is the one to compare the next answer with. */
        data class Wait(
            val delayMs: Long,
            val broadcastId: String?,
        ) : Step
    }

    /**
     * The wait the server asked for: `liveStreamability.pollDelayMs`, else the heartbeat interval,
     * else [DEFAULT_DELAY_MS]. Never below [MIN_DELAY_MS], so a zero cannot become a busy loop.
     */
    fun delayMs(poll: UpcomingPoll?): Long =
        (poll?.pollDelayMs?.takeIf { it > 0L } ?: poll?.heartbeatIntervalMs?.takeIf { it > 0L })
            ?.coerceAtLeast(MIN_DELAY_MS)
            ?: DEFAULT_DELAY_MS

    /**
     * [poll] is null when the call failed, which waits the default interval and keeps the broadcast
     * id it had. The first broadcast id seen is remembered, not taken for a change.
     */
    fun next(
        knownBroadcastId: String?,
        poll: UpcomingPoll?,
    ): Step {
        if (poll == null) return Step.Wait(DEFAULT_DELAY_MS, knownBroadcastId)
        val broadcastId = poll.broadcastId ?: knownBroadcastId
        val changed = knownBroadcastId != null && poll.broadcastId != null && poll.broadcastId != knownBroadcastId
        return when {
            poll.isPlayable || changed -> Step.Load(keepPolling = true, broadcastId = broadcastId)
            !poll.isUpcoming -> Step.Load(keepPolling = false, broadcastId = broadcastId)
            else -> Step.Wait(delayMs(poll), broadcastId)
        }
    }

    const val DEFAULT_DELAY_MS = 30_000L
    const val MIN_DELAY_MS = 5_000L

    /**
     * Full loads one countdown is worth. The light call can say a video plays while the full load
     * still lands on the countdown; this keeps that disagreement from loading every interval.
     */
    const val MAX_FULL_LOADS = 10
}
