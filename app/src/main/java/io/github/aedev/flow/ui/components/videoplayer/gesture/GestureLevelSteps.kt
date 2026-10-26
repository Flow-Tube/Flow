package io.github.aedev.flow.ui.components.videoplayer.gesture

/** Level travel between two haptic ticks of a brightness or boost swipe: twenty steps per 100%. */
private const val GESTURE_TICK_STEP = 0.05f

/** Brightness below zero is auto, which gets a step of its own. */
internal const val AUTO_GESTURE_STEP = -1

/** Keeps a level that lands exactly on a step, such as 1.0, from flooring into the one below. */
private const val STEP_EPSILON = 1e-4f

private const val NO_TICK = Int.MIN_VALUE

/** The tick step a brightness or boost [level] falls in. */
internal fun gestureLevelStep(level: Float): Int {
    if (level < 0f) return AUTO_GESTURE_STEP
    return ((level + STEP_EPSILON) / GESTURE_TICK_STEP).toInt()
}

/**
 * The tick step of a volume swipe at [level]: the system stream's own step up to 100%, so the feel
 * matches the hardware keys, then every [GESTURE_TICK_STEP] of the app's boost above it, where the
 * stream is pinned at [maxVolume] and would otherwise go quiet.
 */
internal fun volumeGestureStep(
    level: Float,
    maxVolume: Int,
): Int =
    if (level <= 1f) {
        (level * maxVolume).toInt()
    } else {
        maxVolume + gestureLevelStep(level - 1f)
    }

/** Calls [onTick] each time a swipe moves into a new step, but not for the step it starts in. */
internal class GestureTicker(
    private val onTick: () -> Unit,
) {
    private var lastStep = NO_TICK

    fun reset() {
        lastStep = NO_TICK
    }

    fun onStep(step: Int) {
        if (step == lastStep) return
        if (lastStep != NO_TICK) onTick()
        lastStep = step
    }
}
