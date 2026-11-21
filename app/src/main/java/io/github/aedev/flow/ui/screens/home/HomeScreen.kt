package io.github.aedev.flow.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.HomeFeedColumns
import io.github.aedev.flow.data.local.HomeViewMode
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.VideoCardStyle
import io.github.aedev.flow.data.local.VideoHistoryEntry
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.shorts.queue.ShortsQueueSource
import io.github.aedev.flow.player.DeepFlowManager
import io.github.aedev.flow.ui.OnTabReselected
import io.github.aedev.flow.ui.TabScrollEventBus
import io.github.aedev.flow.ui.components.layout.LocalFlowBottomInsets
import io.github.aedev.flow.ui.components.layout.floatAboveBottomChrome
import io.github.aedev.flow.ui.components.layout.navigation.FlowTab
import io.github.aedev.flow.ui.components.layout.topbar.FlowScrollAwayLayout
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBar
import io.github.aedev.flow.ui.components.layout.topbar.rememberFlowScrollAwayState
import io.github.aedev.flow.ui.components.rememberFeedGridLayout
import io.github.aedev.flow.ui.components.shared.FlowPullToRefreshBox
import io.github.aedev.flow.ui.screens.home.chips.HomeChipRow
import io.github.aedev.flow.ui.utils.reachUnderStatusBar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val MILLIS_PER_SECOND = 1000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onVideoClick: (Video) -> Unit,
    onShortClick: (ShortsQueueSource) -> Unit,
    onSearchClick: () -> Unit,
    onNavigateToHistory: () -> Unit = {},
    onOpenShortsFeed: () -> Unit = {},
    onPlayMix: (videos: List<Video>, title: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val preferences = remember(context) { PlayerPreferences(context) }
    val topBarHideOnScroll by preferences.topBarHideOnScroll.collectAsStateWithLifecycle(initialValue = true)
    val videoCardStyle by preferences.videoCardStyle.collectAsStateWithLifecycle(initialValue = VideoCardStyle.ROUNDED)
    val homeViewMode by preferences.homeViewMode.collectAsStateWithLifecycle(initialValue = HomeViewMode.GRID)
    val homeFeedColumns by preferences.homeFeedColumns.collectAsStateWithLifecycle(initialValue = HomeFeedColumns.AUTO)
    val homeFeedEnabled by preferences.homeFeedEnabled.collectAsStateWithLifecycle(initialValue = true)
    val refreshHomeOnReselect by preferences.refreshHomeOnReselect.collectAsStateWithLifecycle(initialValue = true)
    val showAppLogoIcon by preferences.showAppLogoIcon.collectAsStateWithLifecycle(initialValue = true)
    val deepFlowActive by preferences.deepFlowActive.collectAsStateWithLifecycle(initialValue = false)

    val gridStates = rememberHomeGridStates()
    val coroutineScope = rememberCoroutineScope()
    val chips by viewModel.chips.collectAsStateWithLifecycle()
    val headerState = rememberFlowScrollAwayState()
    val headerSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val snackbarHostState = remember { SnackbarHostState() }
    val refreshFailed = stringResource(R.string.home_refresh_failed)
    val retryLabel = stringResource(R.string.retry)

    LaunchedEffect(viewModel) {
        viewModel.refreshFailures.collectLatest {
            val result = snackbarHostState.showSnackbar(refreshFailed, actionLabel = retryLabel, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) viewModel.refreshFeed()
        }
    }

    HomeFeedEffects(
        viewModel = viewModel,
        uiState = uiState,
        chips = chips,
        gridStates = gridStates,
        homeFeedEnabled = homeFeedEnabled,
        refreshHomeOnReselect = refreshHomeOnReselect,
    )

    val haptic = LocalHapticFeedback.current
    val homeLabel = stringResource(R.string.nav_home)
    val deepFlowLabel = stringResource(R.string.deep_flow_mode_title)
    val reselectHome: () -> Unit = { TabScrollEventBus.emitScrollToTop(FlowTab.Home.route) }
    val toggleDeepFlow: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        coroutineScope.launch { DeepFlowManager.toggle(context) }
    }
    val logoClicks = Modifier.homeLogoClicks(homeLabel, deepFlowLabel, onClick = reselectHome, onLongClick = toggleDeepFlow)
    OnTabReselected(FlowTab.Home.route) { headerState.show(headerSpec) }

    Scaffold(
        modifier = modifier.fillMaxSize().reachUnderStatusBar(),
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = {
            SnackbarHost(snackbarHostState, Modifier.floatAboveBottomChrome(LocalFlowBottomInsets.current))
        },
    ) { padding ->
        FlowScrollAwayLayout(
            modifier = Modifier.padding(padding),
            state = headerState,
            hideOnScroll = topBarHideOnScroll,
            header = {
                FlowTopBar(
                    title = {
                        Text(
                            stringResource(R.string.app_name_uppercase),
                            modifier = logoClicks,
                            style =
                                MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.sp,
                                ),
                        )
                    },
                    leading =
                        if (showAppLogoIcon) {
                            {
                                FlowHeaderLogoIcon(
                                    isDeepFlowActive = deepFlowActive,
                                    modifier = Modifier.size(32.dp).then(logoClicks),
                                )
                            }
                        } else {
                            null
                        },
                    actions = {
                        IconButton(onClick = onSearchClick) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = stringResource(R.string.search),
                            )
                        }
                    },
                )
                if (homeFeedEnabled && chips.chips.size > 1) {
                    HomeChipRow(
                        state = chips,
                        onSelect = { key ->
                            coroutineScope.launch { headerState.show(headerSpec) }
                            viewModel.selectChip(key)
                        },
                    )
                }
            },
        ) { topPadding ->
            ResettableHomePullToRefreshBox(
                resetKey = uiState.isLoading && uiState.videos.isEmpty(),
                isRefreshing = if (chips.isAll) uiState.isRefreshing else chips.isRefreshing,
                onRefresh = { if (chips.isAll) viewModel.refreshFeed() else viewModel.refreshSelectedChip() },
                indicatorTopPadding = topPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    HomeFeedBody(
                        viewModel = viewModel,
                        uiState = uiState,
                        chips = chips,
                        gridStates = gridStates,
                        feedLayout = rememberFeedGridLayout(maxWidth, homeFeedColumns, cardStyle = videoCardStyle),
                        isListView = homeViewMode == HomeViewMode.LIST,
                        homeFeedEnabled = homeFeedEnabled,
                        onVideoClick = onVideoClick,
                        onPlayMix = onPlayMix,
                        onShortClick = onShortClick,
                        onNavigateToHistory = onNavigateToHistory,
                        onOpenShortsFeed = onOpenShortsFeed,
                        topPadding = topPadding,
                        headerOffsetPx = { headerState.headerOffsetPx },
                    )
                }
            }
        }
    }
}

// Deliberately not data/model's VideoHistoryEntry.toVideo(): that one carries isShort, which would
// reroute a resumed short to the Shorts player instead of the video player.
internal fun VideoHistoryEntry.toResumeVideo(): Video =
    Video(
        id = videoId,
        title = title,
        channelName = channelName,
        channelId = channelId,
        thumbnailUrl = thumbnailUrl,
        duration = (duration / MILLIS_PER_SECOND).toInt(),
        viewCount = 0L,
        uploadDate = "",
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResettableHomePullToRefreshBox(
    resetKey: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    indicatorTopPadding: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    key(resetKey) {
        FlowPullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            indicatorTopPadding = indicatorTopPadding,
            modifier = modifier,
            content = content,
        )
    }
}
