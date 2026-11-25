package io.github.aedev.flow.ui.screens.channel

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.pages.channel.ChannelHeader
import io.github.aedev.flow.innertube.pages.channel.ChannelTabDescriptor
import io.github.aedev.flow.innertube.pages.channel.ChannelTabKind

data class ChannelUiState(
    val channelId: String? = null,
    val header: ChannelHeader? = null,
    /** Exactly the tabs the channel published, minus the ones the app hides. Empty until loaded. */
    val tabs: List<ChannelTabDescriptor> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingVideos: Boolean = false,
    val error: String? = null,
    /** YouTube says the channel does not exist, so [error] is final and retrying cannot help. */
    val isGone: Boolean = false,
    val videosError: String? = null,
    val isSubscribed: Boolean = false,
    val isNotificationsEnabled: Boolean = false,
    /** Null until the tab list lands: the channel decides which tab is first, not the app. */
    val selectedTab: ChannelTabKind? = null,
    val searchActive: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<Video> = emptyList(),
    val isSearching: Boolean = false,
    val searchErrorLog: String? = null,
    val searchContinuation: String? = null,
    val isLoadingMoreSearch: Boolean = false,
) {
    fun tabParams(kind: ChannelTabKind): String? = tabs.firstOrNull { it.kind == kind }?.params ?: kind.defaultParams

    fun hasTab(kind: ChannelTabKind): Boolean = tabs.any { it.kind == kind }
}

/**
 * Whether opening [url] needs a fetch. Coming back to a channel page re-runs its effects, and a
 * second load would refetch it and reset every tab's sort and loaded pages; only a different
 * channel, or a retry after a failed load, loads again.
 */
internal fun shouldLoadChannel(
    state: ChannelUiState,
    requestedUrl: String?,
    url: String,
): Boolean = url != requestedUrl || (state.header == null && !state.isLoading)
