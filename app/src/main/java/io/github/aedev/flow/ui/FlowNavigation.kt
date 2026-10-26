package io.github.aedev.flow.ui

import androidx.compose.animation.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.localmedia.toMusicTrack
import io.github.aedev.flow.data.localmedia.toVideo
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.toMusicTrack
import io.github.aedev.flow.data.music.model.toVideo
import io.github.aedev.flow.data.shorts.queue.ShortsQueueSource
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.ui.components.layout.LocalFlowBottomInsets
import io.github.aedev.flow.ui.components.layout.navigation.MediaNavigator
import io.github.aedev.flow.ui.components.musicplayer.sheet.MusicPlayerSheetState
import io.github.aedev.flow.ui.components.settings.SettingsDestination
import io.github.aedev.flow.ui.components.settings.SettingsTarget
import io.github.aedev.flow.ui.components.videoplayer.PlayerDraggableState
import io.github.aedev.flow.ui.screens.channel.ChannelScreen
import io.github.aedev.flow.ui.screens.equalizer.EqualizerScreen
import io.github.aedev.flow.ui.screens.history.HistoryScreen
import io.github.aedev.flow.ui.screens.home.HomeScreen
import io.github.aedev.flow.ui.screens.home.HomeViewModel
import io.github.aedev.flow.ui.screens.library.LibraryScreen
import io.github.aedev.flow.ui.screens.music.sharedMusicPlayerViewModel
import io.github.aedev.flow.ui.screens.notifications.NotificationScreen
import io.github.aedev.flow.ui.screens.onboarding.OnboardingScreen
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModel
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.ui.screens.playlists.PlaylistDetailScreen
import io.github.aedev.flow.ui.screens.playlists.PlaylistsScreen
import io.github.aedev.flow.ui.screens.recap.RecapRoutes
import io.github.aedev.flow.ui.screens.recap.RecapScreen
import io.github.aedev.flow.ui.screens.recap.story.RecapStoryScreen
import io.github.aedev.flow.ui.screens.search.SearchScreen
import io.github.aedev.flow.ui.screens.settings.SettingsHost
import io.github.aedev.flow.ui.screens.shorts.ShortsScreen
import io.github.aedev.flow.ui.screens.subscriptions.SubscriptionsScreen
import io.github.aedev.flow.ui.screens.update.UPDATE_ROUTE
import io.github.aedev.flow.ui.screens.update.UpdateScreen
import kotlinx.coroutines.flow.first

