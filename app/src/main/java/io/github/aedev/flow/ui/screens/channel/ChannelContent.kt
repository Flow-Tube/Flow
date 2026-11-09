package io.github.aedev.flow.ui.screens.channel

import androidx.compose.animation.core.animate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.HomeFeedColumns
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.pages.channel.ChannelTabKind
import io.github.aedev.flow.innertube.pages.renderer.CommunityPost
import io.github.aedev.flow.innertube.pages.renderer.FeedShelf
import io.github.aedev.flow.ui.components.channel.ChannelAboutSection
import io.github.aedev.flow.ui.components.channel.ChannelCommunityPosts
import io.github.aedev.flow.ui.components.channel.ChannelFilterBar
import io.github.aedev.flow.ui.components.channel.ChannelHeaderSection
import io.github.aedev.flow.ui.components.channel.ChannelReadingPane
import io.github.aedev.flow.ui.components.channel.ChannelRow
import io.github.aedev.flow.ui.components.channel.ChannelTabItems
import io.github.aedev.flow.ui.components.channel.ChannelTabRow
import io.github.aedev.flow.ui.components.channel.PostsPaneMaxWidth
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.shared.FeedShelfActions
import io.github.aedev.flow.ui.components.shared.FeedShelfSections
import io.github.aedev.flow.ui.components.shared.FeedShelfSlots
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation
import io.github.aedev.flow.ui.components.shared.MediaPostCard
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelContent(
    uiState: ChannelUiState,
    onManageGroups: (() -> Unit)?,
    communityUiState: ChannelCommunityUiState,
    tabStates: Map<ChannelTabKind, ChannelTabState>,
    showShortsTab: Boolean,
    onFilterSelected: (ChannelTabKind, Int, Int) -> Unit,
    subscribedChannelIds: Set<String>,
    notifyingChannelIds: Set<String>,
    onChannelNotificationChange: (String, Boolean) -> Unit,
    channelNote: String?,
    onEditNote: (() -> Unit)?,
    onSubscribeChannel: (io.github.aedev.flow.data.model.Channel, Boolean) -> Unit,
    onVideoClick: (Video) -> Unit,
    onChannelClick: (String) -> Unit,
    onShortClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onSubscribeClick: () -> Unit,
    onUnsubscribeClick: () -> Unit,
    onNotificationChange: (Boolean) -> Unit,
    onTabSelected: (ChannelTabKind) -> Unit,
    onSearchToggle: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onCommunityPostComments: (CommunityPost) -> Unit,
    onCommunityPostShare: (CommunityPost) -> Unit,
    onLoadMoreCommunityPosts: () -> Unit,
    onRetryCommunityPosts: () -> Unit,
    initialScrollIndex: Int = 0,
    initialScrollOffset: Int = 0,
    onScrollChanged: (index: Int, offset: Int) -> Unit = { _, _ -> },
    onCollapsedTitleVisibilityChange: (Boolean) -> Unit = {},
) {
    val header = uiState.header ?: return

    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences =
        remember {
            io.github.aedev.flow.data.local
                .PlayerPreferences(context)
        }
    val isGridView by preferences.channelIsGridView.collectAsState(initial = false)
    val columnPreference by preferences.homeFeedColumns.collectAsState(initial = HomeFeedColumns.AUTO)
    val coroutineScope = rememberCoroutineScope()

    val aboutTitle = stringResource(R.string.tab_about)
    val visibleTabs =
        remember(uiState.tabs, uiState.header, showShortsTab, aboutTitle) {
            channelScreenTabs(uiState.tabs, uiState.header, showShortsTab, aboutTitle)
        }
    if (visibleTabs.isEmpty()) return

    // Keyed on the resolved list: the tab count changes once, when the header lands, and a pager
    // holding a stale count indexes out of bounds on the first swipe.
    val pagerState =
        key(visibleTabs) {
            rememberPagerState(
                initialPage = visibleTabs.indexOfFirst { it.kind == uiState.selectedTab }.coerceAtLeast(0),
                pageCount = { visibleTabs.size },
            )
        }

    val settledTab = visibleTabs.getOrElse(pagerState.settledPage) { visibleTabs.first() }

    // Persist only fully settled pages so an in-progress swipe cannot trigger a competing animation.
    LaunchedEffect(header.id, settledTab) {
        onTabSelected(settledTab.kind)
    }

    val activeState = tabStates[settledTab.kind]
    val activeFilters = activeState?.filters.orEmpty()
    val activeSelection = activeState?.selected.orEmpty()
    val showFilterBar = activeFilters.isNotEmpty() && !settledTab.isAbout

    var collapsingHeaderHeightPx by remember { mutableFloatStateOf(0f) }
    var stickySectionHeightPx by remember { mutableFloatStateOf(0f) }
    var headerOffsetPx by rememberSaveable { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    val collapseTitleThresholdPx = with(density) { 2.dp.toPx() }
    val visibleHeaderHeightDp =
        with(density) {
            (collapsingHeaderHeightPx + stickySectionHeightPx + headerOffsetPx)
                .coerceAtLeast(stickySectionHeightPx)
                .toDp()
        }
    val headerMeasured by remember { derivedStateOf { collapsingHeaderHeightPx > 0f } }
    val showCollapsedTopBarTitle by remember(collapseTitleThresholdPx) {
        derivedStateOf {
            headerMeasured &&
                headerOffsetPx <= -collapsingHeaderHeightPx + collapseTitleThresholdPx
        }
    }

    LaunchedEffect(showCollapsedTopBarTitle) {
        onCollapsedTitleVisibilityChange(showCollapsedTopBarTitle)
    }

    DisposableEffect(Unit) {
        onDispose { onCollapsedTitleVisibilityChange(false) }
    }

    LaunchedEffect(collapsingHeaderHeightPx) {
        headerOffsetPx = headerOffsetPx.coerceIn(-collapsingHeaderHeightPx, 0f)
    }

    // A remote moves focus instead of scrolling. Content scrolled under the header would hide the item
    // it focuses, and Down from the tabs would find no list below them, so on a D-pad the content sits
    // under the header and the header folds away while focus is in the content.
    val dpad = LocalDpadNavigation.current
    var contentHasFocus by remember { mutableStateOf(false) }
    val headerFoldSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    if (dpad) {
        LaunchedEffect(contentHasFocus, collapsingHeaderHeightPx) {
            val target = if (contentHasFocus) -collapsingHeaderHeightPx else 0f
            animate(headerOffsetPx, target, animationSpec = headerFoldSpec) { value, _ -> headerOffsetPx = value }
        }
    }
    val contentTopInset = if (dpad) 0.dp else visibleHeaderHeightDp

    val nestedScrollConnection =
        remember(collapsingHeaderHeightPx) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (available.y >= 0f) return Offset.Zero
                    val previous = headerOffsetPx
                    val next = (previous + available.y).coerceIn(-collapsingHeaderHeightPx, 0f)
                    headerOffsetPx = next
                    return Offset(x = 0f, y = next - previous)
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (available.y <= 0f) return Offset.Zero
                    val previous = headerOffsetPx
                    val next = (previous + available.y).coerceIn(-collapsingHeaderHeightPx, 0f)
                    headerOffsetPx = next
                    return Offset(x = 0f, y = next - previous)
                }
            }
        }

    // Persist Videos-tab scroll position across navigation
    val videosListState =
        rememberLazyGridState(
            initialFirstVisibleItemIndex = initialScrollIndex,
            initialFirstVisibleItemScrollOffset = initialScrollOffset,
        )
    val shortsListState = rememberLazyGridState()
    val liveListState = rememberLazyGridState()
    val playlistsListState = rememberLazyGridState()
    val postsListState = rememberLazyListState()
    val homeListState = rememberLazyGridState()
    val searchListState = rememberLazyGridState()
    val aboutListState = rememberLazyListState()
    val genericListState = rememberLazyGridState()

    fun listStateFor(kind: ChannelTabKind) =
        when (kind) {
            ChannelTabKind.Videos -> videosListState
            ChannelTabKind.Shorts -> shortsListState
            ChannelTabKind.Live -> liveListState
            ChannelTabKind.Playlists -> playlistsListState
            else -> genericListState
        }

    LaunchedEffect(videosListState) {
        snapshotFlow { videosListState.firstVisibleItemIndex to videosListState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> onScrollChanged(index, offset) }
    }

    // A new tab or filter starts at the top; coming back to the page keeps the list where it was.
    var scrolledFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(activeSelection, settledTab.kind) {
        val target = "${settledTab.kind}:$activeSelection"
        if (scrolledFor != null && scrolledFor != target) listStateFor(settledTab.kind).scrollToItem(0)
        scrolledFor = target
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .then(if (dpad) Modifier else Modifier.nestedScroll(nestedScrollConnection)),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(
                        if (dpad) {
                            Modifier.padding(top = visibleHeaderHeightDp).onFocusChanged { contentHasFocus = it.hasFocus }
                        } else {
                            Modifier
                        },
                    ).graphicsLayer { alpha = if (headerMeasured) 1f else 0f },
            verticalAlignment = Alignment.Top,
            // Off on a D-pad, which also stops focus at a row's edge from paging to the next tab.
            userScrollEnabled = !dpad,
        ) { page ->
            val listPadding = PaddingValues(top = contentTopInset, bottom = flowBottomContentPadding())
            val tab = visibleTabs.getOrElse(page) { visibleTabs.first() }

            when {
                tab.isAbout -> {
                    ChannelReadingPane {
                        LazyColumn(
                            state = aboutListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = listPadding,
                        ) {
                            item { ChannelAboutSection(header = header) }
                            item { Spacer(Modifier.height(16.dp)) }
                        }
                    }
                }

                tab.kind == ChannelTabKind.Posts -> {
                    ChannelReadingPane(maxWidth = PostsPaneMaxWidth) {
                        ChannelCommunityPosts(
                            posts = communityUiState.posts,
                            isLoading = communityUiState.isLoadingPosts,
                            isLoadingMore = communityUiState.isLoadingMorePosts,
                            hasMore = communityUiState.postsContinuation != null,
                            errorLog = communityUiState.postsErrorLog,
                            listState = postsListState,
                            contentPadding = listPadding,
                            onAuthorClick = { onChannelClick(header.id) },
                            onCommentsClick = onCommunityPostComments,
                            onShareClick = onCommunityPostShare,
                            onLoadMore = onLoadMoreCommunityPosts,
                            onRetry = onRetryCommunityPosts,
                        )
                    }
                }

                uiState.searchActive && uiState.searchQuery.isNotBlank() && tab.kind == ChannelTabKind.Videos -> {
                    ChannelSearchResults(
                        uiState = uiState,
                        listState = searchListState,
                        contentPadding = listPadding,
                        topInset = contentTopInset,
                        isGridView = isGridView,
                        columnPreference = columnPreference,
                        onVideoClick = onVideoClick,
                        onRetry = { onSearchQueryChange(uiState.searchQuery) },
                    )
                }

                tab.kind == ChannelTabKind.Home -> {
                    FeedShelfSections(
                        sections = tabStates[tab.kind]?.sections.orEmpty(),
                        isLoading = tabStates[tab.kind]?.loaded != true,
                        listState = homeListState,
                        columnPreference = columnPreference,
                        contentPadding = listPadding,
                        topInset = contentTopInset,
                        actions =
                            FeedShelfActions(
                                onVideoClick = onVideoClick,
                                onShortClick = onShortClick,
                                onPlaylistClick = onPlaylistClick,
                                canOpenSection = { section -> sectionTarget(section, visibleTabs) != null },
                                subscribedChannelIds = subscribedChannelIds,
                                onSubscribeChannel = onSubscribeChannel,
                                onSectionMore = { section ->
                                    when (val target = sectionTarget(section, visibleTabs)) {
                                        is SectionTarget.Playlist -> {
                                            onPlaylistClick(target.playlistId)
                                        }

                                        is SectionTarget.Tab -> {
                                            coroutineScope.launch { pagerState.animateScrollToPage(target.index) }
                                        }

                                        null -> {
                                            Unit
                                        }
                                    }
                                },
                            ),
                        slots =
                            FeedShelfSlots(
                                post = { post, compact ->
                                    MediaPostCard(
                                        post = post,
                                        onAuthorClick = { onChannelClick(header.id) },
                                        onCommentsClick = { onCommunityPostComments(post) },
                                        onShareClick = { onCommunityPostShare(post) },
                                        showDivider = !compact,
                                        compact = compact,
                                        modifier = if (compact) Modifier.padding(bottom = 4.dp) else Modifier,
                                    )
                                },
                                channelRow = { channel, isSubscribed ->
                                    ChannelRow(
                                        channel = channel,
                                        onClick = { onChannelClick(channel.id) },
                                        isSubscribed = isSubscribed,
                                        onSubscribeClick = { onSubscribeChannel(channel, true) },
                                        onUnsubscribeClick = { onSubscribeChannel(channel, false) },
                                        isNotificationsEnabled = channel.id in notifyingChannelIds,
                                        onNotificationChange = { onChannelNotificationChange(channel.id, it) },
                                    )
                                },
                            ),
                    )
                }

                else -> {
                    val items = tabStates[tab.kind]?.items?.collectAsLazyPagingItems()
                    ChannelTabItems(
                        pagingItems = items,
                        kind = tab.kind,
                        isGridView = isGridView,
                        columnPreference = columnPreference,
                        hasFilterBar = tabStates[tab.kind]?.filters.orEmpty().isNotEmpty(),
                        listState = listStateFor(tab.kind),
                        contentPadding = listPadding,
                        topInset = contentTopInset,
                        onVideoClick = onVideoClick,
                        onShortClick = onShortClick,
                        onPlaylistClick = onPlaylistClick,
                        onChannelClick = onChannelClick,
                    )
                }
            }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(x = 0, y = headerOffsetPx.roundToInt()) },
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .onSizeChanged { collapsingHeaderHeightPx = it.height.toFloat() },
            ) {
                ChannelHeaderSection(
                    header = header,
                    isSubscribed = uiState.isSubscribed,
                    isNotificationsEnabled = uiState.isNotificationsEnabled,
                    onSubscribeClick = onSubscribeClick,
                    onUnsubscribeClick = onUnsubscribeClick,
                    onNotificationChange = onNotificationChange,
                    onManageGroups = onManageGroups.takeIf { uiState.isSubscribed },
                    note = channelNote,
                    onEditNote = onEditNote,
                )
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .onSizeChanged { stickySectionHeightPx = it.height.toFloat() },
            ) {
                ChannelTabRow(
                    selectedIndex = pagerState.currentPage,
                    tabs = visibleTabs.map { it.title },
                    onTabSelected = { idx ->
                        coroutineScope.launch { pagerState.animateScrollToPage(idx) }
                    },
                )
                if (showFilterBar) {
                    ChannelFilterBar(
                        filterGroups = activeFilters,
                        selected = activeSelection,
                        searchActive = uiState.searchActive,
                        searchQuery = uiState.searchQuery,
                        // The Shorts tab is a fixed portrait grid and has no in-channel search.
                        showListControls = settledTab.kind != ChannelTabKind.Shorts,
                        onFilterSelected = { group, option -> onFilterSelected(settledTab.kind, group, option) },
                        onSearchToggle = onSearchToggle,
                        onSearchQueryChange = onSearchQueryChange,
                    )
                }
            }
        }
    }
}

// Filter + grid toggle bar

/**
 * Where a Home shelf's chevron leads. Most shelves carry a `moreParams` that maps to no tab the app
 * shows, so the chevron is drawn only when this resolves — a chevron that does nothing reads as broken.
 */
private sealed interface SectionTarget {
    data class Playlist(
        val playlistId: String,
    ) : SectionTarget

    data class Tab(
        val index: Int,
    ) : SectionTarget
}

private fun sectionTarget(
    section: FeedShelf,
    tabs: List<ChannelScreenTab>,
): SectionTarget? {
    section.morePlaylistId?.let { return SectionTarget.Playlist(it) }
    val params = section.moreParams ?: return null
    val index = tabs.indexOfFirst { it.params == params }
    return if (index >= 0) SectionTarget.Tab(index) else null
}
