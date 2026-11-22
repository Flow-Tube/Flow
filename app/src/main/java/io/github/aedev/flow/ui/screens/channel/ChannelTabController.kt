package io.github.aedev.flow.ui.screens.channel

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.github.aedev.flow.data.engagement.HiddenContent
import io.github.aedev.flow.data.paging.FeedTabPagingSource
import io.github.aedev.flow.data.paging.withoutHiddenVideos
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.channel.ChannelFilterGroup
import io.github.aedev.flow.innertube.pages.channel.ChannelTabContent
import io.github.aedev.flow.innertube.pages.channel.ChannelTabKind
import io.github.aedev.flow.innertube.pages.renderer.FeedItem
import io.github.aedev.flow.innertube.pages.renderer.FeedItemOwner
import io.github.aedev.flow.innertube.pages.renderer.FeedShelf
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ChannelTabState(
    val items: Flow<PagingData<FeedItem>>? = null,
    val sections: List<FeedShelf> = emptyList(),
    val filters: List<ChannelFilterGroup> = emptyList(),
    /** Index of the chosen option per filter group; -1 where the group is at the tab's default. */
    val selected: List<Int> = emptyList(),
    val isLoading: Boolean = false,
    val loaded: Boolean = false,
)

/**
 * Holds one lazily built pager per channel tab.
 *
 * A tab is fetched when it is first shown, never on channel open. The screen used to prefetch the
 * Videos and Live tabs to their page cap the moment a channel opened — up to fifty requests each,
 * throttled to 800 ms apart, on both tabs at once, whether or not the channel had a Live tab and
 * whether or not either was ever looked at.
 */
internal class ChannelTabController(
    private val scope: CoroutineScope,
    private val hidden: Flow<HiddenContent>,
) {
    private val _states = MutableStateFlow<Map<ChannelTabKind, ChannelTabState>>(emptyMap())
    val states: StateFlow<Map<ChannelTabKind, ChannelTabState>> = _states.asStateFlow()

    private var browseId: String = ""
    private var owner: FeedItemOwner = FeedItemOwner()

    fun reset(
        browseId: String,
        owner: FeedItemOwner,
    ) {
        this.browseId = browseId
        this.owner = owner
        _states.value = emptyMap()
    }

    fun ensureLoaded(
        kind: ChannelTabKind,
        params: String?,
    ) {
        if (browseId.isBlank() || params.isNullOrBlank()) return
        // Home is shelves, not a pageable grid: nothing collects a pager for it, so a pager would
        // never run its first load and the tab would spin for ever.
        if (kind == ChannelTabKind.Home) {
            loadSections(kind, params)
            return
        }
        if (_states.value[kind]?.items != null) return
        build(kind, params, continuation = null, selected = emptyList())
    }

    /**
     * Fills a shelves tab from a response already in hand. The channel's landing browse is its
     * Home tab, so the tab shown first costs no request of its own.
     */
    fun seedSections(
        kind: ChannelTabKind,
        content: ChannelTabContent,
    ) {
        if (kind != ChannelTabKind.Home) return
        _states.update { states ->
            states +
                (
                    kind to
                        (states[kind] ?: ChannelTabState()).copy(
                            sections = content.sections,
                            filters = content.filters,
                            selected = content.filters.map { group -> group.selectedIndex },
                            isLoading = false,
                            loaded = true,
                        )
                )
        }
    }

    private fun loadSections(
        kind: ChannelTabKind,
        params: String,
    ) {
        val current = _states.value[kind]
        if (current?.loaded == true || current?.isLoading == true) return
        _states.update { it + (kind to (it[kind] ?: ChannelTabState()).copy(isLoading = true)) }
        scope.launch(PerformanceDispatcher.networkIO) {
            val page = YouTube.channelTab(browseId, params, owner, kind).getOrNull()
            _states.update { states ->
                val current = states[kind] ?: ChannelTabState()
                states +
                    (
                        kind to
                            current.copy(
                                sections = page?.sections.orEmpty(),
                                filters = page?.filters.orEmpty(),
                                selected = page?.filters.orEmpty().map { group -> group.selectedIndex },
                                isLoading = false,
                                loaded = true,
                            )
                    )
            }
        }
    }

    /**
     * Applies one option of one filter group. Tapping the option already in effect clears it, which is
     * the only way back to a tab's default once a filter has been chosen.
     */
    fun selectFilter(
        kind: ChannelTabKind,
        tabParams: String?,
        groupIndex: Int,
        optionIndex: Int,
    ) {
        val state = _states.value[kind] ?: return
        val group = state.filters.getOrNull(groupIndex) ?: return
        val option = group.options.getOrNull(optionIndex) ?: return
        if (tabParams.isNullOrBlank()) return

        val clearing = state.selected.getOrElse(groupIndex) { -1 } == optionIndex
        val selected =
            List(state.filters.size) { index ->
                when {
                    index != groupIndex -> -1
                    clearing -> -1
                    else -> optionIndex
                }
            }

        if (clearing) {
            build(kind, tabParams, continuation = null, selected = selected)
            return
        }
        // A sort menu re-browses with its own params; a chip or dropdown entry is a continuation.
        build(kind, option.params ?: tabParams, continuation = option.continuation, selected = selected)
    }

    private fun build(
        kind: ChannelTabKind,
        params: String,
        continuation: String?,
        selected: List<Int>,
    ) {
        val pager =
            Pager(
                config = PagingConfig(pageSize = PAGE_SIZE, prefetchDistance = PREFETCH_DISTANCE, enablePlaceholders = false),
                pagingSourceFactory = {
                    FeedTabPagingSource(
                        browseId = browseId,
                        params = params,
                        kind = kind,
                        sortToken = continuation,
                        owner = owner,
                        onPageLoaded = { page -> publish(kind, page.filters, page.sections) },
                    )
                },
            ).flow
                .cachedIn(scope)
                .combine(hidden) { items, hidden -> items.withoutHiddenVideos(hidden) }

        _states.update { states ->
            val current = states[kind] ?: ChannelTabState()
            states + (kind to current.copy(items = pager, selected = selected))
        }
    }

    private fun publish(
        kind: ChannelTabKind,
        filters: List<ChannelFilterGroup>,
        sections: List<FeedShelf>,
    ) {
        _states.update { states ->
            val current = states[kind] ?: ChannelTabState()
            val nextFilters = filters.ifEmpty { current.filters }
            val nextSelected =
                current.selected.takeIf { it.size == nextFilters.size }
                    ?: nextFilters.map { it.selectedIndex }
            if (current.filters == nextFilters && current.sections == sections && current.selected == nextSelected) {
                states
            } else {
                states + (kind to current.copy(filters = nextFilters, sections = sections, selected = nextSelected))
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 20

        // Search's distance: the next page starts loading six items from the end, not a page early.
        const val PREFETCH_DISTANCE = 6
    }
}
