package io.github.aedev.flow.ui.screens.settings.appearance

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.DEFAULT_NAV_TAB_ORDER
import io.github.aedev.flow.ui.components.layout.navigation.FlowTab
import io.github.aedev.flow.ui.components.layout.navigation.icon
import io.github.aedev.flow.ui.components.shared.FlowReorderButtons
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation
import io.github.aedev.flow.ui.components.shared.ReorderHandle
import sh.calvin.reorderable.ReorderableColumn

private val SectionPadding = 16.dp
private val RowIconSpacing = 12.dp

/**
 * Tab order and default tab. Rows drag by their handle; TalkBack users get Move up / Move down as
 * custom actions, since a drag gesture is not reachable from a screen reader.
 */
@Composable
internal fun NavTabOrderList(
    order: List<Int>,
    enabledTabs: Set<FlowTab>,
    defaultTab: FlowTab,
    onOrderChanged: (List<Int>) -> Unit,
    onDefaultSelected: (FlowTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val persistedTabs = remember(order) { (order + DEFAULT_NAV_TAB_ORDER).distinct().mapNotNull(FlowTab::fromId) }
    // Held locally so a drop shows its result at once instead of snapping back until DataStore emits.
    var tabs by remember(persistedTabs) { mutableStateOf(persistedTabs) }

    fun move(
        from: Int,
        to: Int,
    ): Boolean {
        if (from == to || to !in tabs.indices) return false
        val updated = tabs.toMutableList().apply { add(to, removeAt(from)) }
        tabs = updated
        onOrderChanged(updated.map(FlowTab::id))
        return true
    }

    Column(modifier = modifier.padding(SectionPadding)) {
        val moveUpLabel = stringResource(R.string.move_up)
        val moveDownLabel = stringResource(R.string.move_down)
        ReorderableColumn(
            list = tabs,
            onSettle = { from, to -> move(from, to) },
            onMove = { haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) },
        ) { index, tab, isDragging ->
            key(tab) {
                ReorderableItem {
                    Surface(
                        color = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .semantics {
                                        customActions =
                                            buildList {
                                                if (index > 0) {
                                                    add(CustomAccessibilityAction(moveUpLabel) { move(index, index - 1) })
                                                }
                                                if (index < tabs.lastIndex) {
                                                    add(CustomAccessibilityAction(moveDownLabel) { move(index, index + 1) })
                                                }
                                            }
                                    },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = defaultTab == tab,
                                enabled = tab in enabledTabs,
                                onClick = { onDefaultSelected(tab) },
                            )
                            Icon(
                                imageVector = tab.icon(selected = false),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(RowIconSpacing))
                            Text(
                                text = stringResource(tab.labelRes),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (LocalDpadNavigation.current) {
                                FlowReorderButtons(
                                    onMoveUp = if (index > 0) ({ move(index, index - 1) }) else null,
                                    onMoveDown = if (index < tabs.lastIndex) ({ move(index, index + 1) }) else null,
                                )
                            } else {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(LocalMinimumInteractiveComponentSize.current)
                                            .draggableHandle(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    ReorderHandle()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
