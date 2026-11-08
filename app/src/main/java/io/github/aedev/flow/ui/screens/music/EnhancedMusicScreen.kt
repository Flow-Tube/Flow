package io.github.aedev.flow.ui.screens.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.music.model.audioMusicOnly
import io.github.aedev.flow.innertube.pages.MoodAndGenres
import io.github.aedev.flow.ui.TabScrollEventBus
import io.github.aedev.flow.ui.components.layout.LocalFlowBottomInsets
import io.github.aedev.flow.ui.components.layout.floatAboveBottomChrome
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBar
import io.github.aedev.flow.ui.components.music.section.HomeSectionType
import io.github.aedev.flow.ui.components.music.section.MusicHomeLibrary
import io.github.aedev.flow.ui.components.music.section.musicHomeFeed
import io.github.aedev.flow.ui.components.music.sheet.LocalMusicMenus
import io.github.aedev.flow.ui.components.shared.FlowErrorState
import io.github.aedev.flow.ui.components.shared.FlowPullToRefreshBox
import io.github.aedev.flow.ui.components.shared.MusicScreenShimmerLoading
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import java.util.Random

private val FeedBottomClearance = 96.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EnhancedMusicScreen(
    onSongClick: (MusicTrack, List<MusicTrack>, String?) -> Unit,
    onVideoClick: (MusicTrack) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onSearchClick: () -> Unit = {},
    onRecognizeClick: () -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onMoodsClick: (MoodAndGenres.Item?) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {},
    onAllPlaylistsClick: () -> Unit = {},
    onAllSubscriptionsClick: () -> Unit = {},
    showRecognize: Boolean = true,
    viewModel: MusicViewModel = sharedMusicViewModel(),
    libraryViewModel: MusicHomeLibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val ownPlaylists by libraryViewModel.playlists.collectAsStateWithLifecycle()
    val musicSubscriptions by libraryViewModel.subscriptions.collectAsStateWithLifecycle()
    val hiddenShelves by libraryViewModel.hiddenShelves.collectAsStateWithLifecycle()
    val lastFmSignedIn by libraryViewModel.lastFmSignedIn.collectAsStateWithLifecycle()
    val discovery by libraryViewModel.discovery.collectAsStateWithLifecycle()
    val library =
        remember(
            ownPlaylists,
            musicSubscriptions,
            hiddenShelves,
            onPlaylistClick,
            onAllPlaylistsClick,
            onAllSubscriptionsClick,
            lastFmSignedIn,
            discovery,
        ) {
            MusicHomeLibrary(
                playlists = ownPlaylists,
                subscriptions = musicSubscriptions,
                hidden = hiddenShelves,
                onPlaylistClick = onPlaylistClick,
                onAllPlaylistsClick = onAllPlaylistsClick,
                onAllSubscriptionsClick = onAllSubscriptionsClick,
                discoveryAvailable = lastFmSignedIn,
                discovery = discovery,
                onDiscoveryShown = libraryViewModel::loadDiscovery,
            )
        }
    val musicListState = rememberLazyListState()
    val quickPicksGridState = rememberLazyGridState()

    // Planner-lite: the brain's maturity decides which sections lead the page —
    // a cold brain has nothing personal to say, a mature one leads with taste.
    val sectionOrder =
        remember(uiState.sessionSeed, uiState.brainMaturity) {
            val defaultOrder = HomeSectionType.entries
            val anchored =
                when (uiState.brainMaturity) {
                    "cold_start" -> {
                        listOf(
                            HomeSectionType.CHARTS,
                            HomeSectionType.MOODS_AND_GENRES,
                            HomeSectionType.NEW_RELEASES,
                        )
                    }

                    "mature" -> {
                        listOf(
                            HomeSectionType.QUICK_PICKS,
                            HomeSectionType.SIMILAR_TO,
                            HomeSectionType.FROM_COMMUNITY,
                        )
                    }

                    else -> {
                        listOf(
                            HomeSectionType.QUICK_PICKS,
                            HomeSectionType.FROM_COMMUNITY,
                            HomeSectionType.DAILY_DISCOVER,
                        )
                    }
                }
            val dynamicPool = defaultOrder - anchored
            anchored + dynamicPool.shuffled(Random(uiState.sessionSeed))
        }

    // Scroll to top and refresh when tapping the music tab while already on this screen
    LaunchedEffect(Unit) {
        TabScrollEventBus.scrollToTopEvents
            .filter { it == "music" }
            .collectLatest {
                musicListState.animateScrollToItem(0)
                viewModel.refresh()
            }
    }
    val musicMenus = LocalMusicMenus.current

    val bottomInsets = LocalFlowBottomInsets.current

    Scaffold(
        topBar = {
            FlowTopBar(
                title = stringResource(R.string.screen_title_music),
                actions = {
                    IconButton(onClick = onSearchClick) {
                        Icon(Icons.Outlined.Search, stringResource(R.string.search))
                    }
                },
            )
        },
        floatingActionButton = {
            if (showRecognize) {
                FloatingActionButton(
                    onClick = onRecognizeClick,
                    modifier = Modifier.floatAboveBottomChrome(bottomInsets),
                ) {
                    Icon(Icons.Rounded.Mic, stringResource(R.string.recognize_music))
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            val isInitialLoading = uiState.isLoading && uiState.trendingSongs.isEmpty() && uiState.dynamicSections.isEmpty()

            when {
                isInitialLoading -> {
                    MusicScreenShimmerLoading()
                }

                uiState.error != null && uiState.trendingSongs.isEmpty() -> {
                    FlowErrorState(
                        error = uiState.error ?: stringResource(R.string.error_occurred),
                        onRetry = { viewModel.retry() },
                    )
                }

                else -> {
                    val popularArtists =
                        remember(uiState.trendingSongs, uiState.newReleases) {
                            (uiState.trendingSongs + uiState.newReleases)
                                .distinctBy { it.artist }
                                .take(10)
                        }

                    // Raw concatenation is only the placeholder until the VM's
                    // brain-ranked speed dial lands.
                    val fallbackSpeedDial =
                        remember(uiState.history, uiState.forYouTracks, uiState.listenAgain) {
                            (uiState.history + uiState.forYouTracks + uiState.listenAgain)
                                .audioMusicOnly()
                                .take(26)
                        }
                    val speedDialTracks = uiState.speedDialTracks.ifEmpty { fallbackSpeedDial }
                    val quickPickTracks =
                        remember(uiState.forYouTracks) {
                            uiState.forYouTracks.audioMusicOnly().take(20)
                        }
                    val pullState = rememberPullToRefreshState()

                    FlowPullToRefreshBox(
                        isRefreshing = uiState.isLoading || uiState.homeChipPage?.status == MusicHomeChipStatus.REFRESHING,
                        onRefresh = { viewModel.refresh() },
                        state = pullState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(
                            state = musicListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = flowBottomContentPadding(FeedBottomClearance)),
                        ) {
                            musicHomeFeed(
                                uiState = uiState,
                                sectionOrder = sectionOrder,
                                quickPickTracks = quickPickTracks,
                                speedDialTracks = speedDialTracks,
                                popularArtists = popularArtists,
                                library = library,
                                quickPicksGridState = quickPicksGridState,
                                onSongClick = onSongClick,
                                onVideoClick = onVideoClick,
                                onArtistClick = onArtistClick,
                                onAlbumClick = onAlbumClick,
                                onMoodsClick = onMoodsClick,
                                onChipToggle = { viewModel.setHomeChip(it) },
                                onTrackMenu = musicMenus::openSong,
                                onCollectionMenu = musicMenus::openCollection,
                                onLoadMore = { viewModel.loadMoreHomeContent() },
                                onChipRetry = { viewModel.retryHomeChip() },
                                onChipLoadMore = { viewModel.loadMoreHomeChip() },
                            )
                        }
                    }
                }
            }
        }
    }
}
