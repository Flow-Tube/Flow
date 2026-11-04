package io.github.aedev.flow.player.audio

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.aedev.flow.data.local.DEFAULT_VIDEO_VOLUME_NORMALIZATION
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.service.Media3MusicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.log10

/**
 * Manages audio-related features like skip silence and playback speed.
 *
 * Uses ExoPlayer's built-in [ExoPlayer.setSkipSilenceEnabled] instead of a manual
 * [SilenceSkippingAudioProcessor] to avoid audio-video desync. The built-in API
 * correctly adjusts the media clock when silence is removed, keeping audio and
 * video in perfect sync.
 *
 * **External Audio Processor Compatibility:**
 * This manager works with external audio processors like James DSP.
 * The audio session ID is exposed via [Media3MusicService.currentAudioSessionId]
 * which external apps can use to apply audio effects to Flow's output.
 */
@UnstableApi
class AudioFeaturesManager(
    private val scope: CoroutineScope,
    private val stateFlow: MutableStateFlow<EnhancedPlayerState>,
) {
    companion object {
        private const val TAG = "AudioFeaturesManager"

        /**
         * Get the current audio session ID for external audio processors.
         * Returns 0 if no active session exists.
         *
         * External apps like James DSP can use this to target Flow's audio output.
         */
        fun getAudioSessionId(): Int = Media3MusicService.currentAudioSessionId
    }

    private var playerRef: ExoPlayer? = null

    private var pendingSkipSilence: Boolean? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var desiredPlaybackSpeed: Float = 1.0f
    private var boostGainMb: Int = 0
    private var userVolume: Float = 1f
    private var normalizationEnabled: Boolean = DEFAULT_VIDEO_VOLUME_NORMALIZATION

    // Keyed by media id, so a queue item the screen never loaded plays at full level rather than
    // inheriting the gain of the video before it.
    private var loudness: Pair<String, Float>? = null

    /** The loudness correction in force for what is playing, for the stats panel. */
    val appliedLoudnessDb: Float?
        get() = loudness?.second?.takeIf { normalizationEnabled }

    /**
     * Set the player reference. Must be called after ExoPlayer is created.
     * Applies any pending skip-silence state that was set before the player was ready.
     */
    fun setPlayer(player: ExoPlayer) {
        this.playerRef = player
        if (desiredPlaybackSpeed != 1.0f) {
            player.setPlaybackParameters(PlaybackParameters(desiredPlaybackSpeed))
            // Published as well as applied. Releasing the player resets the state flow to its
            // defaults while this manager keeps the speed the viewer chose, so re-applying it to the
            // new player without saying so left everything reading the state at 1x while the audio
            // genuinely ran faster: the speed pill showed the wrong figure, and the long-press boost
            // compared against 1x and stepped to a speed the player was already at, so holding did
            // nothing until the speed was picked from the menu again (#867).
            stateFlow.value = stateFlow.value.copy(playbackSpeed = desiredPlaybackSpeed)
            Log.d(TAG, "Re-applied playback speed on new player: ${desiredPlaybackSpeed}x")
        }
        pendingSkipSilence?.let { pending ->
            player.skipSilenceEnabled = pending
            pendingSkipSilence = null
            Log.d(TAG, "Applied pending skip silence: $pending")
        }
        applyVolume(player)
    }

    /** Moves the gain onto the player's audio session, which ExoPlayer reports only after build. */
    fun onAudioSessionChanged() {
        val player = playerRef ?: return
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        applyLoudnessGain(player)
    }

    /**
     * Clear the player reference (call on release).
     */
    fun clearPlayer() {
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        playerRef = null
    }

    /**
     * Set skip silence state internally (without persisting).
     * Uses ExoPlayer's built-in skipSilenceEnabled for correct A/V sync.
     */
    fun setSkipSilenceInternal(isEnabled: Boolean) {
        val player = playerRef
        if (player != null) {
            player.skipSilenceEnabled = isEnabled
        } else {
            pendingSkipSilence = isEnabled
            Log.d(TAG, "Player not ready, queuing skip silence: $isEnabled")
        }
        stateFlow.value = stateFlow.value.copy(isSkipSilenceEnabled = isEnabled)
    }

    /**
     * Toggle skip silence and persist the preference.
     */
    fun toggleSkipSilence(
        isEnabled: Boolean,
        context: Context?,
    ) {
        setSkipSilenceInternal(isEnabled)
        context?.let { ctx ->
            scope.launch {
                PlayerPreferences(ctx).setSkipSilenceEnabled(isEnabled)
            }
        }
    }

    /**
     * Set playback speed.
     */
    fun setPlaybackSpeed(
        player: ExoPlayer?,
        speed: Float,
    ) {
        desiredPlaybackSpeed = speed
        stateFlow.value = stateFlow.value.copy(playbackSpeed = speed)
        val target = player ?: playerRef
        if (target != null) {
            target.setPlaybackParameters(PlaybackParameters(speed))
            Log.d(TAG, "Playback speed set to: ${speed}x")
        } else {
            Log.d(TAG, "Player not ready, queued playback speed: ${speed}x")
        }
    }

    /**
     * Sets playback gain, including above unity.
     *
     * ExoPlayer's own volume is documented as 0..1 and constrains anything above it, so a boost
     * factor set there was silently dropped and the slider moved without the audio changing
     * (#1051). Everything up to unity stays on the player; the part above it becomes amplifier gain
     * on the session, which is what LoudnessEnhancer is for.
     */
    fun setVolumeBoost(
        player: ExoPlayer?,
        volume: Float,
    ) {
        val exoPlayer = player ?: return
        userVolume = volume
        boostGainMb = boostGainMillibels(volume)
        applyVolume(exoPlayer)
        applyLoudnessGain(exoPlayer)
        Log.d(TAG, "Volume set to: $volume (player=${exoPlayer.volume}, boost=${boostGainMb}mB)")
    }

    /** YouTube's loudness for [mediaId], applied while that item is the one playing. */
    fun setLoudness(
        mediaId: String,
        gainDb: Float?,
    ) {
        loudness = gainDb?.let { mediaId to it }
        playerRef?.let(::applyVolume)
    }

    fun onMediaItemChanged() {
        playerRef?.let(::applyVolume)
    }

    private fun applyVolume(player: ExoPlayer) {
        val gainDb =
            loudness
                ?.takeIf { (mediaId, _) -> normalizationEnabled && mediaId == player.currentMediaItem?.mediaId }
                ?.second
        player.volume = userVolume.coerceIn(0f, 1f) * LoudnessGain.volume(gainDb)
    }

    /**
     * Observe skip silence preference changes.
     */
    fun observeSkipSilencePreference(
        context: Context,
        observerScope: CoroutineScope,
    ) {
        observerScope.launch {
            PlayerPreferences(context).skipSilenceEnabled.collect { isEnabled ->
                setSkipSilenceInternal(isEnabled)
            }
        }
    }

    fun observeVolumeNormalizationPreference(
        context: Context,
        observerScope: CoroutineScope,
    ) {
        observerScope.launch {
            PlayerPreferences(context).videoVolumeNormalizationEnabled.collect(::setVolumeNormalizationInternal)
        }
    }

    fun toggleVolumeNormalization(
        isEnabled: Boolean,
        context: Context?,
    ) {
        setVolumeNormalizationInternal(isEnabled)
        context?.let { ctx -> scope.launch { PlayerPreferences(ctx).setVideoVolumeNormalizationEnabled(isEnabled) } }
    }

    private fun setVolumeNormalizationInternal(isEnabled: Boolean) {
        normalizationEnabled = isEnabled
        playerRef?.let(::applyVolume)
        stateFlow.value = stateFlow.value.copy(isVolumeNormalizationEnabled = isEnabled)
    }

    private fun applyLoudnessGain(player: ExoPlayer) {
        val targetGainMb = boostGainMb.coerceIn(0, MAX_GAIN_MB)
        try {
            if (targetGainMb <= 0) {
                loudnessEnhancer?.release()
                loudnessEnhancer = null
                Log.d(TAG, "LoudnessEnhancer released")
                return
            }
            val sessionId = player.audioSessionId
            if (sessionId == android.media.AudioTrack.ERROR_BAD_VALUE || sessionId == 0) {
                Log.d(TAG, "No audio session yet — gain of ${targetGainMb}mB deferred")
                return
            }
            val existing = loudnessEnhancer
            if (existing != null) {
                existing.setTargetGain(targetGainMb)
                existing.enabled = true
            } else {
                loudnessEnhancer =
                    LoudnessEnhancer(sessionId).apply {
                        setTargetGain(targetGainMb)
                        enabled = true
                    }
            }
            Log.d(TAG, "LoudnessEnhancer at ${targetGainMb}mB on session $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure LoudnessEnhancer", e)
        }
    }
}

/** Ceiling for the boost gain, a little over the 2x the boost slider can ask for. */
private const val MAX_GAIN_MB = 2000

/**
 * The amplitude factor a boost asks for, as amplifier gain. A factor of 2.0 is +6.02 dB, which the
 * effect takes in hundredths of a decibel.
 */
internal fun boostGainMillibels(volume: Float): Int =
    if (volume <= 1f) {
        0
    } else {
        (2000.0 * log10(volume.toDouble())).toInt().coerceIn(0, MAX_GAIN_MB)
    }
