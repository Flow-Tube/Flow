package io.github.aedev.flow.player.audio

import io.github.aedev.flow.innertube.models.response.PlayerResponse
import kotlin.math.pow

/** YouTube's loudness metadata as a playback gain: loud tracks are turned down, quiet ones stay as they are. */
object LoudnessGain {
    private const val TARGET_LKFS = -14.0
    private const val MIN_GAIN_DB = -20f

    /**
     * Absolute levels come first, measured against the response's target as YouTube's player does,
     * so a target other than -14 LKFS is honoured. The playing format's own level comes before
     * [audioConfig]'s, which describes the original track: a dub read from it would be turned down
     * by the original's level.
     */
    fun gainDb(
        audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        formatLoudnessDb: Double?,
        formatAbsoluteLoudnessLkfs: Double? = null,
    ): Float? {
        val targetLkfs = audioConfig?.loudnessTargetLkfs ?: TARGET_LKFS
        val perFormat = audioConfig?.enablePerFormatLoudness != false
        val gainDb =
            formatAbsoluteLoudnessLkfs?.let { targetLkfs - it }
                ?: formatLoudnessDb?.takeIf { perFormat }?.let { -it }
                ?: audioConfig?.trackAbsoluteLoudnessLkfs?.let { targetLkfs - it }
                ?: audioConfig?.perceptualLoudnessDb?.let { targetLkfs - it }
                ?: audioConfig?.loudnessDb?.let { -it }
                ?: return null
        // Adding 0 turns the -0 of a 0 dB track into 0, which is what the stats panel prints.
        return gainDb.toFloat().coerceIn(MIN_GAIN_DB, 0f) + 0f
    }

    /** The player volume for [gainDb]; 1 when there is nothing to apply. */
    fun volume(gainDb: Float?): Float = gainDb?.let { 10.0.pow(it / 20.0).toFloat() } ?: 1f
}
