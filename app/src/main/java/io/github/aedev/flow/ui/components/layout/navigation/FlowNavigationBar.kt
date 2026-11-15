package io.github.aedev.flow.ui.components.layout.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarArrangement
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.NavBarLabels
import io.github.aedev.flow.ui.utils.LocalWindowSizeClass
import io.github.aedev.flow.ui.utils.isMediumWidth

private const val MAX_BAR_TABS = 5

/**
 * The bottom navigation bar. Material allows at most five destinations in a bar, so a sixth and
 * seventh enabled tab move behind a "More" item.
 */
@Composable
internal fun FlowNavigationBar(
    tabs: List<FlowTab>,
    selectedTab: FlowTab?,
    onTabSelected: (FlowTab) -> Unit,
    modifier: Modifier = Modifier,
    labels: NavBarLabels = NavBarLabels.ALWAYS,
) {
    val horizontalItems = LocalWindowSizeClass.current.isMediumWidth
    val iconPosition = if (horizontalItems) NavigationItemIconPosition.Start else NavigationItemIconPosition.Top
    val (barTabs, overflowTabs) = splitTabsForOverflow(tabs, MAX_BAR_TABS)

    ShortNavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        arrangement =
            if (horizontalItems) ShortNavigationBarArrangement.Centered else ShortNavigationBarArrangement.EqualWeight,
    ) {
        barTabs.forEach { tab ->
            val selected = tab == selectedTab
            val name = stringResource(tab.labelRes)
            val showsLabel = labels.shows(selected)
            ShortNavigationBarItem(
                selected = selected,
                onClick = { onTabSelected(tab) },
                icon = { Icon(imageVector = tab.icon(selected), contentDescription = if (showsLabel) null else name) },
                label = if (showsLabel) ({ Text(text = name) }) else null,
                iconPosition = iconPosition,
            )
        }
        if (overflowTabs.isNotEmpty()) {
            OverflowItem(
                tabs = overflowTabs,
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
                iconPosition = iconPosition,
                labels = labels,
            )
        }
    }
}

@Composable
private fun OverflowItem(
    tabs: List<FlowTab>,
    selectedTab: FlowTab?,
    onTabSelected: (FlowTab) -> Unit,
    iconPosition: NavigationItemIconPosition,
    labels: NavBarLabels,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = selectedTab in tabs
    val name = stringResource(R.string.nav_more)
    val showsLabel = labels.shows(selected)
    // One Box per bar slot: the bar measures every direct child as an item, and the menu's
    // popup anchor would otherwise count as one.
    Box(propagateMinConstraints = true) {
        ShortNavigationBarItem(
            selected = selected,
            onClick = { expanded = true },
            icon = { Icon(imageVector = Icons.Filled.MoreHoriz, contentDescription = if (showsLabel) null else name) },
            label = if (showsLabel) ({ Text(text = name) }) else null,
            iconPosition = iconPosition,
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

internal fun NavBarLabels.shows(selected: Boolean): Boolean =
    when (this) {
        NavBarLabels.ALWAYS -> true
        NavBarLabels.SELECTED -> selected
        NavBarLabels.NEVER -> false
    }
