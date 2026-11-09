package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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

@Stable
class ReorderableLazyListState internal constructor(
    internal val listState: LazyListState,
    private val haptic: HapticFeedback,
    private val itemIndexOffset: Int,
    private val onMove: (Int, Int) -> Unit,
    private val onDragStopped: () -> Unit,
) {
    var draggingIndex by mutableIntStateOf(-1)
        private set

    private var rawDragOffset by mutableFloatStateOf(0f)

    private var layoutShiftOffset by mutableFloatStateOf(0f)

    private var scrollOffset by mutableFloatStateOf(0f)

    val visualTranslation: Float get() = rawDragOffset - layoutShiftOffset + scrollOffset

    val isDragging: Boolean get() = draggingIndex != -1

    fun startDrag(index: Int) {
        draggingIndex = index
        rawDragOffset = 0f
        layoutShiftOffset = 0f
        scrollOffset = 0f
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun onScrolled(delta: Float) {
        scrollOffset += delta
    }

    fun dragBy(deltaY: Float) {
        if (draggingIndex == -1) return
        rawDragOffset += deltaY
        performSwapIfNeeded()
    }

    fun performSwapIfNeeded() {
        if (draggingIndex == -1) return
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val currentItem = visibleItems.firstOrNull { it.index == draggingIndex + itemIndexOffset } ?: return

        val translation = visualTranslation
        val draggingCenter = currentItem.offset + currentItem.size / 2f + translation

        val neighborLocalIndex =
            when {
                translation > 0f -> draggingIndex + 1
                translation < 0f -> draggingIndex - 1
                else -> return
            }
        if (neighborLocalIndex < 0) return
        val neighborItem = visibleItems.firstOrNull { it.index == neighborLocalIndex + itemIndexOffset } ?: return
        val neighborMidpoint = neighborItem.offset + neighborItem.size / 2f

        val shouldSwap =
            when {
                translation > 0f -> draggingCenter > neighborMidpoint
                translation < 0f -> draggingCenter < neighborMidpoint
                else -> false
            }
        if (!shouldSwap) return

        layoutShiftOffset += neighborItem.offset - currentItem.offset

        onMove(draggingIndex, neighborLocalIndex)
        draggingIndex = neighborLocalIndex
        haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    fun computeEdgeScrollDelta(edgeThresholdPx: Float): Float {
        if (draggingIndex == -1) return 0f
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val currentItem = visibleItems.firstOrNull { it.index == draggingIndex + itemIndexOffset } ?: return 0f

        val itemTop = currentItem.offset + visualTranslation
        val itemBottom = itemTop + currentItem.size
        val viewportStart = listState.layoutInfo.viewportStartOffset.toFloat()
        val viewportEnd = listState.layoutInfo.viewportEndOffset.toFloat()

        return when {
            itemTop < viewportStart + edgeThresholdPx -> {
                val ratio = (viewportStart + edgeThresholdPx - itemTop) / edgeThresholdPx
                -(ratio * MAX_SCROLL_PX_PER_FRAME).coerceAtLeast(MIN_SCROLL_PX)
            }

            itemBottom > viewportEnd - edgeThresholdPx -> {
                val ratio = (itemBottom - (viewportEnd - edgeThresholdPx)) / edgeThresholdPx
                (ratio * MAX_SCROLL_PX_PER_FRAME).coerceAtLeast(MIN_SCROLL_PX)
            }

            else -> {
                0f
            }
        }
    }

    fun endDrag() {
        if (draggingIndex != -1) onDragStopped()
        draggingIndex = -1
        rawDragOffset = 0f
        layoutShiftOffset = 0f
        scrollOffset = 0f
    }

    fun itemModifier(index: Int): Modifier {
        val isDragging = draggingIndex == index
        return Modifier
            .graphicsLayer {
                translationY = if (isDragging) visualTranslation else 0f
                scaleX = if (isDragging) 1.03f else 1f
                scaleY = if (isDragging) 1.03f else 1f
                shadowElevation = if (isDragging) 12f else 0f
            }.zIndex(if (isDragging) 1f else 0f)
    }

    @Composable
    fun handleModifier(index: Int): Modifier {
        val latestIndex by rememberUpdatedState(index)
        return Modifier.pointerInput(this@ReorderableLazyListState) {
            awaitEachGesture {
                // A gesture that begins on the drag handle belongs exclusively to reordering.
                // Consuming the initial down claims the pointer, so the parent row's
                // combinedClickable never starts its own click / long-press (quick-actions)
                // detection here. Long-press on the rest of the row is therefore free to open
                // quick actions without racing the drag.
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                startDrag(latestIndex)
                drag(longPress.id) { change ->
                    dragBy(change.positionChange().y)
                    change.consume()
                }
                endDrag()
            }
        }
    }

    companion object {
        private const val MAX_SCROLL_PX_PER_FRAME = 22f
        private const val MIN_SCROLL_PX = 2f
    }
}

@Composable
fun rememberReorderableLazyListState(
    listState: LazyListState,
    itemIndexOffset: Int = 0,
    onMove: (Int, Int) -> Unit,
    onDragStopped: () -> Unit,
): ReorderableLazyListState {
    val haptic = LocalHapticFeedback.current
    val edgeThresholdPx = with(LocalDensity.current) { 80.dp.toPx() }
    val onMoveState = rememberUpdatedState(onMove)
    val onDragStoppedState = rememberUpdatedState(onDragStopped)

    val state =
        remember(listState, itemIndexOffset) {
            ReorderableLazyListState(
                listState = listState,
                haptic = haptic,
                itemIndexOffset = itemIndexOffset,
                onMove = { from, to -> onMoveState.value(from, to) },
                onDragStopped = { onDragStoppedState.value() },
            )
        }

    LaunchedEffect(state) {
        while (isActive) {
            if (state.isDragging) {
                val delta = state.computeEdgeScrollDelta(edgeThresholdPx)
                if (delta != 0f) {
                    val scrolled = listState.scrollBy(delta)
                    state.onScrolled(scrolled)
                    state.performSwapIfNeeded()
                }
            }
            delay(16L)
        }
    }

    return state
}
