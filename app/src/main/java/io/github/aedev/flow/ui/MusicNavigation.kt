package io.github.aedev.flow.ui

import androidx.compose.runtime.*
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.aedev.flow.data.localmedia.toMusicTrack
import io.github.aedev.flow.data.localmedia.toVideo
import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.music.model.toMusicTrack
import io.github.aedev.flow.data.music.model.toVideo
import io.github.aedev.flow.ui.components.layout.navigation.FlowTab
import io.github.aedev.flow.ui.components.layout.navigation.MediaNavigator
import io.github.aedev.flow.ui.screens.music.EnhancedMusicScreen
import io.github.aedev.flow.ui.screens.music.MusicViewModel
import io.github.aedev.flow.ui.screens.music.sharedMusicPlayerViewModel
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModel

/** The music tab and every page reached from it: search, recognition, browse, artists and collections. */
internal fun NavGraphBuilder.musicRoutes(
    navController: NavHostController,
    mediaNavigator: MediaNavigator,
    currentRoute: MutableState<String>,
    playerViewModel: VideoPlayerViewModel,
    defaultStartRoute: String,
    onMusicStarted: () -> Unit,
) {
    composable("music") {
        currentRoute.value = "music"

        val musicPlayerViewModel = sharedMusicPlayerViewModel()

        EnhancedMusicScreen(
            onSongClick = { track, queue, source ->
                musicPlayerViewModel.loadAndPlayTrack(track, queue, source)

                // Navigate to player
                onMusicStarted()
            },
            onVideoClick = { track ->
                playerViewModel.playVideo(track.toVideo())
            },
            onArtistClick = { channelId ->
                mediaNavigator.openArtist(channelId)
            },
            onSearchClick = {
                navController.navigate("musicSearch")
            },
            onRecognizeClick = {
                navController.navigate("musicRecognize")
            },
            onAlbumClick = { albumId ->
                mediaNavigator.openAlbum(albumId)
            },
            onPlaylistClick = mediaNavigator::openMusicPlaylist,
            onAllPlaylistsClick = {
                navController.navigate("playlists?kind=${io.github.aedev.flow.ui.components.shared.MediaKind.Music.name}")
            },
            onAllSubscriptionsClick = {
                currentRoute.value = FlowTab.Subscriptions.route
                navController.navigateToTab(FlowTab.Subscriptions, defaultStartRoute)
                navController.currentBackStackEntry?.savedStateHandle?.set(OPEN_MUSIC_SUBSCRIPTIONS, true)
            },
            onMoodsClick = { item ->
                if (item != null) {
                    // Navigate to browse screen with browseId and params for proper content fetching
                    val encodedParams = android.net.Uri.encode(item.endpoint.params ?: "")
                    navController.navigate("youtube_browse/${item.endpoint.browseId}?params=$encodedParams")
                } else {
                    navController.navigate("moodsAndGenres")
                }
            },
        )
    }

    composable("moodsAndGenres") {
        currentRoute.value = "moodsAndGenres"
        io.github.aedev.flow.ui.screens.music.MoodsAndGenresScreen(
            onBackClick = { navController.popBackStack() },
            onGenreClick = { item ->
                val encodedParams = android.net.Uri.encode(item.endpoint.params ?: "")
                navController.navigate("youtube_browse/${item.endpoint.browseId}?params=$encodedParams")
            },
        )
    }

    // Music Search Screen
    composable(
        route = "musicSearch?query={query}",
        arguments =
            listOf(
                navArgument("query") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { backStackEntry ->
        currentRoute.value = "musicSearch"

        val musicPlayerViewModel = sharedMusicPlayerViewModel()
        val initialQuery = backStackEntry.arguments?.getString("query")

        io.github.aedev.flow.ui.screens.music.MusicSearchScreen(
            initialQuery = initialQuery,
            onBackClick = { navController.popBackStack() },
            onTrackClick = { track, queue, source ->
                musicPlayerViewModel.loadAndPlayTrack(track, queue, source)
                onMusicStarted()
            },
            onAlbumClick = { albumId ->
                mediaNavigator.openAlbum(albumId)
            },
            onArtistClick = { channelId ->
                mediaNavigator.openArtist(channelId)
            },
            onChannelClick = mediaNavigator::openChannel,
            onPlaylistClick = { playlistId ->
                mediaNavigator.openMusicPlaylist(playlistId)
            },
            onMoodClick = { item ->
                val encodedParams = android.net.Uri.encode(item.endpoint.params ?: "")
                navController.navigate("youtube_browse/${item.endpoint.browseId}?params=$encodedParams")
            },
            onMoodsSeeAll = { navController.navigate("moodsAndGenres") },
        )
    }

    // Music Recognition (Shazam) Screen
    composable("musicRecognize") {
        currentRoute.value = "musicRecognize"

        val musicPlayerViewModel = sharedMusicPlayerViewModel()

        fun playRecognized(result: io.github.aedev.flow.data.recognition.RecognitionResult) {
            val track =
                io.github.aedev.flow.ui.screens.recognition.RecognitionViewModel
                    .toMusicTrack(result) ?: return
            musicPlayerViewModel.loadAndPlayTrack(track, listOf(track), "Recognized")
            onMusicStarted()
        }

        fun searchRecognized(
            title: String,
            artist: String,
        ) {
            val query =
                io.github.aedev.flow.ui.screens.recognition.RecognitionViewModel
                    .searchQueryFor(title, artist)
            navController.navigate("musicSearch?query=${android.net.Uri.encode(query)}")
        }

        io.github.aedev.flow.ui.screens.recognition.RecognitionScreen(
            onBackClick = { navController.popBackStack() },
            onHistoryClick = { navController.navigate("recognitionHistory") },
            onPlay = { result -> playRecognized(result) },
            onSearch = { result -> searchRecognized(result.title, result.artist) },
        )
    }

    // Music Recognition History Screen
    composable("recognitionHistory") {
        currentRoute.value = "recognitionHistory"

        val musicPlayerViewModel = sharedMusicPlayerViewModel()

        io.github.aedev.flow.ui.screens.recognition.RecognitionHistoryScreen(
            onBackClick = { navController.popBackStack() },
            onItemClick = { item ->
                val videoId = item.youtubeVideoId
                if (!videoId.isNullOrBlank()) {
                    val track =
                        MusicTrack(
                            videoId = videoId,
                            title = item.title,
                            artist = item.artist,
                            thumbnailUrl = item.coverArtHqUrl ?: item.coverArtUrl ?: "",
                            duration = 0,
                            album = item.album.orEmpty(),
                        )
                    musicPlayerViewModel.loadAndPlayTrack(track, listOf(track), "Recognized")
                    onMusicStarted()
                } else {
                    val query =
                        io.github.aedev.flow.ui.screens.recognition.RecognitionViewModel
                            .searchQueryFor(item.title, item.artist)
                    navController.navigate("musicSearch?query=${android.net.Uri.encode(query)}")
                }
            },
        )
    }

    // YouTube Browse Screen (for mood/genre content)
    composable(
        route = "youtube_browse/{browseId}?params={params}",
        arguments =
            listOf(
                navArgument("browseId") { type = NavType.StringType },
                navArgument("params") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) {
        currentRoute.value = "youtube_browse"

        val musicPlayerViewModel = sharedMusicPlayerViewModel()

        io.github.aedev.flow.ui.screens.music.YouTubeBrowseScreen(
            onBackClick = { navController.popBackStack() },
            onSongClick = { song ->
                val track =
                    MusicTrack(
                        videoId = song.id,
                        title = song.title,
                        artist = song.artists.joinToString(", ") { it.name },
                        thumbnailUrl = song.thumbnail,
                        duration = song.duration ?: 0,
                        album = song.album?.name ?: "",
                        channelId = song.artists.firstOrNull()?.id ?: "",
                        artists = song.artists.map { MusicArtist(it.name, it.id) },
                    )
                musicPlayerViewModel.loadAndPlayTrack(track, emptyList())
                onMusicStarted()
            },
            onAlbumClick = { albumId ->
                mediaNavigator.openAlbum(albumId)
            },
            onArtistClick = { channelId ->
                mediaNavigator.openArtist(channelId)
            },
            onPlaylistClick = { playlistId ->
                mediaNavigator.openMusicPlaylist(playlistId)
            },
        )
    }

    // Artist Page
    composable(MUSIC_ARTIST_ROUTE_PATTERN) { backStackEntry ->
        val channelId = backStackEntry.arguments?.getString("channelId") ?: return@composable
        val musicPlayerViewModel = sharedMusicPlayerViewModel()
        io.github.aedev.flow.ui.screens.music.ArtistRoute(
            channelId = channelId,
            onBackClick = { navController.popBackStack() },
            onTrackClick = { track, queue ->
                musicPlayerViewModel.loadAndPlayTrack(track, queue)
                onMusicStarted()
            },
            onAlbumClick = mediaNavigator::openAlbum,
            onArtistClick = mediaNavigator::openArtist,
            onSeeAllClick = { browseId, params ->
                val encodedParams = if (params != null) android.net.Uri.encode(params) else null
                navController.navigate("artistItems/$channelId/$browseId?params=$encodedParams")
            },
            onPlayWatchPlaylist = { playlistId, params ->
                musicPlayerViewModel.playWatchPlaylist(playlistId, params).also { played ->
                    if (played) onMusicStarted()
                }
            },
            onStartRadio = { track ->
                musicPlayerViewModel.startRadio(track)
                onMusicStarted()
            },
        )
    }

    // Artist Items Page (View All)
    composable(
        "artistItems/{channelId}/{browseId}?params={params}",
        arguments =
            listOf(
                navArgument("browseId") { type = NavType.StringType },
                navArgument("params") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("channelId") { type = NavType.StringType },
            ),
    ) { backStackEntry ->
        val browseId = backStackEntry.arguments?.getString("browseId") ?: return@composable
        val params = backStackEntry.arguments?.getString("params")
        // channelId is available if needed contextually

        val musicViewModel: MusicViewModel =
            io.github.aedev.flow.ui.screens.music
                .sharedMusicViewModel()
        val musicPlayerViewModel = sharedMusicPlayerViewModel()

        io.github.aedev.flow.ui.screens.music.ArtistItemsScreen(
            browseId = browseId,
            params = params,
            onBackClick = { navController.popBackStack() },
            viewModel = musicViewModel,
            onTrackClick = { songItem ->
                val track =
                    MusicTrack(
                        videoId = songItem.id,
                        title = songItem.title,
                        artist = songItem.artists.joinToString(", ") { it.name },
                        thumbnailUrl = songItem.thumbnail,
                        duration = songItem.duration ?: 0,
                    )
                musicPlayerViewModel.loadAndPlayTrack(track, listOf(track))
                onMusicStarted()
            },
            onAlbumClick = { albumId ->
                mediaNavigator.openAlbum(albumId)
            },
            onArtistClick = { id ->
                mediaNavigator.openArtist(id)
            },
            onPlaylistClick = { playlistId ->
                mediaNavigator.openMusicPlaylist(playlistId)
            },
        )
    }

    // Music Playlist Page
    composable(MUSIC_PLAYLIST_ROUTE_PATTERN) { backStackEntry ->
        if (backStackEntry.arguments?.getString("playlistId") == null) return@composable
        val musicPlayerViewModel = sharedMusicPlayerViewModel()
        io.github.aedev.flow.ui.screens.music.collection.MusicCollectionScreen(
            callbacks =
                io.github.aedev.flow.ui.screens.music.collection.MusicCollectionCallbacks(
                    onBackClick = { navController.popBackStack() },
                    onTrackClick = { track, queue, sourceName ->
                        musicPlayerViewModel.loadAndPlayTrack(track, queue, sourceName)
                        onMusicStarted()
                    },
                    onArtistClick = { channelId -> mediaNavigator.openArtist(channelId) },
                    onCollectionClick = { mediaNavigator.openMusicPlaylist(it) },
                    onPlayNext = musicPlayerViewModel::playNext,
                    onAddToQueue = musicPlayerViewModel::addToQueue,
                ),
        )
    }
}
