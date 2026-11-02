package io.github.aedev.flow.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.TabScrollEventBus
import io.github.aedev.flow.ui.components.layout.FlowBottomInsets
import io.github.aedev.flow.ui.components.layout.LocalFlowBottomInsets
import io.github.aedev.flow.ui.components.settings.SettingsTarget
import io.github.aedev.flow.ui.components.shared.MediaMiniBarDefaults
import io.github.aedev.flow.ui.screens.home.HomeViewModel
import io.github.aedev.flow.ui.screens.music.MusicPlayerViewModel
import io.github.aedev.flow.ui.screens.music.MusicViewModel
import io.github.aedev.flow.ui.screens.search.SearchViewModel
import io.github.aedev.flow.ui.screens.subscriptions.SubscriptionsViewModel
import io.github.aedev.flow.ui.tv.components.TvNavRail
import io.github.aedev.flow.ui.tv.components.TvRailProfile
import io.github.aedev.flow.ui.tv.components.railFocusDirection
import io.github.aedev.flow.ui.tv.music.TvMiniPlayer
import io.github.aedev.flow.ui.tv.navigation.TvNavHost
import io.github.aedev.flow.ui.tv.navigation.TvNavigation
import io.github.aedev.flow.ui.tv.navigation.TvRoutes
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens

