package io.github.aedev.flow.ui.components.musicplayer.motion

import androidx.compose.animation.core.Spring
import androidx.compose.ui.util.lerp
import kotlin.math.abs

/** How far below its resting place a collapsed bar can be pulled, in bar heights, while closing it. */
private const val CLOSE_PULL_LIMIT_HEIGHTS = 1.5f
private const val CLOSE_PULL_HEIGHTS = 0.5f

/** How fast, in dp per second, a downward flick on the collapsed bar has to be to close it. */
internal const val CLOSE_FLING_DP_PER_SECOND = 300f

internal data class MusicSheetDragFrame(
    val translationY: Float,
    val expansionFraction: Float,
)

internal fun computeMusicSheetDragFrame(
    currentTranslationY: Float,
    dragAmount: Float,
    expandedY: Float,
    collapsedY: Float,
    miniHeightPx: Float,
    initialFractionOnDragStart: Float,
    initialYOnDragStart: Float,
    canPullToClose: Boolean = false,
): MusicSheetDragFrame {
    val pullLimit = if (canPullToClose) CLOSE_PULL_LIMIT_HEIGHTS else 0.2f
    val newY =
        (currentTranslationY + dragAmount)
            .coerceIn(
                expandedY - miniHeightPx * 0.2f,
                collapsedY + miniHeightPx * pullLimit,
            )
    val denominator = (collapsedY - expandedY).coerceAtLeast(1f)
    val dragRatio = (initialYOnDragStart - newY) / denominator
    val newFraction = (initialFractionOnDragStart + dragRatio).coerceIn(0f, 1f)
    return MusicSheetDragFrame(
        translationY = newY,
        expansionFraction = newFraction,
    )
}

internal fun resolveMusicSheetDragTarget(
    isExpanded: Boolean,
    accumulatedDragY: Float,
    minDragThresholdPx: Float,
    verticalVelocity: Float,
    velocityThreshold: Float,
    currentFraction: Float,
): Boolean =
    when {
        isExpanded && accumulatedDragY <= 0f -> true
        abs(accumulatedDragY) > minDragThresholdPx -> accumulatedDragY < 0
        abs(verticalVelocity) > velocityThreshold -> verticalVelocity < 0
        else -> currentFraction > 0.5f
    }

/** Whether a drag that started on the collapsed bar closes it: pulled half its height down, or flicked down. */
internal fun shouldCloseMusicMiniDownward(
    startedCollapsed: Boolean,
    accumulatedDragY: Float,
    miniHeightPx: Float,
    verticalVelocity: Float,
    closeFlingVelocity: Float,
): Boolean =
    startedCollapsed &&
        accumulatedDragY > 0f &&
        (accumulatedDragY > miniHeightPx * CLOSE_PULL_HEIGHTS || verticalVelocity > closeFlingVelocity)

internal fun collapseSpringDampingForFraction(currentFraction: Float): Float =
    lerp(
        start = Spring.DampingRatioNoBouncy,
        stop = Spring.DampingRatioLowBouncy,
        fraction = currentFraction,
    )

internal fun collapseInitialSquashForFraction(currentFraction: Float): Float = lerp(1.0f, 0.97f, currentFraction)
