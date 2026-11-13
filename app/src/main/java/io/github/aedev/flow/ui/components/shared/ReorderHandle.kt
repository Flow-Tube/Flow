package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableCollectionItemScope

@Composable
fun ReorderHandle(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
) {
    Column(
        modifier = modifier.size(width = 20.dp, height = 28.dp),
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
 * The long-press drag for a [ReorderHandle] inside a row that has its own long press. The handle
 * claims the press, so the row's menu doesn't open as the drag starts.
 */
@Composable
fun ReorderableCollectionItemScope.reorderHandleDrag(enabled: Boolean = true): Modifier {
    if (!enabled) return Modifier
    val haptics = LocalHapticFeedback.current
    return Modifier
        .longPressDraggableHandle(
            onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            onDragStopped = { haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) },
        ).pointerInput(Unit) {
            awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
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
