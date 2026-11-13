package io.github.aedev.flow.ui.screens.settings.taste

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingsListScope
import io.github.aedev.flow.ui.components.settings.SettingsPage
import io.github.aedev.flow.ui.components.shared.ChannelOrganizeActions
import io.github.aedev.flow.ui.components.shared.ChannelOrganizeTarget
import io.github.aedev.flow.ui.components.shared.FlowEmptyState
import io.github.aedev.flow.ui.components.shared.FlowLoadingIndicator
import io.github.aedev.flow.ui.components.shared.FlowQuickSearchChips
import io.github.aedev.flow.ui.components.shared.FlowSearchField
import io.github.aedev.flow.ui.components.shared.MediaChannelSubscribeRow
import io.github.aedev.flow.ui.components.shared.rememberChannelOrganizeActions
import io.github.aedev.flow.utils.formatSubscriberCount

private val LoadingHeight = 160.dp

/** Search for channels and subscribe in one tap, as setup offers, plus channels already watched often. */
@Composable
internal fun DiscoverChannelsScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: DiscoverChannelsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()

    SettingsPage(title = stringResource(R.string.discover_channels_title), onBack = onBack, highlight = highlight) {
        item("discover.search") {
            FlowSearchField(
                query = query,
                onQueryChange = viewModel::onQueryChange,
                placeholder = stringResource(R.string.onboarding_channels_search_placeholder),
                onClear = { viewModel.onQueryChange("") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                releaseFocusWithKeyboard = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.quickSearches.isNotEmpty()) {
            item("discover.quick") {
                FlowQuickSearchChips(searches = state.quickSearches, query = query, onQueryChange = viewModel::onQueryChange)
            }
        }
        if (query.isBlank()) suggestions(state, viewModel) else results(state, query, viewModel)
    }
}

private fun SettingsListScope.suggestions(
    state: DiscoverChannelsState,
    viewModel: DiscoverChannelsViewModel,
) {
    neighbours(state, viewModel)
    val suggestions = state.suggestions ?: return
    group(key = "discover.suggested", header = R.string.taste_remembered_header, footer = R.string.discover_channels_suggested_footer) {
        suggestions.forEach { channel ->
            row("discover.suggested.${channel.channelId}") { shape ->
                val avatar = state.suggestionAvatars[channel.channelId]
                MediaChannelSubscribeRow(
                    name = channel.name.ifBlank { channel.channelId },
                    thumbnailUrl = avatar,
                    supportingText = null,
                    subscribed = channel.channelId in state.subscribedIds,
                    notifying = channel.channelId in state.notifyingIds,
                    shape = shape,
                    onToggle = { viewModel.toggleSubscription(channel.channelId, channel.name, avatar.orEmpty()) },
                    onNotificationsChange = { viewModel.setNotifications(channel.channelId, it) },
                    organize = discoverOrganize(channel.channelId, channel.name, avatar, channel.channelId in state.subscribedIds),
                )
            }
        }
    }
}

private fun SettingsListScope.neighbours(
    state: DiscoverChannelsState,
    viewModel: DiscoverChannelsViewModel,
) {
    if (state.neighbours.isEmpty()) return
    group(
        key = "discover.neighbours",
        header = R.string.discover_channels_like_yours,
        footer = R.string.discover_channels_like_yours_footer,
    ) {
        state.neighbours.forEach { channel ->
            row("discover.neighbours.${channel.channelId}") { shape ->
                val avatar = state.suggestionAvatars[channel.channelId]
                val pointers = channel.pointerNames
                MediaChannelSubscribeRow(
                    name = channel.name,
                    thumbnailUrl = avatar,
                    supportingText =
                        when (pointers.size) {
                            0 -> {
                                null
                            }

                            1 -> {
                                stringResource(R.string.home_origin_neighbour, pointers[0])
                            }

                            else -> {
                                stringResource(
                                    R.string.home_origin_neighbour,
                                    stringResource(R.string.home_origin_neighbour_pair, pointers[0], pointers[1]),
                                )
                            }
                        },
                    subscribed = channel.channelId in state.subscribedIds,
                    notifying = channel.channelId in state.notifyingIds,
                    shape = shape,
                    onToggle = { viewModel.toggleSubscription(channel.channelId, channel.name, avatar.orEmpty()) },
                    onNotificationsChange = { viewModel.setNotifications(channel.channelId, it) },
                    organize = discoverOrganize(channel.channelId, channel.name, avatar, channel.channelId in state.subscribedIds),
                )
            }
        }
    }
}

private fun SettingsListScope.results(
    state: DiscoverChannelsState,
    query: String,
    viewModel: DiscoverChannelsViewModel,
) {
    val results = state.results
    when {
        results == null -> {
            item("discover.loading") { FlowLoadingIndicator(Modifier.fillMaxWidth().height(LoadingHeight)) }
        }

        results.isEmpty() -> {
            item("discover.none") {
                FlowEmptyState(
                    title = stringResource(R.string.onboarding_channels_no_results, query),
                    icon = Icons.Outlined.PersonSearch,
                )
            }
        }

        else -> {
            group(key = "discover.results", header = R.string.onboarding_channels_results_header) {
                results.forEach { channel ->
                    row("discover.results.${channel.id}") { shape ->
                        MediaChannelSubscribeRow(
                            name = channel.name,
                            thumbnailUrl = channel.thumbnailUrl,
                            supportingText =
                                channel.subscriberCount.takeIf { it > 0 }?.let {
                                    stringResource(R.string.onboarding_channels_subscribers, formatSubscriberCount(it))
                                },
                            subscribed = channel.id in state.subscribedIds,
                            notifying = channel.id in state.notifyingIds,
                            shape = shape,
                            onToggle = { viewModel.toggleSubscription(channel.id, channel.name, channel.thumbnailUrl) },
                            onNotificationsChange = { viewModel.setNotifications(channel.id, it) },
                            organize = discoverOrganize(channel.id, channel.name, channel.thumbnailUrl, channel.id in state.subscribedIds),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun discoverOrganize(
    channelId: String,
    name: String,
    avatarUrl: String?,
    isSubscribed: Boolean,
): ChannelOrganizeActions =
    rememberChannelOrganizeActions(
        target = remember(channelId, name, avatarUrl) { ChannelOrganizeTarget(channelId, name, avatarUrl.orEmpty()) },
        isSubscribed = isSubscribed,
    )
