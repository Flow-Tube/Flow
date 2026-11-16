package io.github.aedev.flow.service

import androidx.annotation.OptIn
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import io.github.aedev.flow.player.config.PlayerConfig

/**
 * The music player's load control: [base] loads the whole song, and while [savesData] is on it stops at
 * the lean 30 s window instead. Media3 fixes a player's load control when the player is built, and
 * 1.11 ships no forwarding one, so the Data Saver check wraps the stock control rather than rebuilding
 * the player. The back buffer stays either way: it costs memory, not data.
 *
 * Every override is spelled out: Kotlin's `by` delegation skips Java default methods, and the
 * interface's defaults throw. These are the ten overloads `ExoPlayerImplInternal` 1.11 calls.
 */
@OptIn(UnstableApi::class)
internal class MusicLoadControl(
    private val base: DefaultLoadControl,
    initiallySavesData: Boolean,
) : LoadControl {
    @Volatile
    var savesData: Boolean = initiallySavesData

    private var capped = false

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val baseWants = base.shouldContinueLoading(parameters)
        if (!savesData) {
            capped = false
            return baseWants
        }
        // Same hysteresis as the stock window: fill to the cap, then wait until below the minimum, so
        // the radio wakes in bursts rather than for every few kilobytes.
        if (parameters.bufferedDurationUs >= LEAN_MAX_US) {
            capped = true
        } else if (parameters.bufferedDurationUs < LEAN_MIN_US) {
            capped = false
        }
        return baseWants && !capped
    }

    override fun onPrepared(playerId: PlayerId) = base.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = base.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = base.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = base.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = base.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = base.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = base.retainBackBufferFromKeyframe(playerId)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = base.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = base.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    private companion object {
        const val LEAN_MAX_US = PlayerConfig.MUSIC_MAX_BUFFER_MS * 1_000L
        const val LEAN_MIN_US = PlayerConfig.MUSIC_MIN_BUFFER_MS * 1_000L
    }
}
