package io.github.aedev.flow.ui.components.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.innertube.pages.explore.ExploreDestination
import io.github.aedev.flow.ui.components.shared.FlowDropdownFilterChip
import io.github.aedev.flow.ui.components.shared.FlowFilterChip
import io.github.aedev.flow.ui.screens.categories.CATEGORY_TABS
import io.github.aedev.flow.ui.screens.categories.CategorySubTab

/** The destination chips, on the same pill the search and channel filter bars use. */
@Composable
internal fun CategoryTabBar(
    selected: ExploreDestination,
    onSelect: (ExploreDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowState = rememberLazyListState()
    // A swipe can land on a chip scrolled out of the row, so the row follows it into view.
    LaunchedEffect(selected) {
        val index = CATEGORY_TABS.indexOfFirst { it.destination == selected }
        val info = rowState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        val fullyVisible =
            item != null && item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset
        if (index >= 0 && !fullyVisible) rowState.animateScrollToItem(index)
    }
    LazyRow(
        state = rowState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
        contentPadding = PaddingValues(horizontal = RowHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(CATEGORY_TABS, key = { it.destination.name }) { tab ->
            FlowFilterChip(
                label = stringResource(tab.labelRes),
                selected = selected == tab.destination,
                onClick = { onSelect(tab.destination) },
            )
        }
    }
}

/**
 * A destination's own categories — News ships seven, every other destination none.
 *
 * One dropdown rather than a second row of pills: the destinations above are navigation and the
 * categories are a filter within one of them, so stacking two identical chip rows read as one dense
 * block with no hierarchy.
 */
@Composable
internal fun CategorySubTabMenu(
    subTabs: List<CategorySubTab>,
    selected: String?,
    onSelect: (CategorySubTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (subTabs.size < 2) return
    var expanded by remember(subTabs) { mutableStateOf(false) }
    val current = subTabs.firstOrNull { it.title == selected } ?: subTabs.first()

    Box(modifier = modifier.padding(horizontal = RowHorizontalPadding)) {
        FlowDropdownFilterChip(
            label = current.title,
            selected = current.title != subTabs.first().title,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            subTabs.forEach { subTab ->
                DropdownMenuItem(
                    text = { Text(subTab.title) },
                    onClick = {
                        expanded = false
                        onSelect(subTab)
                    },
                )
            }
        }
    }
}

private val ChipSpacing = 8.dp
private val RowHorizontalPadding = 12.dp
