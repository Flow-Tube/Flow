package io.github.aedev.flow.ui.screens.playlists

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.shared.FlowReorderButtons
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation
import io.github.aedev.flow.ui.components.shared.ReorderHandle
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Drag handles over the full list of one kind; the new order is saved when a drag ends. */
@Composable
internal fun PlaylistsReorderList(
    playlists: List<PlaylistInfo>,
    isMusic: Boolean,
    onOrderChanged: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    // Held locally so a drop shows its result at once instead of snapping back until Room emits.
    var ordered by remember(playlists) { mutableStateOf(playlists) }
    val listState = rememberLazyListState()
    val reorderState =
        rememberReorderableLazyListState(listState) { from, to ->
            ordered = ordered.toMutableList().apply { add(to.index, removeAt(from.index)) }
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }

    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging && ordered.map { it.id } != playlists.map { it.id }) {
            onOrderChanged(ordered.map { it.id })
        }
    }

    fun move(
        from: Int,
        to: Int,
    ): Boolean {
        if (to !in ordered.indices) return false
        ordered = ordered.toMutableList().apply { add(to, removeAt(from)) }
        onOrderChanged(ordered.map { it.id })
        return true
    }

    val moveUpLabel = stringResource(R.string.move_up)
    val moveDownLabel = stringResource(R.string.move_down)
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = flowBottomContentPadding()),
    ) {
        itemsIndexed(ordered, key = { _, playlist -> playlist.id }) { index, playlist ->
            ReorderableItem(reorderState, key = playlist.id) { isDragging ->
                val itemScope = this
                Surface(
                    color = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    PlaylistCompactRow(
                        playlist = playlist,
                        isMusic = isMusic,
                        onClick = null,
                        modifier =
                            Modifier.semantics {
                                customActions =
                                    buildList {
                                        if (index > 0) add(CustomAccessibilityAction(moveUpLabel) { move(index, index - 1) })
                                        if (index < ordered.lastIndex) {
                                            add(CustomAccessibilityAction(moveDownLabel) { move(index, index + 1) })
                                        }
                                    }
                            },
                        trailing = {
                            if (LocalDpadNavigation.current) {
                                FlowReorderButtons(
                                    onMoveUp = if (index > 0) ({ move(index, index - 1) }) else null,
                                    onMoveDown = if (index < ordered.lastIndex) ({ move(index, index + 1) }) else null,
                                )
                            } else {
                                Box(
                                    modifier =
                                        with(itemScope) {
                                            Modifier
                                                .size(LocalMinimumInteractiveComponentSize.current)
                                                .draggableHandle(
                                                    onDragStarted = {
                                                        haptics.performHapticFeedback(
                                                            HapticFeedbackType.GestureThresholdActivate,
                                                        )
                                                    },
                                                    onDragStopped = { haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) },
                                                )
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    ReorderHandle()
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
