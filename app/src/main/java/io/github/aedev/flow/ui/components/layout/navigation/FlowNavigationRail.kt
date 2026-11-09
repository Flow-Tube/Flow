package io.github.aedev.flow.ui.components.layout.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailState
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.utils.LocalWindowSizeClass
import io.github.aedev.flow.ui.utils.isLargeWidth
import kotlinx.coroutines.launch

// Centres the 48 dp header button over the 96 dp collapsed rail's item column.
private val HeaderButtonStartPadding = 24.dp

// The minimum height Material gives a collapsed rail item, used until the first item has been
// measured; the measured height, which grows with the font scale, replaces it.
private val RailItemMinHeight = 64.dp

// The space Material leaves under the rail's header before the items.
private val RailHeaderPadding = 40.dp

/**
 * The start-edge navigation rail. Collapsed by default; on large windows its header offers an
 * expanded form with labels beside the icons. Items centre on the full height; the header stays on
 * top. When the window is too short for every tab, as on a phone in landscape, the last slot
 * becomes a "More" item holding the rest, as on the bottom bar.
 */
@Composable
internal fun FlowNavigationRail(
    tabs: List<FlowTab>,
    selectedTab: FlowTab?,
    onTabSelected: (FlowTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val expandable = LocalWindowSizeClass.current.isLargeWidth
    val state = rememberWideNavigationRailState()
    LaunchedEffect(expandable) {
        if (!expandable) state.snapTo(WideNavigationRailValue.Collapsed)
    }
    val railExpanded = state.targetValue == WideNavigationRailValue.Expanded
    val windowInsets = WideNavigationRailDefaults.windowInsets
    val contentPadding = WideNavigationRailDefaults.ContentPadding
    val density = LocalDensity.current
    var itemHeightPx by remember { mutableIntStateOf(0) }
    var headerHeightPx by remember { mutableIntStateOf(0) }

    BoxWithConstraints(modifier = modifier.fillMaxHeight()) {
        val capacity =
            if (constraints.hasBoundedHeight) {
                with(density) {
                    val reservedPx =
                        windowInsets.getTop(this) +
                            windowInsets.getBottom(this) +
                            (contentPadding.calculateTopPadding() + contentPadding.calculateBottomPadding()).roundToPx() +
                            (if (expandable) headerHeightPx + RailHeaderPadding.roundToPx() else 0)
                    railItemCapacity(
                        availableHeightPx = constraints.maxHeight - reservedPx,
                        itemHeightPx = itemHeightPx.takeIf { it > 0 } ?: RailItemMinHeight.roundToPx(),
                    )
                }
            } else {
                tabs.size
            }
        val (railTabs, overflowTabs) = splitTabsForOverflow(tabs, capacity)
        val measureItem = Modifier.onSizeChanged { itemHeightPx = it.height }

        WideNavigationRail(
            modifier = Modifier.fillMaxHeight(),
            state = state,
            header =
                if (expandable) {
                    { RailToggle(state, Modifier.onSizeChanged { headerHeightPx = it.height }) }
                } else {
                    null
                },
            windowInsets = windowInsets,
            arrangement = Arrangement.Center,
            contentPadding = contentPadding,
        ) {
            railTabs.forEach { tab ->
                val selected = tab == selectedTab
                WideNavigationRailItem(
                    selected = selected,
                    onClick = { onTabSelected(tab) },
                    icon = { Icon(imageVector = tab.icon(selected), contentDescription = null) },
                    label = { Text(text = stringResource(tab.labelRes)) },
                    railExpanded = railExpanded,
                    modifier = measureItem,
                )
            }
            if (overflowTabs.isNotEmpty()) {
                RailOverflowItem(
                    tabs = overflowTabs,
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected,
                    railExpanded = railExpanded,
                    modifier = measureItem,
                )
            }
        }
    }
}

/** How many items of [itemHeightPx] fit one above the other in [availableHeightPx]. */
internal fun railItemCapacity(
    availableHeightPx: Int,
    itemHeightPx: Int,
): Int = if (itemHeightPx <= 0) 0 else (availableHeightPx / itemHeightPx).coerceAtLeast(0)

@Composable
private fun RailOverflowItem(
    tabs: List<FlowTab>,
    selectedTab: FlowTab?,
    onTabSelected: (FlowTab) -> Unit,
    railExpanded: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    // One Box per rail slot: the rail measures every direct child as an item, and the menu's
    // popup anchor would otherwise count as one.
    Box(modifier = modifier, propagateMinConstraints = true) {
        WideNavigationRailItem(
            selected = selectedTab in tabs,
            onClick = { expanded = true },
            icon = { Icon(imageVector = Icons.Filled.MoreHoriz, contentDescription = null) },
            label = { Text(text = stringResource(R.string.nav_more)) },
            railExpanded = railExpanded,
        )
        FlowTabOverflowMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            tabs = tabs,
            selectedTab = selectedTab,
            onTabSelected = onTabSelected,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RailToggle(
    state: WideNavigationRailState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val expanded = state.targetValue == WideNavigationRailValue.Expanded
    val description = stringResource(if (expanded) R.string.nav_rail_collapse else R.string.nav_rail_expand)
    val stateText = stringResource(if (expanded) R.string.nav_rail_state_expanded else R.string.nav_rail_state_collapsed)

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text = description) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        IconButton(
            onClick = { scope.launch { state.toggle() } },
            modifier =
                Modifier
                    .padding(start = HeaderButtonStartPadding)
                    .semantics { stateDescription = stateText },
        ) {
            Icon(
                imageVector = if (expanded) Icons.AutoMirrored.Filled.MenuOpen else Icons.Filled.Menu,
                contentDescription = description,
            )
        }
    }
}
