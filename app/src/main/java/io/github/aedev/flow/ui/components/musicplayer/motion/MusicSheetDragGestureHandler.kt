package io.github.aedev.flow.ui.components.musicplayer.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class MusicSheetDragGestureHandler(
    private val scope: CoroutineScope,
    private val velocityTracker: VelocityTracker,
    private val densityProvider: () -> Density,
    private val motionController: MusicSheetMotionController,
    private val expansionFraction: Animatable<Float, AnimationVector1D>,
    private val translationY: Animatable<Float, AnimationVector1D>,
    private val expandedYProvider: () -> Float,
    private val collapsedYProvider: () -> Float,
    private val miniHeightPxProvider: () -> Float,
    private val isExpandedProvider: () -> Boolean,
    private val onDraggingChange: (Boolean) -> Unit,
    private val onSettle: (targetExpanded: Boolean, velocity: Float, dampingRatio: Float, squash: Float) -> Unit,
    private val onCloseDownward: () -> Unit,
) {
    private var initialFractionOnDragStart = 0f
    private var initialYOnDragStart = 0f
    private var accumulatedDragY = 0f
    private var dragSnapJob: Job? = null

    fun onDragStart() {
        dragSnapJob?.cancel()
        dragSnapJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                motionController.stop()
            }
        onDraggingChange(true)
        velocityTracker.resetTracking()
        initialFractionOnDragStart = expansionFraction.value
        initialYOnDragStart = translationY.value
        accumulatedDragY = 0f
    }

    fun onVerticalDrag(
        change: PointerInputChange,
        dragAmount: Float,
    ) {
        accumulatedDragY += dragAmount
        val frame =
            computeMusicSheetDragFrame(
                currentTranslationY = translationY.value,
                dragAmount = dragAmount,
                expandedY = expandedYProvider(),
                collapsedY = collapsedYProvider(),
                miniHeightPx = miniHeightPxProvider(),
                initialFractionOnDragStart = initialFractionOnDragStart,
                initialYOnDragStart = initialYOnDragStart,
                canPullToClose = initialFractionOnDragStart == 0f,
            )
        dragSnapJob?.cancel()
        dragSnapJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                motionController.snapTo(
                    translationYValue = frame.translationY,
                    expansionFractionValue = frame.expansionFraction,
                )
            }
        // The bar moves with the finger, so its local positions barely change; the change's own
        // deltas give the finger's real speed (#2064).
        velocityTracker.addPointerInputChange(change)
    }

    fun onDragEnd() {
        dragSnapJob?.cancel()
        dragSnapJob = null
        onDraggingChange(false)

        val verticalVelocity = velocityTracker.calculateVelocity().y
        val currentFraction = expansionFraction.value
        val startedCollapsed = initialFractionOnDragStart == 0f
        val closeFlingVelocity = with(densityProvider()) { CLOSE_FLING_DP_PER_SECOND.dp.toPx() }
        if (shouldCloseMusicMiniDownward(
                startedCollapsed,
                accumulatedDragY,
                miniHeightPxProvider(),
                verticalVelocity,
                closeFlingVelocity,
            )
        ) {
            accumulatedDragY = 0f
            onCloseDownward()
            return
        }
        val minDragThresholdPx = with(densityProvider()) { 5.dp.toPx() }

        val targetExpanded =
            resolveMusicSheetDragTarget(
                isExpanded = isExpandedProvider(),
                accumulatedDragY = accumulatedDragY,
                minDragThresholdPx = minDragThresholdPx,
                verticalVelocity = verticalVelocity,
                velocityThreshold = 150f,
                currentFraction = currentFraction,
            )
        val dampingRatio =
            if (targetExpanded) Spring.DampingRatioNoBouncy else collapseSpringDampingForFraction(currentFraction)
        val squash =
            if (targetExpanded) 1f else collapseInitialSquashForFraction(currentFraction)

        onSettle(targetExpanded, verticalVelocity, dampingRatio, squash)
        accumulatedDragY = 0f
    }

    fun onDragCancel() {
        onDragEnd()
    }
}

internal fun Modifier.musicSheetVerticalDragGesture(
    enabled: Boolean,
    handler: MusicSheetDragGestureHandler,
): Modifier {
    if (!enabled) return this
    return this.pointerInput(handler) {
        detectVerticalDragGestures(
            onDragStart = { handler.onDragStart() },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                handler.onVerticalDrag(change = change, dragAmount = dragAmount)
            },
            onDragEnd = { handler.onDragEnd() },
            onDragCancel = { handler.onDragCancel() },
        )
    }
}

internal fun musicSheetSettleSpring(dampingRatio: Float) =
    spring<Float>(
        dampingRatio = dampingRatio,
        stiffness = Spring.StiffnessLow,
    )
