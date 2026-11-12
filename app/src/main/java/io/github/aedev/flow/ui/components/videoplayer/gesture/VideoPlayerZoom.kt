package io.github.aedev.flow.ui.components.videoplayer.gesture

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Resting bounds of the pinch transform. */
private const val ZOOM_MIN = 1f
private const val ZOOM_MAX = 6f

/** Hard stops for the stretch past [ZOOM_MIN]/[ZOOM_MAX] the fingers can reach. */
private const val ZOOM_OVERSHOOT_MIN = 0.6f
private const val ZOOM_OVERSHOOT_MAX = 8f

/** Share of the pinch honoured once past a bound, so it fights back instead of stopping dead. */
private const val ZOOM_RESISTANCE = 0.35f

/** Scale at which releasing settles the video all the way back to unzoomed. */
private const val ZOOM_RESET_THRESHOLD = 1.02f

/**
 * Two-finger pinch-to-zoom with pan, for the fullscreen video surface.
 *
 * Only claims the gesture once a second pointer arrives, so single-finger taps and the
 * brightness/volume/seek drags in [videoPlayerControls] are untouched.
 *
 * With [panEnabled] off the pinch only scales, about the centre.
 *
 * [scale] and [offsetX]/[offsetY] read back the transform the caller is currently applying;
 * [onTransform] publishes the next one. They are plain values rather than a state object because
 * the transform lives in the player screen state and is read in the draw phase — routing it through
 * a callback keeps it out of composition.
 */
fun Modifier.videoPlayerZoom(
    scope: CoroutineScope,
    scale: () -> Float,
    offsetX: () -> Float,
    offsetY: () -> Float,
    panEnabled: Boolean = true,
    onTransform: (scale: Float, offsetX: Float, offsetY: Float) -> Unit,
): Modifier =
    pointerInput("playerZoom", panEnabled) {
        var settleJob: Job? = null

        awaitEachGesture {
            val firstDown = awaitFirstDown(requireUnconsumed = false)

            var secondDown: PointerInputChange? = null
            while (secondDown == null) {
                val event = awaitPointerEvent()
                val first = event.changes.firstOrNull { it.id == firstDown.id }
                if (first == null || !first.pressed) return@awaitEachGesture
                secondDown =
                    event.changes.firstOrNull {
                        it.id != firstDown.id && it.pressed && !it.previousPressed
                    }
            }
            val second = secondDown ?: return@awaitEachGesture
            second.consume()

            // Picking the pinch back up mid-settle continues from wherever it got to.
            settleJob?.cancel()

            val firstId = firstDown.id
            val secondId = second.id
            var previousSpan = (firstDown.position - second.position).getDistance().coerceAtLeast(1f)
            var previousCentroid = (firstDown.position + second.position) / 2f
            // Tracked unresisted so that pushing past a bound and easing off retraces the same path.
            var rawScale = scale()

            while (true) {
                val event = awaitPointerEvent()
                val p1 = event.changes.firstOrNull { it.id == firstId }
                val p2 = event.changes.firstOrNull { it.id == secondId }
                if (p1 == null || p2 == null || !p1.pressed || !p2.pressed) break
                p1.consume()
                p2.consume()

                val span = (p1.position - p2.position).getDistance().coerceAtLeast(1f)
                val centroid = (p1.position + p2.position) / 2f
                val pan = if (panEnabled) centroid - previousCentroid else Offset.Zero

                rawScale =
                    (rawScale * (span / previousSpan))
                        .coerceIn(ZOOM_OVERSHOOT_MIN, ZOOM_OVERSHOOT_MAX)
                val appliedScale = resistedZoom(rawScale)
                val panned =
                    clampPan(
                        Offset(offsetX() + pan.x, offsetY() + pan.y),
                        appliedScale,
                        size.width,
                        size.height,
                    )
                onTransform(appliedScale, panned.x, panned.y)

                previousSpan = span
                previousCentroid = centroid
            }

            settleJob =
                scope.launch {
                    settleZoom(
                        fromScale = scale(),
                        fromOffset = Offset(offsetX(), offsetY()),
                        width = size.width,
                        height = size.height,
                        onTransform = onTransform,
                    )
                }
        }
    }

private fun resistedZoom(rawScale: Float): Float =
    when {
        rawScale < ZOOM_MIN -> ZOOM_MIN - (ZOOM_MIN - rawScale) * ZOOM_RESISTANCE
        rawScale > ZOOM_MAX -> ZOOM_MAX + (rawScale - ZOOM_MAX) * ZOOM_RESISTANCE
        else -> rawScale
    }

/**
 * Keeps the pan inside the area the zoom actually reveals. The bounds collapse to zero below 1x —
 * they must not be allowed to go negative, or the clamp itself throws.
 */
private fun clampPan(
    offset: Offset,
    scale: Float,
    width: Int,
    height: Int,
): Offset {
    val maxX = ((scale - 1f) * width / 2f).coerceAtLeast(0f)
    val maxY = ((scale - 1f) * height / 2f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/**
 * Springs an over-pinched transform back to the nearest resting one. Scale and pan are driven off a
 * single fraction so they cannot disagree part-way and slide the video out of its own bounds.
 */
private suspend fun settleZoom(
    fromScale: Float,
    fromOffset: Offset,
    width: Int,
    height: Int,
    onTransform: (scale: Float, offsetX: Float, offsetY: Float) -> Unit,
) {
    val targetScale =
        when {
            fromScale < ZOOM_RESET_THRESHOLD -> ZOOM_MIN
            fromScale > ZOOM_MAX -> ZOOM_MAX
            else -> fromScale
        }
    val targetOffset =
        if (targetScale <= ZOOM_MIN) {
            Offset.Zero
        } else {
            clampPan(fromOffset, targetScale, width, height)
        }
    if (targetScale == fromScale && targetOffset == fromOffset) return

    animate(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
    ) { fraction, _ ->
        onTransform(
            lerp(fromScale, targetScale, fraction),
            lerp(fromOffset.x, targetOffset.x, fraction),
            lerp(fromOffset.y, targetOffset.y, fraction),
        )
    }
}
