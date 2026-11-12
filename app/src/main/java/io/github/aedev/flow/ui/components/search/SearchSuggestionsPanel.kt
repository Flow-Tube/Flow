package io.github.aedev.flow.ui.components.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.SearchHistoryItem
import io.github.aedev.flow.data.local.SearchType
import io.github.aedev.flow.innertube.pages.search.SearchSuggestion
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.shared.DeepFlowState
import io.github.aedev.flow.ui.components.shared.FlowSuggestionRow
import io.github.aedev.flow.ui.components.shared.FlowSwitchRow
import io.github.aedev.flow.ui.components.shared.deepFlowStatus

/**
 * What the search screen shows before a query is submitted: the user's own history first, then
 * what YouTube suggests, on the screen's own background rather than a raised surface. When the
 * user turned it on in Settings, a Deep Flow switch leads the list until they start typing.
 */
@Composable
fun SearchSuggestionsPanel(
    query: String,
    history: List<SearchHistoryItem>,
    suggestions: List<SearchSuggestion>,
    onSubmit: (String) -> Unit,
    onHistorySelect: (SearchHistoryItem) -> Unit,
    onFill: (String) -> Unit,
    onDeleteHistoryItem: (SearchHistoryItem) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
    deepFlow: DeepFlowState = DeepFlowState(),
    onDeepFlowChange: (Boolean) -> Unit = {},
) {
    var showAllHistory by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = flowBottomContentPadding()),
    ) {
        if (deepFlow.showOnSearch && query.isBlank()) {
            item(key = "deepflow") {
                FlowSwitchRow(
                    title = stringResource(R.string.deep_flow_mode_title),
                    supportingText = deepFlowStatus(deepFlow) ?: stringResource(R.string.deep_flow_mode_subtitle),
                    checked = deepFlow.active,
                    onCheckedChange = onDeepFlowChange,
                    leadingIcon = Icons.Outlined.VisibilityOff,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.padding(horizontal = DeepFlowHorizontalPadding, vertical = DeepFlowVerticalPadding),
                )
            }
        }

        searchHistoryItems(
            query = query,
            history = history,
            showAll = showAllHistory,
            onShowAll = { showAllHistory = true },
            onSelect = onHistorySelect,
            onDeleteHistoryItem = onDeleteHistoryItem,
            onClearHistory = onClearHistory,
        )

        items(suggestions, key = { "suggestion:${it.text}" }) { suggestion ->
            FlowSuggestionRow(
                text = suggestion.text,
                leadingIcon = Icons.Rounded.Search,
                onClick = { onSubmit(suggestion.text) },
                query = query,
                leadingImageUrl = suggestion.entity?.thumbnailUrl,
                supportingText = suggestion.entity?.description,
                trailingIcon = Icons.Rounded.NorthWest,
                trailingContentDescription = stringResource(R.string.resize_fill),
                onTrailingClick = { onFill(suggestion.text) },
            )
        }
    }
}

/**
 * The "Recent searches" header and rows, for any search surface's lazy list. Only the newest few
 * are drawn until [onShowAll] asks for the rest, which then scroll with the list.
 */
fun LazyListScope.searchHistoryItems(
    query: String,
    history: List<SearchHistoryItem>,
    showAll: Boolean,
    onShowAll: () -> Unit,
    onSelect: (SearchHistoryItem) -> Unit,
    onDeleteHistoryItem: (SearchHistoryItem) -> Unit,
    onClearHistory: () -> Unit,
) {
    if (history.isEmpty()) return
    val shown = history.shownHistory(showAll)
    item(key = "history:header") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = HeaderStartPadding, end = HeaderEndPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.recent_searches),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onClearHistory) {
                Text(stringResource(R.string.clear_search_history))
            }
        }
    }
    items(shown, key = { "history:${it.id}" }) { item ->
        FlowSuggestionRow(
            text = item.query,
            leadingIcon = if (item.type == SearchType.VOICE) Icons.Rounded.Mic else Icons.Rounded.History,
            onClick = { onSelect(item) },
            query = query,
            supportingText = item.filters?.let { searchFilterSummary(it) },
            trailingIcon = Icons.Rounded.Close,
            trailingContentDescription = stringResource(R.string.remove),
            onTrailingClick = { onDeleteHistoryItem(item) },
        )
    }
    if (shown.size < history.size) {
        item(key = "history:show-all") {
            TextButton(
                onClick = onShowAll,
                modifier = Modifier.padding(start = ShowAllStartPadding),
            ) {
                Text(stringResource(R.string.recent_searches_show_all))
            }
        }
    }
}

private val DeepFlowHorizontalPadding = 12.dp
private val DeepFlowVerticalPadding = 4.dp
private val HeaderStartPadding = 16.dp
private val HeaderEndPadding = 4.dp

// Puts the button label on the header title's 16 dp start line.
private val ShowAllStartPadding = 4.dp
