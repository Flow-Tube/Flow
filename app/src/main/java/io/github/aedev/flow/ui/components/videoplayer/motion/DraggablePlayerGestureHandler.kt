package io.github.aedev.flow.ui.components.videoplayer.motion

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import io.github.aedev.flow.ui.components.videoplayer.MiniPlayerCorner
import io.github.aedev.flow.ui.components.videoplayer.MiniPlayerTuckSide
import io.github.aedev.flow.ui.components.videoplayer.PlayerDraggableState
import io.github.aedev.flow.ui.components.videoplayer.canSwipeUpToFullscreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val DRAG_MODE_FRACTION = 0
private const val DRAG_MODE_EXPAND_SCALE = 1

/**
 * Where a mini player drag left the finger, how far it went, whether the player followed it, and
 * whether a second finger or another handler took the gesture over.
 */
private class MiniDrag(
    val fingerAt: Offset,
    val travel: Float,
    val moved: Boolean,
    val interrupted: Boolean,
)

/**
 * The one-finger gesture on the video box: collapse drag and swipe-to-fullscreen while expanded,
 * free 2-D drag, tap, double tap, swipe down to close and tucking into an edge while mini.
 *
 * Hand-rolled on purpose. `anchoredDraggable` and `draggable2D` apply touch slop and report
 * deltas in local space, but this node sits under the morph's graphicsLayer scale, so the same
 * finger travel is 2-3x more local distance in the mini player and the collapse mapping changes
 * as the scale shrinks mid-drag. Every delta and slop here is scaled through
 * [DraggablePlayerGestureMetrics.liveGestureScale] at read time to keep the hand-tuned physics.
 *
 * Taps are classified inside this loop rather than by a separate `detectTapGestures` node: the
 * modifier chain on the video box must stay structurally constant, because inserting or removing
 * a pointer-input node while a finger is down re-pairs the remaining nodes by position, resets
 * their keys and cancels the drag coroutine mid-gesture, which leaves the sheet frozen wherever
 * the finger was.
 *
 * Velocity is measured on the finger's screen-space path (the scaled deltas summed from the
 * down), not on the raw local positions: the node moves with the finger, so local positions
 * barely change while the mini tracks it and then jump once it is pinned against a bound, which
 * read as a horizontal fling and dismissed the player on an ordinary diagonal throw.
 */
