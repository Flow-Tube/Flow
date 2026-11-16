package io.github.aedev.flow.player.audio

import androidx.media3.common.Player
import io.github.aedev.flow.player.MutePausePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Pauses a video when the device is muted and resumes it when sound comes back, as music does (#2087). */
internal class VideoMutePause(
    private val player: Player,
) : Player.Listener {
    var enabled = false
    private var pausedForMute = false

    override fun onPlayWhenReadyChanged(
        playWhenReady: Boolean,
        reason: Int,
    ) {
        if (playWhenReady) pausedForMute = false
    }

    override fun onDeviceVolumeChanged(
        volume: Int,
        muted: Boolean,
    ) {
        when (MutePausePolicy.onVolume(muted || volume == 0, enabled, player.isPlaying, pausedForMute)) {
            MutePausePolicy.Action.PAUSE -> {
                player.pause()
                pausedForMute = true
            }

            MutePausePolicy.Action.RESUME -> {
                pausedForMute = false
                player.play()
            }

            MutePausePolicy.Action.NONE -> {}
        }
    }

    companion object {
        /** Listens on [player] for as long as it lives, following [enabled] while [scope] is open. */
        fun attach(
            player: Player,
            enabled: Flow<Boolean>,
            scope: CoroutineScope,
        ): VideoMutePause =
            VideoMutePause(player).also { pause ->
                player.addListener(pause)
                scope.launch { enabled.collect { pause.enabled = it } }
            }
    }
}