/**
 * TV browse shell: content laid out against the collapsed rail width, with the mini player floating
 * over its bottom edge; the rail lies over both, so its focus expansion never reflows the page. Coming
 * back from the rail returns focus to the item it left.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun TvShell(
    navigation: TvNavigation,
    navController: NavHostController,
    homeViewModel: HomeViewModel,
    musicViewModel: MusicViewModel,
    musicPlayerViewModel: MusicPlayerViewModel,
    subscriptionsViewModel: SubscriptionsViewModel,
    searchViewModel: SearchViewModel,
    onPlayVideo: (Video) -> Unit,
    onPlayPlaylist: (videos: List<Video>, startIndex: Int, title: String, shuffle: Boolean) -> Unit,
    onPlayVideoId: (String) -> Unit,
    activeMusicTrack: MusicTrack?,
    onExpandMusic: () -> Unit,
    onDismissMusic: () -> Unit,
    modifier: Modifier = Modifier,
    startSettings: SettingsTarget? = null,
) {
    LaunchedEffect(startSettings) { startSettings?.let { navController.navigate(TvRoutes.settingsPage(it)) } }
    val dimens = LocalTvDimens.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val rootRoutes = navigation.rootRoutes
    val isOnDetailRoute = currentRoute != null && currentRoute !in rootRoutes
    var lastRoot by remember { mutableStateOf(navigation.startTab.route) }
    LaunchedEffect(currentRoute) {
        if (currentRoute != null && currentRoute in rootRoutes) lastRoot = currentRoute
    }
    val currentRoot = currentRoute?.takeIf { it in rootRoutes } ?: lastRoot
    val miniPlayerShown = rememberUpdatedState(activeMusicTrack != null)
    // Phone pages pad their ends by these insets, so they scroll clear of the floating mini player here too.
    val bottomInsets =
        remember(dimens) {
            FlowBottomInsets(
                systemInset = mutableStateOf(0.dp),
                barHeight = mutableStateOf(0.dp),
                barShown = mutableStateOf(false),
                miniPlayerHeight = mutableStateOf(MediaMiniBarDefaults.Height + dimens.overscanVertical),
                miniPlayerShown = miniPlayerShown,
                barFraction = { 0f },
                miniPlayerFraction = { if (miniPlayerShown.value) 1f else 0f },
            )
        }
    var railHasFocus by remember { mutableStateOf(false) }
    // Set when the rail took focus only because the page had nothing to hold it yet, as while it loads;
    // the page takes it back once it can, unless the remote has been used in the rail since.
    var railHoldsFocusForPage by remember { mutableStateOf(false) }
    var contentHasFocus by remember { mutableStateOf(false) }
    val railFocusRequester = remember { FocusRequester() }
    val pageFocusRequester = remember { FocusRequester() }
    val miniPlayerFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val layoutDirection = LocalLayoutDirection.current
    val contentFocusRequester = remember { FocusRequester() }

    // Registered before the NavHost, so a page's own Back (a settings sub-page, a list mode) and the
    // NavHost's pop come first: a detail route pops, another root returns to the start tab. Only on the
    // start tab itself does Back reach this, moving focus to the rail, and from the rail the app exits.
    BackHandler(enabled = !isOnDetailRoute && currentRoot == navigation.startTab.route && !railHasFocus) {
        railFocusRequester.requestFocus(railFocusDirection(layoutDirection))
    }

    fun navigateToRoot(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Navigating to a root with restoreState brings back the pages last opened over it, so the root the
    // remote is already under, and the start root (always at the bottom of the stack), are popped to instead.
    fun selectRoot(route: String) {
        val popped =
            when {
                route == currentRoute -> {
                    TabScrollEventBus.emitScrollToTop(route)
                    true
                }

                route == currentRoot -> {
                    navController.popBackStack(route, inclusive = false)
                }

                route == navigation.startTab.route -> {
                    navController.popBackStack(route, inclusive = false, saveState = true)
                }

                else -> {
                    false
                }
            }
        if (!popped) navigateToRoot(route)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(start = dimens.railCollapsedWidth)
                    .onFocusChanged { contentHasFocus = it.hasFocus }
                    // A page composes its first focusable item when its data arrives, which is a new
                    // layout; until something holds focus, each one offers focus to the page, so the
                    // remote starts on the content instead of the rail.
                    .onGloballyPositioned {
                        if ((!railHasFocus || railHoldsFocusForPage) && !contentHasFocus) contentFocusRequester.requestFocus()
                    }.focusRequester(contentFocusRequester)
                    .focusRestorer()
                    .focusGroup(),
        ) {
            CompositionLocalProvider(LocalFlowBottomInsets provides bottomInsets) {
                TvNavHost(
                    navController = navController,
                    startRoute = navigation.startTab.route,
                    homeViewModel = homeViewModel,
                    musicViewModel = musicViewModel,
                    musicPlayerViewModel = musicPlayerViewModel,
                    subscriptionsViewModel = subscriptionsViewModel,
                    searchViewModel = searchViewModel,
                    onPlayVideo = onPlayVideo,
                    onPlayPlaylist = onPlayPlaylist,
                    onPlayVideoId = onPlayVideoId,
                    onSelectRoot = ::selectRoot,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .focusRequester(pageFocusRequester)
                            .focusRestorer()
                            .focusGroup()
                            // A page's last row can sit under the floating mini player, where the D-pad
                            // finds nothing below it, so Down that moves nowhere goes to the mini player.
                            .onKeyEvent { event ->
                                if (activeMusicTrack == null || !event.isPress(Key.DirectionDown)) return@onKeyEvent false
                                if (!focusManager.moveFocus(FocusDirection.Down)) miniPlayerFocusRequester.requestFocus()
                                true
                            },
                )
            }
            activeMusicTrack?.let { track ->
                TvMiniPlayer(
                    track = track,
                    onExpand = onExpandMusic,
                    onDismiss = onDismissMusic,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = dimens.overscanHorizontal)
                            .padding(bottom = dimens.overscanVertical)
                            .focusRequester(miniPlayerFocusRequester)
                            // An item under the bar is not above it either, so Up that moves nowhere goes back
                            // into the page, which pivots the item it lands on into view above the bar.
                            .onKeyEvent { event ->
                                if (!event.isPress(Key.DirectionUp)) return@onKeyEvent false
                                if (!focusManager.moveFocus(FocusDirection.Up)) pageFocusRequester.requestFocus()
                                true
                            },
                )
            }
        }
        AnimatedVisibility(
            visible = railHasFocus,
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(WideNavigationRailDefaults.colors().modalScrimColor),
            )
        }
        TvNavRail(
            tabs = navigation.tabs,
            selectedRoute = currentRoot,
            onSelect = ::selectRoot,
            onFocusChanged = {
                railHasFocus = it
                if (!it) railHoldsFocusForPage = false
            },
            focusRequester = railFocusRequester,
            onOtherEntry = { contentFocusRequester.requestFocus().also { taken -> railHoldsFocusForPage = !taken } },
            nowPlayingTitle = activeMusicTrack?.title,
            onOpenNowPlaying = onExpandMusic,
            profile = { expanded -> TvRailProfile(expanded, onOpenSettings = { navController.navigate(TvRoutes.settingsPage(it)) }) },
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .onPreviewKeyEvent {
                        railHoldsFocusForPage = false
                        false
                    },
        )
    }
}

private fun KeyEvent.isPress(key: Key): Boolean = this.key == key && type == KeyEventType.KeyDown
