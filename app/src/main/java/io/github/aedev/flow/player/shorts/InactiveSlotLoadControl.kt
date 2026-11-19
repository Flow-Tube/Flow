package io.github.aedev.flow.player.shorts

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * A pooled Shorts player that is not the one on screen loads only [inactiveBufferUs]: enough to start
 * the moment its page comes up, not a full window for a reel that may be swiped past. YouTube gives its
 * next Short 2 s the same way. Once the slot is active, [delegate] decides as usual.
 *
 * Every method forwards explicitly: Kotlin's `by` delegation skips Java default methods, which would
 * leave the player calling LoadControl's "not implemented" stubs.
 */
@UnstableApi
internal class InactiveSlotLoadControl(
    private val delegate: LoadControl,
    private val isActive: () -> Boolean,
    private val inactiveBufferUs: Long = INACTIVE_BUFFER_US,
) : LoadControl {
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        // Asked first either way, so the stock control keeps its own loading state current.
        val delegateWants = delegate.shouldContinueLoading(parameters)
        return delegateWants && (isActive() || parameters.bufferedDurationUs < inactiveBufferUs)
    }

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = delegate.shouldStartPlayback(parameters)

    companion object {
        const val INACTIVE_BUFFER_US = 2_000_000L
    }
}
