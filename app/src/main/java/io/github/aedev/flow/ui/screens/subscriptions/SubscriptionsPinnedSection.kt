package io.github.aedev.flow.ui.screens.subscriptions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.ui.components.shared.ReorderHandle
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

private const val PINNED_KEY_PREFIX = "pinned:"

/**
 * The order of the pinned rows while they are dragged. The library needs each move applied before its callback
 * returns, so the order is held here and saved once a drag ends, as Manage groups does.
 */
internal class PinnedReorder(
    val reorderState: ReorderableLazyListState,
    val ordered: () -> List<Channel>,
    val move: (from: Int, to: Int) -> Boolean,
)

@Composable
internal fun rememberPinnedReorder(
    listState: LazyListState,
    pinned: List<Channel>,
    onOrderChanged: (List<String>) -> Unit,
): PinnedReorder {
    val haptics = LocalHapticFeedback.current
    val currentOnOrderChanged by rememberUpdatedState(onOrderChanged)
    var ordered by remember(pinned) { mutableStateOf(pinned) }
    val reorderState =
        rememberReorderableLazyListState(listState) { from, to ->
            val fromIndex = ordered.indexOfFirst { PINNED_KEY_PREFIX + it.id == from.key }
            val toIndex = ordered.indexOfFirst { PINNED_KEY_PREFIX + it.id == to.key }
            if (fromIndex >= 0 && toIndex >= 0) {
                ordered = ordered.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }
        }
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging && ordered.map { it.id } != pinned.map { it.id }) {
            currentOnOrderChanged(ordered.map { it.id })
        }
    }
    return remember(reorderState, pinned) {
        PinnedReorder(
            reorderState = reorderState,
            ordered = { ordered },
            move = { from, to ->
                if (to in ordered.indices) {
                    ordered = ordered.toMutableList().apply { add(to, removeAt(from)) }
                    currentOnOrderChanged(ordered.map { it.id })
                }
                to in ordered.indices
            },
        )
    }
}

/** The Pinned rows at the top of Manage subscriptions, each with a drag handle (#1776). */
internal fun LazyListScope.pinnedSubscriptionsSection(
    reorder: PinnedReorder,
    row: @Composable (channel: Channel, dragHandle: @Composable () -> Unit) -> Unit,
) {
    val pinned = reorder.ordered()
    if (pinned.isEmpty()) return
    item(key = "pinned_header") {
        Text(
            text = stringResource(R.string.subscriptions_pinned_section),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
    itemsIndexed(pinned, key = { _, channel -> PINNED_KEY_PREFIX + channel.id }) { index, channel ->
        ReorderableItem(state = reorder.reorderState, key = PINNED_KEY_PREFIX + channel.id) {
            val itemScope = this
            val haptics = LocalHapticFeedback.current
            val moveUp = stringResource(R.string.move_up)
            val moveDown = stringResource(R.string.move_down)
            row(channel) {
                Box(
                    modifier =
                        with(itemScope) {
                            Modifier
                                .size(LocalMinimumInteractiveComponentSize.current)
                                .semantics {
                                    customActions =
                                        buildList {
                                            if (index > 0) {
                                                add(CustomAccessibilityAction(moveUp) { reorder.move(index, index - 1) })
                                            }
                                            if (index < pinned.lastIndex) {
                                                add(CustomAccessibilityAction(moveDown) { reorder.move(index, index + 1) })
                                            }
                                        }
                                }.draggableHandle(
                                    onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                    onDragStopped = { haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) },
                                )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    ReorderHandle()
                }
            }
        }
    }
}
