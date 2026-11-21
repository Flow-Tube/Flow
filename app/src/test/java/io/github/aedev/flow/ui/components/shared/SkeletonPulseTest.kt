package io.github.aedev.flow.ui.components.shared

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Each bone used to run its own infinite transition. The shared clock must give every bone, at
 * every moment, the value that bone's own animation spec would have.
 */
class SkeletonPulseTest {
    private fun ownAnimationValue(
        elapsedMillis: Long,
        durationMillis: Int,
        delayMillis: Int,
    ): Float =
        TargetBasedAnimation(
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(delayMillis),
                ),
            typeConverter = Float.VectorConverter,
            initialValue = 0f,
            targetValue = 1f,
        ).getValueFromNanos(elapsedMillis * 1_000_000L)

    @Test
    fun `every bone pulses as its own animation did`() {
        for (delay in listOf(0, 40, 80, 100, 120, 140, 160, 200, 280)) {
            for (elapsed in 0L..12_000L step 7L) {
                assertEquals(
                    "delay $delay at $elapsed ms",
                    ownAnimationValue(elapsed, 1200, delay),
                    skeletonPulseAt(elapsed.toFloat(), 1200, delay),
                    1e-4f,
                )
            }
        }
    }

    @Test
    fun `a clock parked at its end reads full`() {
        assertEquals(1f, skeletonPulseAt(SKELETON_CLOCK_SPAN_MILLIS.toFloat(), 1200, 0), 0f)
        assertEquals(1f, skeletonPulseAt(SKELETON_CLOCK_SPAN_MILLIS.toFloat(), 1200, 160), 0f)
    }

    @Test
    fun `a delayed bone holds its start until its delay passes`() {
        assertEquals(0f, skeletonPulseAt(100f, 1200, 120), 0f)
        assertEquals(0f, skeletonPulseAt(120f, 1200, 120), 0f)
    }
}
