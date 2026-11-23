package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableCollectionItemScope

private val HandleWidth: Dp = 20.dp
private val HandleHeight: Dp = 28.dp

@Composable
fun ReorderHandle(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
) {
    Column(
        modifier = modifier.size(width = HandleWidth, height = HandleHeight),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(tint),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(tint),
        )
    }
}

/**
 * A [ReorderHandle] with a minimum-size touch target that keeps the drawn handle's footprint, so a
 * row's thumbnail doesn't move: the extra touch area overhangs into the row's padding and the gap
 * before the next item.
 */
@Composable
fun ReorderHandleCell(
    dragModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val touchSize = LocalMinimumInteractiveComponentSize.current.takeOrElse { 0.dp }
    Box(
        modifier =
            modifier
                .layout { measurable, _ ->
                    val width = HandleWidth.roundToPx()
                    val height = HandleHeight.roundToPx()
                    val target = touchSize.roundToPx()
                    val placeable = measurable.measure(Constraints.fixed(maxOf(width, target), maxOf(height, target)))
                    layout(width, height) {
                        placeable.place((width - placeable.width) / 2, (height - placeable.height) / 2)
                    }
                }.then(dragModifier),
        contentAlignment = Alignment.Center,
    ) {
        ReorderHandle()
    }
}

/**
 * The drag for a [ReorderHandle] inside a row that has its own click and long press. The drag starts
 * after a short hold on the handle, so a scroll or swipe that only passes over it still scrolls or
 * swipes; the handle claims the press, so the row's menu doesn't open.
 */
@Composable
fun ReorderableCollectionItemScope.reorderHandleDrag(enabled: Boolean = true): Modifier {
    if (!enabled) return Modifier
    val haptics = LocalHapticFeedback.current
    return Modifier
        .draggableHandle(
            onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            onDragStopped = { haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) },
            dragGestureDetector = ShortHoldDrag,
        ).pointerInput(Unit) {
            awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
        }
}

/** How long a finger rests on a handle before it picks the row up. */
private const val HANDLE_HOLD_MS = 100L

/** A drag that starts once the finger has rested [HANDLE_HOLD_MS] without moving past touch slop. */
private object ShortHoldDrag : DragGestureDetector {
    override suspend fun PointerInputScope.detect(
        onDragStart: (Offset) -> Unit,
        onDragEnd: () -> Unit,
        onDragCancel: () -> Unit,
        onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
    ) = awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        val leftEarly =
            withTimeoutOrNull(HANDLE_HOLD_MS) {
                var change = down
                while (change.pressed && (change.position - down.position).getDistance() <= slop) {
                    change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                }
                true
            }
        if (leftEarly == true) return@awaitEachGesture
        onDragStart(down.position)
        val finished =
            drag(down.id) { change ->
                onDrag(change, change.positionChange())
                change.consume()
            }
        if (finished) onDragEnd() else onDragCancel()
    }
}

/** Lifts the row being dragged above its neighbours. */
fun Modifier.reorderLift(isDragging: Boolean): Modifier =
    graphicsLayer {
        val scale = if (isDragging) DRAGGED_SCALE else 1f
        scaleX = scale
        scaleY = scale
        shadowElevation = if (isDragging) DRAGGED_SHADOW_PX else 0f
    }

private const val DRAGGED_SCALE = 1.03f
private const val DRAGGED_SHADOW_PX = 12f
