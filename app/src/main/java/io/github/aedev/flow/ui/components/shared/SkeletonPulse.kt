package io.github.aedev.flow.ui.components.shared

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The one clock a skeleton's bones pulse from. [pulse] reads it, so call it only while drawing: the
 * clock then redraws the bones each frame without recomposing them.
 */
@Stable
class SkeletonClock internal constructor(
    private val elapsedMillis: State<Float>,
) {
    /**
     * The pulse of one bone, the same value its own `infiniteRepeatable(tween(durationMillis,
     * FastOutSlowInEasing), RepeatMode.Reverse, StartOffset(delayMillis))` would hold.
     */
    fun pulse(
        durationMillis: Int,
        delayMillis: Int,
    ): Float = skeletonPulseAt(elapsedMillis.value, durationMillis, delayMillis)

    /** How far a shimmer band is through its sweep, from 0 up to 1; a parked clock reads 1, past the end. */
    fun sweep(periodMillis: Int): Float = skeletonSweepAt(elapsedMillis.value, periodMillis)
}

internal const val SKELETON_CLOCK_SPAN_MILLIS = 2_400_000

/**
 * The pulse [elapsedMillis] into the clock. A clock held at its end, which is where a transition
 * parks when animations are off, reads full, as each bone's own animation did.
 */
internal fun skeletonPulseAt(
    elapsedMillis: Float,
    durationMillis: Int,
    delayMillis: Int,
): Float {
    if (elapsedMillis >= SKELETON_CLOCK_SPAN_MILLIS) return 1f
    val local = elapsedMillis - delayMillis
    if (local <= 0f || durationMillis <= 0) return 0f
    val cycle = local % (2f * durationMillis)
    val fraction = if (cycle < durationMillis) cycle / durationMillis else (2f * durationMillis - cycle) / durationMillis
    return FastOutSlowInEasing.transform(fraction)
}

internal fun skeletonSweepAt(
    elapsedMillis: Float,
    periodMillis: Int,
): Float {
    if (elapsedMillis >= SKELETON_CLOCK_SPAN_MILLIS || periodMillis <= 0) return 1f
    return (elapsedMillis.coerceAtLeast(0f) % periodMillis) / periodMillis
}

/**
 * Where the middle of a shimmer band of half width [halfWidth] sits along the sweep at [fraction]:
 * just before [span] starts at 0, just past its end at 1, so the band enters and leaves fully.
 */
internal fun skeletonSweepCentre(
    fraction: Float,
    span: Float,
    halfWidth: Float,
): Float = -halfWidth + (span + 2f * halfWidth) * fraction

private val LocalSkeletonClock = staticCompositionLocalOf<SkeletonClock?> { null }

/** A linear millisecond count over 40 minutes, far longer than any skeleton stays on screen. */
@Composable
private fun rememberSkeletonClock(): SkeletonClock {
    val elapsed =
        rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 0f,
            targetValue = SKELETON_CLOCK_SPAN_MILLIS.toFloat(),
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = SKELETON_CLOCK_SPAN_MILLIS, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "skeleton_clock",
        )
    return remember(elapsed) { SkeletonClock(elapsed) }
}

/** The clock of the enclosing [SkeletonSurface], or a clock of this caller's own outside one. */
@Composable
internal fun currentSkeletonClock(): SkeletonClock = LocalSkeletonClock.current ?: rememberSkeletonClock()

/**
 * Gives every bone inside [content] one shared pulse clock, which stops when the skeleton leaves
 * composition. Nested surfaces reuse the outer clock.
 */
@Composable
fun SkeletonSurface(content: @Composable () -> Unit) {
    if (LocalSkeletonClock.current != null) {
        content()
        return
    }
    CompositionLocalProvider(LocalSkeletonClock provides rememberSkeletonClock(), content = content)
}