internal class DraggablePlayerGestureHandler(
    private val state: PlayerDraggableState,
    private val metrics: DraggablePlayerGestureMetrics,
) {
    private val velocityTracker = VelocityTracker()
    private val tapDecider = MiniPlayerTapDecider()
    private var singleTapJob: Job? = null
    private var doubleTapTimeoutMillis = 300L

    suspend fun AwaitPointerEventScope.handleGesture() {
        val gestureTargetMiniX = metrics.targetMiniX
        val gestureTargetMiniY = metrics.targetMiniY

        val down = awaitFirstDown(requireUnconsumed = false)
        doubleTapTimeoutMillis = viewConfiguration.doubleTapTimeoutMillis
        val downConsumedByChild = down.isConsumed
        if (state.openOrigin != null) {
            awaitAllPointersUp()
            return
        }

        if (state.expandFraction.value > 0.8f) {
            handleMiniGesture(down, downConsumedByChild)
            return
        }
        val isCollapseDrag = state.expandFraction.value < 0.4f

        val canSwipeToFullscreen =
            isCollapseDrag &&
                canSwipeUpToFullscreen(
                    isFullscreen = metrics.isFullscreen,
                    isLandscape = metrics.isLandscape,
                    isLargeWindow = metrics.isLargeScreen,
                ) &&
                metrics.onFullscreenGesture != null

        var fingerPath = Offset.Zero
        velocityTracker.resetTracking()
        velocityTracker.addPosition(down.uptimeMillis, fingerPath)

        if (isCollapseDrag) {
            state.scope.launch {
                state.motion.stopFraction()
                state.motion.snapOffsets(x = gestureTargetMiniX, y = gestureTargetMiniY)
            }
        }

        val dragPointerId = down.id
        var hasCrossedSlop = !isCollapseDrag
        var startDragY = 0f
        var detectedDirection = 0

        if (isCollapseDrag) {
            val slop = viewConfiguration.touchSlop
            while (!hasCrossedSlop) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == dragPointerId }
                if (change == null || !change.pressed || change.isConsumed) {
                    break
                }
                val delta = (change.position - down.position) * metrics.liveGestureScale(state)
                velocityTracker.addPosition(change.uptimeMillis, delta)
                if (delta.y > slop && delta.y > abs(delta.x)) {
                    hasCrossedSlop = true
                    startDragY = delta.y
                    detectedDirection = 1
                    fingerPath = delta
                    change.consume()
                } else if (canSwipeToFullscreen && delta.y < -slop && abs(delta.y) > abs(delta.x)) {
                    hasCrossedSlop = true
                    startDragY = delta.y
                    detectedDirection = -1
                    fingerPath = delta
                    change.consume()
                } else if (abs(delta.x) > slop) {
                    break
                }
            }
        }

        var cumulativeDragY = startDragY
        val startFraction = state.expandFraction.value
        var totalUpwardDrag = 0f

        if (hasCrossedSlop) {
            state.isDragging = true
            val snapSignal = Channel<Unit>(Channel.CONFLATED)
            var pendingFraction = state.expandFraction.value
            var pendingMode = DRAG_MODE_FRACTION
            var pendingExpandScale = 1f
            val snapDriver =
                state.scope.launch {
                    for (ignored in snapSignal) {
                        when (pendingMode) {
                            DRAG_MODE_FRACTION -> {
                                state.expandFraction.snapTo(pendingFraction)
                            }

                            else -> {
                                state.expandDragScale.snapTo(pendingExpandScale)
                            }
                        }
                    }
                }
            try {
                drag(dragPointerId) { change ->
                    val delta = change.positionChange() * metrics.liveGestureScale(state)
                    fingerPath += delta
                    velocityTracker.addPosition(change.uptimeMillis, fingerPath)

                    if (isCollapseDrag && detectedDirection == 1) {
                        change.consume()
                        cumulativeDragY += delta.y
                        val collapseTravel = (metrics.targetMiniY - metrics.statusBarHeight).coerceAtLeast(1f)
                        pendingFraction = (startFraction + cumulativeDragY / collapseTravel).coerceIn(0f, 1f)
                        pendingMode = DRAG_MODE_FRACTION
                        snapSignal.trySend(Unit)
                    } else if (isCollapseDrag && detectedDirection == -1) {
                        change.consume()
                        totalUpwardDrag += -delta.y
                        pendingExpandScale = expandDragZoomFor(totalUpwardDrag, metrics.thresholds)
                        pendingMode = DRAG_MODE_EXPAND_SCALE
                        snapSignal.trySend(Unit)
                    }
                }
                currentEvent.changes.firstOrNull { it.id == dragPointerId }?.let { up ->
                    fingerPath += up.positionChange() * metrics.liveGestureScale(state)
                    velocityTracker.addLiftOff(up.uptimeMillis, fingerPath)
                }
            } finally {
                snapSignal.close()
                snapDriver.cancel()
                state.isDragging = false
                state.scope.launch { state.expandDragScale.animateTo(1f, dragReleaseSpringSpec) }
            }
        } else {
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    if (event.changes.all { !it.pressed }) break
                }
            } finally {
                state.isDragging = false
            }
        }

        if (isCollapseDrag && detectedDirection == -1) {
            val velY = velocityTracker.calculateVelocity().y
            if (shouldEnterFullscreenFromSwipe(totalUpwardDrag, velY, metrics.thresholds)) {
                metrics.onFullscreenGesture?.invoke()
            }
            return
        }

        if (isCollapseDrag) {
            val velY = velocityTracker.calculateVelocity().y
            val collapseTravel = metrics.targetMiniY - metrics.statusBarHeight
            if (shouldCollapseOnRelease(state.expandFraction.value, velY, metrics.thresholds)) {
                metrics.onCollapseGesture?.invoke()
                state.collapse(fractionVelocity(velY, collapseTravel))
            } else {
                state.expand(fractionVelocity(velY, collapseTravel))
            }
            return
        }
    }

    /**
     * A press on the mini player. It follows the finger from half the touch slop, and a release that
     * travelled less than [PlayerGestureThresholds.miniTapMovement] is a tap. A second finger hands the gesture to the
     * pinch, so the player is never dragged and re-cornered underneath a resize.
     */
    private suspend fun AwaitPointerEventScope.handleMiniGesture(
        down: PointerInputChange,
        downConsumedByChild: Boolean,
    ) {
        velocityTracker.resetTracking()
        velocityTracker.addPosition(down.uptimeMillis, Offset.Zero)
        val startCorner = state.corner
        state.scope.launch { state.motion.stopOffsets() }
        val drag = dragMini(down)
        when {
            drag.interrupted -> {
                awaitAllPointersUp()
            }

            drag.travel < metrics.thresholds.miniTapMovement -> {
                if (drag.moved) releaseMini(drag.fingerAt, startCorner)
                onMiniTap(down.uptimeMillis, downConsumedByChild)
            }

            else -> {
                releaseMini(drag.fingerAt, startCorner)
            }
        }
    }

    /** Moves the mini player with the finger until it lifts or a second finger lands. */
    private suspend fun AwaitPointerEventScope.dragMini(down: PointerInputChange): MiniDrag {
        var rawX = state.offsetX.value
        var rawY = state.offsetY.value
        var fingerPath = Offset.Zero
        var travel = 0f
        var following = false
        val snapSignal = Channel<Unit>(Channel.CONFLATED)
        val snapDriver =
            state.scope.launch {
                for (ignored in snapSignal) {
                    state.offsetX.snapTo(miniDragX(rawX))
                    state.offsetY.snapTo(miniDragY(rawY))
                }
            }
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) {
                    if (change != null) {
                        velocityTracker.addLiftOff(
                            change.uptimeMillis,
                            fingerPath + change.positionChange() * metrics.liveGestureScale(state),
                        )
                    }
                    break
                }
                if (change.isConsumed || event.changes.count { it.pressed } > 1) {
                    return MiniDrag(Offset(rawX, rawY), travel, following, interrupted = true)
                }
                val delta = change.positionChange() * metrics.liveGestureScale(state)
                fingerPath += delta
                travel += delta.getDistance()
                velocityTracker.addPosition(change.uptimeMillis, fingerPath)
                if (!following && travel > viewConfiguration.touchSlop * 0.5f) {
                    following = true
                    state.isDragging = true
                    state.tuckedSide = null
                }
                if (following) {
                    change.consume()
                    rawX += delta.x
                    rawY += delta.y
                    snapSignal.trySend(Unit)
                }
            }
        } finally {
            snapSignal.close()
            snapDriver.cancel()
            state.isDragging = false
        }
        return MiniDrag(Offset(rawX, rawY), travel, following, interrupted = false)
    }

    private fun onMiniTap(
        uptimeMillis: Long,
        downConsumedByChild: Boolean,
    ) {
        if (state.tuckedSide != null) {
            untuck()
            return
        }
        if (downConsumedByChild || !metrics.tapToExpand) return
        singleTapJob?.cancel()
        when (tapDecider.onTap(uptimeMillis, doubleTapTimeoutMillis)) {
            MiniPlayerTap.DOUBLE -> {
                toggleWideMode()
            }

            MiniPlayerTap.SINGLE_PENDING -> {
                singleTapJob =
                    state.scope.launch {
                        delay(doubleTapTimeoutMillis)
                        state.expand()
                    }
            }
        }
    }

    private fun miniDragX(rawX: Float): Float =
        if (state.isInlineMode && !metrics.isLargeScreen) {
            metrics.stablePhoneCenteredX
        } else {
            rubberBand(rawX, metrics.minX, metrics.maxX)
        }

    private fun miniDragY(rawY: Float): Float = rubberBand(rawY, metrics.minY, metrics.maxY)

    private suspend fun AwaitPointerEventScope.awaitAllPointersUp() {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            if (event.changes.all { !it.pressed }) return
        }
    }

    private fun toggleWideMode() {
        if (state.isInlineMode) {
            state.shrinkToCorner(
                baseMiniWidth = metrics.baseMiniWidth,
                screenWidth = metrics.screenWidth,
                margin = metrics.margin,
                minY = metrics.minY,
                screenHeight = metrics.screenHeight,
                bottomNavPad = metrics.bottomNavPad,
            )
        } else {
            state.expandWide(
                screenWidth = metrics.screenWidth,
                margin = metrics.margin,
                baseMiniWidth = metrics.baseMiniWidth,
                screenHeight = metrics.screenHeight,
                minY = metrics.minY,
                bottomNavPad = metrics.bottomNavPad,
                isLargeWindow = metrics.isLargeScreen,
            )
        }
    }

    private fun closeDownward(velocityY: Float) {
        state.scope.launch {
            launch {
                state.motion.moveOffsets {
                    state.offsetY.animateTo(
                        metrics.screenHeight + metrics.margin,
                        miniDismissSpringSpec,
                        initialVelocity = velocityY.coerceAtLeast(0f),
                    )
                }
            }
            delay(MINI_DISMISS_TEARDOWN_DELAY_MS)
            metrics.onDismiss()
        }
    }

    private fun releaseMini(
        fingerAt: Offset,
        startCorner: MiniPlayerCorner,
    ) {
        val velocity = velocityTracker.calculateVelocity()
        val velY = velocity.y
        val velX = velocity.x
        val startedAtBottom = !startCorner.isTop
        if (shouldCloseMiniDownward(fingerAt.y, metrics.maxY, metrics.miniHeight, startedAtBottom, velX, velY, metrics.thresholds)) {
            closeDownward(velY)
            return
        }
        val currentX = state.offsetX.value
        val currentY = state.offsetY.value
        val bounds = metrics.bounds
        val newCorner =
            resolveMiniPlayerCorner(
                current = state.corner,
                currentX = currentX,
                currentY = currentY,
                bounds = bounds,
                scaledVelocityX = velX,
                scaledVelocityY = velY,
                thresholds = metrics.thresholds,
            )
        val targetX = cornerTargetX(newCorner, bounds.minX, bounds.maxX)
        val targetY = cornerTargetY(newCorner, bounds.minY, bounds.maxY)

        if (state.isInlineMode) {
            state.corner = newCorner
            state.scope.launch {
                state.motion.moveOffsets {
                    if (metrics.isLargeScreen) {
                        launch { state.offsetX.animateTo(targetX, miniSnapSpringSpec, initialVelocity = velX) }
                    } else {
                        launch { state.offsetX.animateTo(metrics.stablePhoneCenteredX, miniSnapSpringSpec) }
                    }
                    launch { state.offsetY.animateTo(targetY, miniSnapSpringSpec, initialVelocity = velY) }
                }
            }
            return
        }

        val tuckSide = resolveMiniPlayerTuck(fingerAt.x, startCorner.isLeft, bounds, metrics.miniWidth, velX, velY, metrics.thresholds)
        if (tuckSide != null) {
            tuck(tuckSide, currentY, velX)
            return
        }
        state.corner = newCorner
        state.scope.launch {
            state.motion.moveOffsets {
                launch { state.offsetX.animateTo(targetX, miniSnapSpringSpec, initialVelocity = velX) }
                launch { state.offsetY.animateTo(targetY, miniSnapSpringSpec, initialVelocity = velY) }
            }
        }
    }

    private fun tuck(
        side: MiniPlayerTuckSide,
        currentY: Float,
        velocityX: Float,
    ) {
        val bounds = metrics.bounds
        val restY = currentY.coerceIn(bounds.minY, bounds.maxY)
        state.tuckedSide = side
        state.corner = cornerFor(left = side == MiniPlayerTuckSide.Left, top = restY < (bounds.minY + bounds.maxY) / 2f)
        state.scope.launch {
            state.motion.moveOffsets {
                launch {
                    state.offsetX.animateTo(
                        tuckedMiniX(side, metrics.screenWidth, metrics.miniWidth),
                        miniSnapSpringSpec,
                        initialVelocity = velocityX,
                    )
                }
                launch { state.offsetY.animateTo(restY, miniSnapSpringSpec) }
            }
        }
    }

    /** Brings a tucked mini player back to the corner on the side it was tucked into. */
    fun untuck() {
        if (state.tuckedSide == null) return
        state.tuckedSide = null
        val bounds = metrics.bounds
        val targetX = cornerTargetX(state.corner, bounds.minX, bounds.maxX)
        val targetY = cornerTargetY(state.corner, bounds.minY, bounds.maxY)
        state.scope.launch {
            state.motion.moveOffsets {
                launch { state.offsetX.animateTo(targetX, miniSnapSpringSpec) }
                launch { state.offsetY.animateTo(targetY, miniSnapSpringSpec) }
            }
        }
    }
}

/**
 * Records where and when the finger lifted. Without it a finger that stops and then lets go keeps
 * the speed of its last movement, so a drag that was held still could still release as a fling.
 */
private fun VelocityTracker.addLiftOff(
    uptimeMillis: Long,
    position: Offset,
) = addPosition(uptimeMillis, position)

internal fun Modifier.draggablePlayerGestures(handler: DraggablePlayerGestureHandler): Modifier =
    pointerInput(handler) {
        awaitEachGesture {
            with(handler) { handleGesture() }
        }
    }
