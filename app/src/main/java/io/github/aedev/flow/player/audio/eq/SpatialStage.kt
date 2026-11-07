package io.github.aedev.flow.player.audio.eq

import io.github.aedev.flow.data.audio.eq.EqSpatialSound
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Widens a stereo image: more of the side signal, then a little of each channel, delayed like an
 * early reflection and dulled the way the far ear hears it, fed into the other. A delay this long
 * reads as space, where a sub-millisecond one arrives in phase and narrows the low end instead.
 * Android's Spatializer leaves stereo alone by
 * default, Virtualizer depends on the device and Media3's channel mixer has no delay or filter, so
 * this runs in the player's own chain. Off is an exact pass-through, and every change glides.
 */
internal class SpatialStage(
    sampleRate: Int,
) {
    private val delaySamples = (sampleRate * CROSSFEED_DELAY_SECONDS).roundToInt().coerceAtLeast(1)
    private val delayedLeft = DoubleArray(delaySamples)
    private val delayedRight = DoubleArray(delaySamples)
    private val lowPass = 1.0 - exp(-2.0 * PI * CROSSFEED_CUTOFF_HZ / sampleRate)
    private var delayIndex = 0
    private var dulledLeft = 0.0
    private var dulledRight = 0.0

    private var width = 1.0
    private var crossfeed = 0.0
    private var level = 1.0
    private var targetWidth = 1.0
    private var targetCrossfeed = 0.0
    private var targetLevel = 1.0
    private var widthStep = 0.0
    private var crossfeedStep = 0.0
    private var levelStep = 0.0
    private var rampFramesLeft = 0

    /** Nothing is being changed, so the signal could skip this stage entirely. */
    val isIdle: Boolean
        get() = rampFramesLeft == 0 && targetWidth == 1.0

    fun setTarget(
        sound: EqSpatialSound,
        rampFrames: Int,
    ) {
        targetWidth = widthOf(sound)
        targetCrossfeed = crossfeedOf(sound)
        targetLevel = levelFor(targetWidth, targetCrossfeed)
        if (rampFrames <= 0) {
            width = targetWidth
            crossfeed = targetCrossfeed
            level = targetLevel
            rampFramesLeft = 0
            return
        }
        widthStep = (targetWidth - width) / rampFrames
        crossfeedStep = (targetCrossfeed - crossfeed) / rampFrames
        levelStep = (targetLevel - level) / rampFrames
        rampFramesLeft = rampFrames
    }

    fun clearHistory() {
        delayedLeft.fill(0.0)
        delayedRight.fill(0.0)
        delayIndex = 0
        dulledLeft = 0.0
        dulledRight = 0.0
    }

    /** Replaces the left and right samples in [frame]. */
    fun process(frame: DoubleArray) {
        val left = frame[0]
        val right = frame[1]
        dulledLeft += lowPass * (delayedRight[delayIndex] - dulledLeft)
        dulledRight += lowPass * (delayedLeft[delayIndex] - dulledRight)
        delayedLeft[delayIndex] = left
        delayedRight[delayIndex] = right
        delayIndex = (delayIndex + 1) % delaySamples

        val mid = (left + right) / 2
        val side = (left - right) / 2 * width
        frame[0] = (mid + side + crossfeed * dulledLeft) * level
        frame[1] = (mid - side + crossfeed * dulledRight) * level
        stepRamp()
    }

    private fun stepRamp() {
        if (rampFramesLeft == 0) return
        rampFramesLeft--
        if (rampFramesLeft == 0) {
            width = targetWidth
            crossfeed = targetCrossfeed
            level = targetLevel
        } else {
            width += widthStep
            crossfeed += crossfeedStep
            level += levelStep
        }
    }

    private companion object {
        const val CROSSFEED_DELAY_SECONDS = 0.015
        const val CROSSFEED_CUTOFF_HZ = 2_700.0

        /** Side energy of typical music relative to its mid; the level keeps that music about as loud. */
        const val TYPICAL_SIDE_ENERGY = 0.16

        fun widthOf(sound: EqSpatialSound): Double =
            when (sound) {
                EqSpatialSound.OFF -> 1.0
                EqSpatialSound.LOW -> 1.5
                EqSpatialSound.MEDIUM -> 2.0
                EqSpatialSound.HIGH -> 2.5
            }

        fun crossfeedOf(sound: EqSpatialSound): Double =
            when (sound) {
                EqSpatialSound.OFF -> 0.0
                EqSpatialSound.LOW -> 0.1
                EqSpatialSound.MEDIUM -> 0.15
                EqSpatialSound.HIGH -> 0.2
            }

        fun levelFor(
            width: Double,
            crossfeed: Double,
        ): Double {
            if (width == 1.0) return 1.0
            val energy = (1 + TYPICAL_SIDE_ENERGY * width * width) / (1 + TYPICAL_SIDE_ENERGY)
            return 1 / (sqrt(energy) * (1 + crossfeed / 2))
        }
    }
}
