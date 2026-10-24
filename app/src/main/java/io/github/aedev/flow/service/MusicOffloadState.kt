package io.github.aedev.flow.service

import kotlin.math.abs

/**
 * What decides whether the music player may use audio offload. It is the listener's choice first,
 * since some audio hardware takes offloaded tracks and plays silence or never starts (#1417, #1422).
 * Offloaded audio skips every audio processor and may not apply a speed or pitch, so offload stays
 * off while the sound is changed. A paused offloaded track also keeps the audio hardware out of
 * standby, and the system holds its AudioOffload wake lock until the player lets that track go, so a
 * pause that lasts gives offload up.
 */
internal data class MusicOffloadState(
    val enabledByUser: Boolean = false,
    val processing: Boolean = false,
    val playbackChanged: Boolean = false,
    val pausedLong: Boolean = false,
) {
    val allowed: Boolean get() = enabledByUser && !processing && !playbackChanged && !pausedLong

    companion object {
        // Sonic's own threshold: a speed saved from a slider can read 0.99999994, which plays untouched.
        private const val UNCHANGED_TOLERANCE = 0.0001f

        fun changesPlayback(
            speed: Float,
            pitch: Float,
        ): Boolean = abs(speed - 1f) >= UNCHANGED_TOLERANCE || abs(pitch - 1f) >= UNCHANGED_TOLERANCE
    }
}
