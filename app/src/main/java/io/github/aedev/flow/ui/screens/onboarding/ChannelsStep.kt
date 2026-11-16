package io.github.aedev.flow.ui.screens.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.ui.components.shared.FlowPopIn
import io.github.aedev.flow.ui.components.shared.FlowQuickSearchChips
import io.github.aedev.flow.ui.components.shared.FlowSearchField
import io.github.aedev.flow.ui.components.shared.FlowSectionHeader
import io.github.aedev.flow.ui.components.shared.FlowSegmentedGap
import io.github.aedev.flow.ui.components.shared.FlowSpinner
import io.github.aedev.flow.ui.components.shared.MediaChannelSubscribeRow
import io.github.aedev.flow.ui.components.shared.dismissKeyboardOnPress
import io.github.aedev.flow.ui.components.shared.flowSegmentShape
import io.github.aedev.flow.utils.formatSubscriberCount

private val SearchLoadingSize = 24.dp
private val ChipSpacing = 8.dp
private val PromptPadding = 32.dp
private const val MAX_QUICK_SEARCHES = 6
private const val MAX_POPPED_ROWS = 8

@Composable
internal fun ChannelsStep(
    state: OnboardingUiState,
    onQueryChange: (String) -> Unit,
    onSubscribeToggle: (Channel) -> Unit,
    onNotificationsChange: (String, Boolean) -> Unit,
    header: LazyListScope.() -> Unit,
    contentPadding: PaddingValues,
) {
    val focusManager = LocalFocusManager.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().dismissKeyboardOnPress { focusManager.clearFocus() },
        contentPadding = contentPadding,
    ) {
        header()
        item(key = "search") {
            FlowSearchField(
                query = state.query,
                onQueryChange = onQueryChange,
                placeholder = stringResource(R.string.onboarding_channels_search_placeholder),
                modifier = Modifier.fillMaxWidth(),
                onSearch = { focusManager.clearFocus() },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingContent = { if (state.searching) SearchLoading() },
            )
        }
        item(key = "quick") {
            FlowQuickSearchChips(
                searches = state.topics.take(MAX_QUICK_SEARCHES),
                query = state.query,
                onQueryChange = onQueryChange,
                modifier = Modifier.padding(top = ChipSpacing),
            )
        }
        channelResults(state, onSubscribeToggle, onNotificationsChange)
        if (state.subscribed.isNotEmpty()) {
            item(key = "added") {
                Text(
                    text = pluralStringResource(R.plurals.onboarding_channels_added_count, state.subscribed.size, state.subscribed.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = PromptPadding / 2),
                )
            }
        }
    }
}

private fun LazyListScope.channelResults(
    state: OnboardingUiState,
    onSubscribeToggle: (Channel) -> Unit,
    onNotificationsChange: (String, Boolean) -> Unit,
) {
    when {
        state.query.isBlank() -> {
            item(key = "prompt") { Prompt(stringResource(R.string.onboarding_channels_empty_prompt)) }
        }

        state.results.isEmpty() && !state.searching -> {
            item(key = "empty") { Prompt(stringResource(R.string.onboarding_channels_no_results, state.query)) }
        }

        state.results.isNotEmpty() -> {
            item(key = "results") { FlowSectionHeader(stringResource(R.string.onboarding_channels_results_header)) }
            itemsIndexed(state.results, key = { _, channel -> channel.id }) { index, channel ->
                val row: @Composable () -> Unit = {
                    MediaChannelSubscribeRow(
                        name = channel.name,
                        thumbnailUrl = channel.thumbnailUrl,
                        supportingText =
                            channel.subscriberCount.takeIf { it > 0 }?.let {
                                stringResource(R.string.onboarding_channels_subscribers, formatSubscriberCount(it))
                            },
                        subscribed = state.isSubscribed(channel.id),
                        notifying = channel.id in state.notifying,
                        shape = flowSegmentShape(index, state.results.size),
                        onToggle = { onSubscribeToggle(channel) },
                        onNotificationsChange = { onNotificationsChange(channel.id, it) },
                    )
                }
                val modifier = Modifier.animateItem().padding(bottom = FlowSegmentedGap)
                if (index < MAX_POPPED_ROWS) FlowPopIn(index, modifier) { row() } else Box(modifier) { row() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchLoading() {
    FlowSpinner(Modifier.size(SearchLoadingSize))
}

@Composable
private fun Prompt(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = PromptPadding),
    )
}