@UnstableApi
fun NavGraphBuilder.flowAppGraph(
    navController: NavHostController,
    mediaNavigator: MediaNavigator,
    currentRoute: MutableState<String>,
    playerSheetState: PlayerDraggableState,
    musicPlayerSheetState: MusicPlayerSheetState,
    homeViewModel: HomeViewModel,
    playerViewModel: VideoPlayerViewModel,
    playerUiStateResult: State<VideoPlayerUiState>,
    playerVisibleState: MutableState<Boolean>,
    disableShortsPlayer: Boolean = false,
    defaultStartRoute: String = "home",
    onMusicStarted: () -> Unit = {},
    openDownloads: () -> Unit = {},
) {
    // =============================================
    // ONBOARDING (First-time user experience)
    // =============================================
    composable("onboarding") {
        currentRoute.value = "onboarding"
        OnboardingScreen(
            onComplete = {
                // Navigate to the selected default tab and clear the backstack so user can't go back to onboarding
                navController.navigate(defaultStartRoute) {
                    popUpTo("onboarding") { inclusive = true }
                }
            },
        )
    }

    composable("home") {
        currentRoute.value = "home"
        HomeScreen(
            onVideoClick = { video ->
                navController.openVideoOrShorts(video, disableShortsPlayer) {
                    playerViewModel.playVideo(it)
                    GlobalPlayerState.setCurrentVideo(it)
                }
            },
            onShortClick = { source ->
                navController.openShortsOrPlayer(source, disableShortsPlayer)
            },
            onSearchClick = {
                navController.navigate("search")
            },
            onNavigateToHistory = {
                navController.navigate("history")
            },
            onOpenShortsFeed = {
                navController.openShorts(ShortsQueueSource.Feed)
            },
            onPlayMix = { videos, title -> playerViewModel.playPlaylist(videos, 0, title, false) },
            viewModel = homeViewModel,
        )
    }

    composable(UPDATE_ROUTE) {
        currentRoute.value = UPDATE_ROUTE
        UpdateScreen(onClose = { navController.popBackStack() })
    }

    // Notifications Screen
    composable("notifications") {
        currentRoute.value = "notifications"
        NotificationScreen(
            onBackClick = { navController.popBackStack() },
            onNotificationClick = { videoId ->
                navController.navigateToPlayer(videoId)
            },
            onOpenSettings = {
                navController.navigate("settings?target=${SettingsTarget(SettingsDestination.NOTIFICATIONS).encode()}")
            },
        )
    }

    composable(
        route = SHORTS_ROUTE_PATTERN,
        arguments =
            listOf(
                navArgument(SHORTS_ROUTE_ARG) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { backStackEntry ->
        currentRoute.value = SHORTS_ROUTE_KEY
        val source = ShortsQueueSource.decode(backStackEntry.arguments?.getString(SHORTS_ROUTE_ARG))
        val isRootTab = source == ShortsQueueSource.Feed
        ShortsScreen(
            source = source,
            bottomNavOverlayPadding = if (isRootTab) LocalFlowBottomInsets.current.barBottom else 0.dp,
            onBack = {
                navController.popBackStack()
            },
            onChannelClick = { channelId ->
                mediaNavigator.openChannel(channelId)
            },
        )
    }

    composable("subscriptions") { backStackEntry ->
        currentRoute.value = "subscriptions"
        val openMusic by backStackEntry.savedStateHandle
            .getStateFlow(OPEN_MUSIC_SUBSCRIPTIONS, false)
            .collectAsStateWithLifecycle()
        SubscriptionsScreen(
            openMusicSubscriptions = openMusic,
            onMusicSubscriptionsOpened = { backStackEntry.savedStateHandle[OPEN_MUSIC_SUBSCRIPTIONS] = false },
            onVideoClick = { video ->
                navController.openVideoOrShorts(video, disableShortsPlayer) {
                    playerViewModel.playVideo(it)
                    GlobalPlayerState.setCurrentVideo(it)
                }
            },
            onShortClick = { source ->
                navController.openShortsOrPlayer(source, disableShortsPlayer)
            },
            onChannelClick = { channel ->
                if (channel.isMusic && channel.id.isNotBlank()) {
                    mediaNavigator.openArtist(channel.id)
                } else {
                    mediaNavigator.openChannel(channel.url.ifBlank { channel.id })
                }
            },
        )
    }

    composable("library") {
        currentRoute.value = "library"
        val musicPlayerViewModel = sharedMusicPlayerViewModel()
        val downloadsSourceName =
            androidx.compose.ui.res.stringResource(
                io.github.aedev.flow.R.string.library_downloads_label,
            )
        LibraryScreen(
            onOpenRecap = { period -> navController.navigate(period?.let { RecapRoutes.story(it) } ?: RecapRoutes.stats()) },
            onNavigateToHistory = {
                navController.navigate("history")
            },
            onNavigateToPlaylists = { kind ->
                navController.navigate(if (kind == null) "playlists" else "playlists?kind=${kind.name}")
            },
            onNavigateToLikedVideos = {
                navController.navigate("playlist/${PlaylistRepository.LIKED_VIDEOS_ID}")
            },
            onNavigateToLikedMusic = {
                mediaNavigator.openMusicPlaylist(PlaylistRepository.LIKED_MUSIC_ID)
            },
            onNavigateToWatchLater = {
                navController.navigate("playlist/${PlaylistRepository.WATCH_LATER_ID}")
            },
            onNavigateToSavedShorts = {
                navController.navigate("savedShorts")
            },
            onNavigateToDownloads = openDownloads,
            onNavigateToLocalMedia = {
                navController.navigate("localMedia")
            },
            onNavigateToNotes = {
                navController.navigate("notes")
            },
            onManageData = {
                navController.navigate("settings")
            },
            onVideoClick = { video ->
                navController.openVideoOrShorts(video, disableShortsPlayer) { playerViewModel.playVideo(it) }
            },
            onMusicClick = { track, queue, sourceName ->
                musicPlayerViewModel.loadAndPlayTrack(track, queue, sourceName)
                onMusicStarted()
            },
            onPlaylistClick = { playlistId ->
                navController.navigate("playlist/$playlistId")
            },
            onMusicPlaylistClick = { playlistId ->
                mediaNavigator.openMusicPlaylist(playlistId)
            },
            onDownloadedVideoClick = { videos, index ->
                val videoList = videos.map { it.video }
                playerViewModel.playPlaylist(videoList, index, downloadsSourceName)
                GlobalPlayerState.setCurrentVideo(videoList[index])
            },
            onDownloadedMusicClick = { tracks, index ->
                val musicTracks = tracks.map { it.track }
                val selectedTrack = musicTracks[index]
                musicPlayerViewModel.loadAndPlayTrack(selectedTrack, musicTracks, downloadsSourceName)
                onMusicStarted()
            },
            onSavedShortClick = { video ->
                navController.openShortsOrPlayer(ShortsQueueSource.Saved(video.id), disableShortsPlayer)
            },
        )
    }

    composable("search") {
        currentRoute.value = "search"
        // Search owns the whole screen, the way YouTube's does.
        SearchScreen(
            onVideoClick = { video ->
                navController.openVideoOrShorts(video, disableShortsPlayer) { playerViewModel.playVideo(it) }
            },
            onShortsQueue = { source ->
                navController.openShortsOrPlayer(source, disableShortsPlayer)
            },
            onChannelClick = { channel ->
                mediaNavigator.openChannel(channel.url.ifBlank { channel.id })
            },
            onPlaylistClick = { playlist ->
                navController.navigate("playlist/${playlist.id}")
            },
            onBack = {
                if (!navController.popBackStack()) navController.navigate("home")
            },
        )
    }

    composable(EQUALIZER_ROUTE) {
        currentRoute.value = EQUALIZER_ROUTE
        EqualizerScreen(onBack = { navController.popBackStack() })
    }

    composable("categories") {
        currentRoute.value = "categories"
        io.github.aedev.flow.ui.screens.categories.CategoriesScreen(
            onVideoClick = { video ->
                navController.openVideoOrShorts(video, disableShortsPlayer) { playerViewModel.playVideo(it) }
            },
            onShortClick = { videoId ->
                navController.openShortsOrPlayer(ShortsQueueSource.SeededFeed(videoId), disableShortsPlayer)
            },
            onPlaylistClick = { playlistId ->
                navController.navigate("playlist/$playlistId")
            },
        )
    }

    composable(
        route = "settings?target={target}",
        arguments =
            listOf(
                navArgument("target") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { backStackEntry ->
        currentRoute.value = "settings"
        SettingsHost(
            start = SettingsTarget.decode(backStackEntry.arguments?.getString("target")),
            onExit = { navController.popBackStack() },
            onOpenDonations = { navController.navigate("donations") },
            onOpenRecap = { navController.navigate(RecapRoutes.stats()) },
            onOpenUpdate = { navController.navigate(UPDATE_ROUTE) },
        )
    }

    composable(
        route = RecapRoutes.STATS,
        arguments =
            listOf(
                navArgument(RecapRoutes.ARG_PERIOD) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { backStackEntry ->
        currentRoute.value = "recap"
        RecapScreen(
            onBack = { navController.popBackStack() },
            onPlayStory = { period, source -> navController.navigate(RecapRoutes.story(period, source)) },
            startAt = RecapRoutes.decode(backStackEntry.arguments?.getString(RecapRoutes.ARG_PERIOD)),
        )
    }

    composable(
        route = RecapRoutes.STORY,
        arguments =
            listOf(
                navArgument(RecapRoutes.ARG_PERIOD) { type = NavType.StringType },
                navArgument(RecapRoutes.ARG_SOURCE) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { backStackEntry ->
        currentRoute.value = "recap_story"
        val period = RecapRoutes.decode(backStackEntry.arguments?.getString(RecapRoutes.ARG_PERIOD))
        if (period == null) {
            LaunchedEffect(Unit) { navController.popBackStack() }
        } else {
            RecapStoryScreen(
                period = period,
                source = RecapRoutes.decodeSource(backStackEntry.arguments?.getString(RecapRoutes.ARG_SOURCE)),
                onClose = { navController.popBackStack() },
            )
        }
    }

    composable("donations") {
        currentRoute.value = "donations"
        io.github.aedev.flow.ui.screens.settings.about.DonationsScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable(
        route = "channel?url={channelUrl}",
        arguments = listOf(navArgument("channelUrl") { type = NavType.StringType }),
    ) { backStackEntry ->
        currentRoute.value = "channel"
        val channelUrl =
            backStackEntry.arguments?.getString("channelUrl")?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            } ?: ""

        ChannelScreen(
            channelUrl = channelUrl,
            onVideoClick = { video ->
                navController.openVideoOrShorts(video, disableShortsPlayer) { playerViewModel.playVideo(it) }
            },
            onChannelClick = { channelId ->
                mediaNavigator.openChannel(channelId)
            },
            onShortClick = { videoId, sortIndex ->
                navController.openShortsOrPlayer(
                    ShortsQueueSource.Channel(channelUrl = channelUrl, startVideoId = videoId, sortIndex = sortIndex),
                    disableShortsPlayer,
                )
            },
            onPlaylistClick = { playlistId ->
                navController.navigate("playlist/$playlistId")
            },
            onBackClick = { navController.popBackStack() },
        )
    }

    // History Screen
    composable("history") {
        currentRoute.value = "history"
        val musicPlayerViewModel = sharedMusicPlayerViewModel()
        HistoryScreen(
            onVideoClick = { track ->
                val deviceFile = LocalMediaIds.videoUri(track.videoId)
                if (deviceFile != null) {
                    val video =
                        io.github.aedev.flow.data.model.Video(
                            id = track.videoId,
                            title = track.title,
                            channelName = track.artist,
                            channelId = "",
                            thumbnailUrl = deviceFile.toString(),
                            duration = track.duration,
                            viewCount = 0,
                            uploadDate = "",
                        )
                    playerViewModel.playLocalVideo(video, deviceFile.toString())
                } else {
                    playerViewModel.playVideo(track.toVideo())
                }
            },
            onShortsQueue = { source ->
                navController.openShortsOrPlayer(source, disableShortsPlayer)
            },
            onMusicClick = { track, queue ->
                musicPlayerViewModel.loadAndPlayTrack(track, queue, "History")
                onMusicStarted()
            },
            onBackClick = { navController.popBackStack() },
        )
    }

    // Playlists Screen
    composable(
        route = "playlists?kind={kind}",
        arguments =
            listOf(
                navArgument("kind") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { backStackEntry ->
        currentRoute.value = "playlists"
        val fixedKind =
            backStackEntry.arguments?.getString("kind")?.let { name ->
                io.github.aedev.flow.ui.components.shared.MediaKind.entries
                    .firstOrNull { it.name == name }
            }
        PlaylistsScreen(
            fixedKind = fixedKind,
            onBackClick = { navController.popBackStack() },
            onVideoPlaylistClick = { playlist ->
                navController.navigate("playlist/${playlist.id}")
            },
            onMusicPlaylistClick = { playlist ->
                mediaNavigator.openMusicPlaylist(playlist.id)
            },
        )
    }

    // Playlist Detail Screen
    composable("playlist/{playlistId}") { _ ->
        currentRoute.value = "playlist"
        val musicPlayerViewModel = sharedMusicPlayerViewModel()
        PlaylistDetailScreen(
            onNavigateBack = { navController.popBackStack() },
            onPlayPlaylist = { videos, index, shuffle, title ->
                val start = videos[index]
                if (start.isMusic) {
                    // A YouTube Music playlist plays in the music player, like any other song list.
                    val tracks = videos.filter { it.isMusic }.map { it.toMusicTrack() }
                    musicPlayerViewModel.loadAndPlayTrack(start.toMusicTrack(), tracks, title)
                    onMusicStarted()
                } else {
                    playerViewModel.playPlaylist(videos, index, title, shuffle)
                }
            },
        )
    }

    // Saved Shorts Grid
    composable("savedShorts") {
        currentRoute.value = "savedShorts"
        io.github.aedev.flow.ui.screens.library.SavedShortsGridScreen(
            onBackClick = { navController.popBackStack() },
            onVideoClick = { videoId ->
                navController.openShortsOrPlayer(ShortsQueueSource.Saved(videoId), disableShortsPlayer)
            },
        )
    }

    composable("notes") {
        currentRoute.value = "notes"
        io.github.aedev.flow.ui.screens.notes.NotesScreen(
            onBackClick = { navController.popBackStack() },
            onPlay = { video, startPositionMs -> playerViewModel.playVideo(video, startPositionMs = startPositionMs) },
        )
    }
    composable("localMedia") {
        currentRoute.value = "localMedia"
        val musicPlayerViewModel = sharedMusicPlayerViewModel()
        val localTitle =
            androidx.compose.ui.res
                .stringResource(io.github.aedev.flow.R.string.local_media_title)
        io.github.aedev.flow.ui.screens.library.LocalMediaScreen(
            onBackClick = { navController.popBackStack() },
            onPlayVideos = { items, index, shuffle ->
                playerViewModel.playPlaylist(items.map { it.toVideo() }, index, localTitle, shuffle)
            },
            onPlayMusic = { items, index, shuffle ->
                val tracks = items.map { it.toMusicTrack() }.let { if (shuffle) it.shuffled() else it }
                val start = if (shuffle) tracks.first() else tracks[index]
                musicPlayerViewModel.loadAndPlayTrack(start, tracks, localTitle)
                onMusicStarted()
            },
            onOpenSettings = {
                navController.navigate("settings?target=${SettingsTarget(SettingsDestination.LOCAL_MEDIA).encode()}")
            },
        )
    }

    downloadsRoutes(navController, mediaNavigator, currentRoute, playerViewModel, onMusicStarted)

    musicRoutes(navController, mediaNavigator, currentRoute, playerViewModel, defaultStartRoute, onMusicStarted)

    widgetPlaybackRoutes(navController, currentRoute, defaultStartRoute, playerViewModel, onMusicStarted)

    linkPlaybackRoutes(
        navController = navController,
        currentRoute = currentRoute,
        playerViewModel = playerViewModel,
        playerUiStateResult = playerUiStateResult,
        playerSheetState = playerSheetState,
        playerVisibleState = playerVisibleState,
        defaultStartRoute = defaultStartRoute,
        onMusicStarted = onMusicStarted,
    )
}
