package io.github.aedev.flow.ui.components.layout.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import io.github.aedev.flow.data.local.NavBarLabels
import io.github.aedev.flow.ui.components.shared.MediaMiniBarDefaults

/** How the floating bar lays out its tabs, from the most to the least room it needs. */
internal enum class FloatingNavLayout {
    /** Only the open tab is named, beside its icon. */
    EXPANDING,

    /** Every tab at its own width, named under its icon as the labels setting says. */
    PILL,

    /** The pill stretched to the mini player's width, tabs sharing it equally. */
    WIDE,
}

/** Four tabs is where the open tab's name beside its icon stops fitting a phone. */
internal const val MAX_EXPANDING_SLOTS = 4

/**
 * Picks the first layout that fits [maxWidth]: expanding while there are at most
 * [MAX_EXPANDING_SLOTS] slots and names are shown, then the pill, then the wide bar.
 */
internal fun chooseFloatingNavLayout(
    slotCount: Int,
    labels: NavBarLabels,
    maxWidth: Int,
    expandingWidth: () -> Int,
    pillWidth: () -> Int,
): FloatingNavLayout =
    when {
        labels != NavBarLabels.NEVER && slotCount <= MAX_EXPANDING_SLOTS && expandingWidth() <= maxWidth -> FloatingNavLayout.EXPANDING
        pillWidth() <= maxWidth -> FloatingNavLayout.PILL
        else -> FloatingNavLayout.WIDE
    }

/**
 * The bottom bar in its floating style: Material's floating toolbar holding the same items as the
 * standard bar, above the gesture area. Its measured height includes that gap, so pages and the
 * mini player clear it the way they clear the standard bar.
 */
@Composable
internal fun FlowFloatingNavigationBar(
    tabs: List<FlowTab>,
    selectedTab: FlowTab?,
    onTabSelected: (FlowTab) -> Unit,
    labels: NavBarLabels,
    modifier: Modifier = Modifier,
) {
    val (barTabs, overflowTabs) = splitTabsForOverflow(tabs, MAX_BAR_TABS)
    // What each slot shows when it is the open one: its own tab, or for "More" the first tab behind it.
    val slotTabs = barTabs + listOfNotNull(overflowTabs.firstOrNull())

    SubcomposeLayout(
        modifier =
            modifier
                .windowInsetsPadding(ShortNavigationBarDefaults.windowInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = MediaMiniBarDefaults.CompactMargin)
                .padding(bottom = FloatingToolbarDefaults.ScreenOffset),
    ) { constraints ->
        val unbounded = Constraints(maxHeight = constraints.maxHeight)

        // Measured with every slot open in turn, so the bar keeps its layout whichever tab is open.
        fun widest(layout: FloatingNavLayout): Int =
            slotTabs.withIndex().maxOf { (index, open) ->
                subcompose("$layout$index") {
                    FloatingNavTabs(barTabs, overflowTabs, open, onTabSelected, labels, layout)
                }.maxOf { it.measure(unbounded).width }
            }

        val layout =
            chooseFloatingNavLayout(
                slotCount = slotTabs.size,
                labels = labels,
                maxWidth = constraints.maxWidth,
                expandingWidth = { widest(FloatingNavLayout.EXPANDING) },
                pillWidth = { widest(FloatingNavLayout.PILL) },
            )
        val bar =
            subcompose(layout) {
                FloatingNavTabs(barTabs, overflowTabs, selectedTab, onTabSelected, labels, layout)
            }.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val height = bar.maxOf { it.height }
        layout(constraints.maxWidth, height) {
            bar.forEach { it.placeRelative((constraints.maxWidth - it.width) / 2, 0) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FloatingNavTabs(
    barTabs: List<FlowTab>,
    overflowTabs: List<FlowTab>,
    selectedTab: FlowTab?,
    onTabSelected: (FlowTab) -> Unit,
    labels: NavBarLabels,
    layout: FloatingNavLayout,
) {
    val wide = layout == FloatingNavLayout.WIDE
    val sidePadding = FloatingToolbarDefaults.ContentPadding.calculateLeftPadding(LayoutDirection.Ltr)
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
        // Items bring their own vertical padding; the toolbar's would make a labelled bar 80 dp tall.
        contentPadding = PaddingValues(horizontal = sidePadding),
        modifier = if (wide) Modifier.fillMaxWidth() else Modifier,
    ) {
        val itemModifier = if (wide) Modifier.weight(1f) else Modifier

        fun showsLabel(selected: Boolean) = if (layout == FloatingNavLayout.EXPANDING) selected else labels.shows(selected)

        fun position(selected: Boolean) =
            if (layout == FloatingNavLayout.EXPANDING && selected) NavigationItemIconPosition.Start else NavigationItemIconPosition.Top
        barTabs.forEach { tab ->
            val selected = tab == selectedTab
            Box(itemModifier, propagateMinConstraints = true) {
                FlowTabBarItem(
                    tab = tab,
                    selected = selected,
                    onClick = { onTabSelected(tab) },
                    showsLabel = showsLabel(selected),
                    iconPosition = position(selected),
                )
            }
        }
        if (overflowTabs.isNotEmpty()) {
            val selected = selectedTab in overflowTabs
            FlowOverflowBarItem(
                tabs = overflowTabs,
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
                showsLabel = showsLabel(selected),
                iconPosition = position(selected),
                modifier = itemModifier,
            )
        }
    }
}
