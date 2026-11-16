@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.aedev.flow.ui.screens.settings.appearance.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.MAX_MINI_BAR_BUTTONS
import io.github.aedev.flow.data.local.MiniBarButton
import io.github.aedev.flow.ui.components.musicplayer.mini.icon
import io.github.aedev.flow.ui.components.musicplayer.mini.labelRes
import io.github.aedev.flow.ui.components.shared.FlowSheetHeader
import io.github.aedev.flow.ui.components.shared.FlowSwitch
import io.github.aedev.flow.ui.components.shared.MediaMiniBarDefaults
import io.github.aedev.flow.ui.components.shared.rememberFlowSheetState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val CoverSize = 44.dp
private val PreviewIconSize = 24.dp

/**
 * The mini player's buttons: a bar at the top shows them as they will look, the ones in use are
 * dragged into order, and the rest are switched on below, up to [MAX_MINI_BAR_BUTTONS].
 */
@Composable
internal fun MiniButtonsSheet(
    chosen: List<MiniBarButton>,
    onChange: (List<MiniBarButton>) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // Filled at once: rows added after the first frame would sit above the one the list anchors on.
    val order = remember { mutableStateListOf<MiniBarButton>().apply { addAll(chosen) } }
    LaunchedEffect(chosen) {
        if (order.toList() != chosen) {
            order.clear()
            order.addAll(chosen)
        }
    }
    val listState = rememberLazyListState()
    val reorderState =
        rememberReorderableLazyListState(listState) { from, to ->
            val fromIndex = order.indexOfFirst { it.chosenKey() == from.key }
            val toIndex = order.indexOfFirst { it.chosenKey() == to.key }
            if (fromIndex >= 0 && toIndex >= 0) {
                order.add(toIndex, order.removeAt(fromIndex))
                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }
        }
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging && order.toList() != chosen) onChange(order.toList())
    }
    val unused = MiniBarButton.entries.filter { it !in order }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberFlowSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        FlowSheetHeader(
            title = stringResource(R.string.player_appearance_mini_buttons_title),
            subtitle = stringResource(R.string.player_appearance_mini_buttons_subtitle),
            onClose = onDismiss,
            showDragHandle = false,
        )
        ButtonsPreview(order, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyColumn(
            state = listState,
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(order.toList(), key = { it.chosenKey() }) { button ->
                ReorderableItem(reorderState, key = button.chosenKey()) {
                    ButtonRow(
                        button = button,
                        checked = true,
                        enabled = true,
                        onCheckedChange = { onChange(order.toList() - button) },
                        handle = Modifier.draggableHandle(),
                    )
                }
            }
            item(key = "spacer") { Spacer(Modifier.height(12.dp)) }
            items(unused, key = { "off:${it.name}" }) { button ->
                ButtonRow(
                    button = button,
                    checked = false,
                    enabled = order.size < MAX_MINI_BAR_BUTTONS,
                    onCheckedChange = { onChange(order.toList() + button) },
                    handle = null,
                )
            }
        }
    }
}

/**
 * A chosen row keys apart from the same button unchosen, so a row switched across doesn't carry the
 * list's scroll anchor with it.
 */
private fun MiniBarButton.chosenKey(): String = "on:$name"

@Composable
private fun ButtonRow(
    button: MiniBarButton,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    handle: Modifier?,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (handle != null) {
            Icon(
                imageVector = Icons.Rounded.DragIndicator,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = handle,
            )
        }
        Icon(button.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = stringResource(button.labelRes()),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled || checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        FlowSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** The bar as it will look: a cover, then the buttons in their order, or a note when there are none. */
@Composable
private fun ButtonsPreview(
    buttons: List<MiniBarButton>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(MediaMiniBarDefaults.Height)
                .clip(RoundedCornerShape(MediaMiniBarDefaults.CornerRadius))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(CoverSize).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer))
        Box(Modifier.weight(1f)) {
            if (buttons.isEmpty()) {
                Text(
                    text = stringResource(R.string.player_appearance_mini_buttons_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        buttons.forEach { button ->
            val filled = button == MiniBarButton.PLAY_PAUSE
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = button.icon(),
                    contentDescription = stringResource(button.labelRes()),
                    tint = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(PreviewIconSize),
                )
            }
        }
    }
}
