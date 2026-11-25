package io.github.aedev.flow.ui.tv.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.toVideo
import io.github.aedev.flow.ui.components.layout.navigation.FlowTab
import io.github.aedev.flow.ui.components.shared.MediaKind
import io.github.aedev.flow.ui.screens.music.ArtistItemsScreen
import io.github.aedev.flow.ui.screens.music.ArtistRoute
import io.github.aedev.flow.ui.screens.music.EnhancedMusicScreen
import io.github.aedev.flow.ui.screens.music.MoodsAndGenresScreen
import io.github.aedev.flow.ui.screens.music.MusicPlayerViewModel
import io.github.aedev.flow.ui.screens.music.YouTubeBrowseScreen
import io.github.aedev.flow.ui.screens.music.collection.MusicCollectionCallbacks
import io.github.aedev.flow.ui.screens.music.collection.MusicCollectionScreen
import io.github.aedev.flow.ui.screens.music.convertSongToMusicTrack
import io.github.aedev.flow.ui.tv.components.TvScreenFrame

/** Music and everything reached from it: artists and their sections, albums and playlists, moods. */
internal fun NavGraphBuilder.tvMusicRoutes(
    navController: NavHostController,
    musicPlayerViewModel: MusicPlayerViewModel,
    onPlayVideo: (Video) -> Unit,
    onSelectRoot: (String) -> Unit,
) {
    composable(FlowTab.Music.route) {
        TvScreenFrame {
            EnhancedMusicScreen(
                onSongClick = musicPlayerViewModel::loadAndPlayTrack,
                onVideoClick = { track -> onPlayVideo(track.toVideo()) },
                onArtistClick = { navController.navigate(TvRoutes.musicArtist(it)) },
                onSearchClick = { onSelectRoot(FlowTab.Search.route) },
                onAlbumClick = { navController.navigate(TvRoutes.musicCollection(it)) },
                onMoodsClick = { item -> navController.navigate(item?.let(TvRoutes::musicBrowse) ?: TvRoutes.MOODS) },
                onPlaylistClick = { navController.navigate(TvRoutes.musicCollection(it)) },
                onAllPlaylistsClick = { navController.navigate(TvRoutes.playlists(MediaKind.Music)) },
                onAllSubscriptionsClick = { onSelectRoot(FlowTab.Subscriptions.route) },
                showRecognize = false,
            )
        }
    }
    composable(
        route = TvRoutes.MUSIC_ARTIST,
        arguments = listOf(navArgument(TvRoutes.MUSIC_ARTIST_ARG) { type = NavType.StringType }),
    ) { entry ->
        val artistChannelId = entry.arguments?.getString(TvRoutes.MUSIC_ARTIST_ARG).orEmpty()
        TvScreenFrame {
            ArtistRoute(
                channelId = artistChannelId,
                onBackClick = { navController.popBackStack() },
                onTrackClick = { track, queue -> musicPlayerViewModel.loadAndPlayTrack(track, queue) },
                onAlbumClick = { navController.navigate(TvRoutes.musicCollection(it)) },
                onArtistClick = { navController.navigate(TvRoutes.musicArtist(it)) },
                onSeeAllClick = { browseId, params -> navController.navigate(TvRoutes.artistItems(artistChannelId, browseId, params)) },
                onPlayWatchPlaylist = musicPlayerViewModel::playWatchPlaylist,
                onStartRadio = musicPlayerViewModel::startRadio,
            )
        }
    }
    composable(
        route = TvRoutes.ARTIST_ITEMS,
        arguments =
            listOf(
                navArgument(TvRoutes.ARTIST_ITEMS_CHANNEL_ARG) { type = NavType.StringType },
                navArgument(TvRoutes.ARTIST_ITEMS_BROWSE_ARG) { type = NavType.StringType },
                navArgument(TvRoutes.ARTIST_ITEMS_PARAMS_ARG) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { entry ->
        val browseId = entry.arguments?.getString(TvRoutes.ARTIST_ITEMS_BROWSE_ARG) ?: return@composable
        TvScreenFrame {
            ArtistItemsScreen(
                browseId = browseId,
                params = entry.arguments?.getString(TvRoutes.ARTIST_ITEMS_PARAMS_ARG),
                onBackClick = { navController.popBackStack() },
                onTrackClick = { song ->
                    val track = convertSongToMusicTrack(song)
                    musicPlayerViewModel.loadAndPlayTrack(track, listOf(track))
                },
                onAlbumClick = { navController.navigate(TvRoutes.musicCollection(it)) },
                onArtistClick = { navController.navigate(TvRoutes.musicArtist(it)) },
                onPlaylistClick = { navController.navigate(TvRoutes.musicCollection(it)) },
            )
        }
    }
    composable(
        route = TvRoutes.MUSIC_COLLECTION,
        arguments = listOf(navArgument(TvRoutes.MUSIC_COLLECTION_ARG) { type = NavType.StringType }),
    ) {
        TvScreenFrame {
            MusicCollectionScreen(
                callbacks =
                    MusicCollectionCallbacks(
                        onBackClick = { navController.popBackStack() },
                        onTrackClick = musicPlayerViewModel::loadAndPlayTrack,
                        onArtistClick = { navController.navigate(TvRoutes.musicArtist(it)) },
                        onCollectionClick = { navController.navigate(TvRoutes.musicCollection(it)) },
                        onPlayNext = musicPlayerViewModel::playNext,
                        onAddToQueue = musicPlayerViewModel::addToQueue,
                    ),
            )
        }
    }
    composable(TvRoutes.MOODS) {
        TvScreenFrame {
            MoodsAndGenresScreen(
                onBackClick = { navController.popBackStack() },
                onGenreClick = { navController.navigate(TvRoutes.musicBrowse(it)) },
            )
        }
    }
    composable(
        route = TvRoutes.MUSIC_BROWSE,
        arguments =
            listOf(
                navArgument(TvRoutes.MUSIC_BROWSE_ID_ARG) { type = NavType.StringType },
                navArgument(TvRoutes.MUSIC_BROWSE_PARAMS_ARG) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) {
        TvScreenFrame {
            YouTubeBrowseScreen(
                onBackClick = { navController.popBackStack() },
                onSongClick = { song -> musicPlayerViewModel.loadAndPlayTrack(convertSongToMusicTrack(song), emptyList()) },
                onAlbumClick = { navController.navigate(TvRoutes.musicCollection(it)) },
                onArtistClick = { navController.navigate(TvRoutes.musicArtist(it)) },
                onPlaylistClick = { navController.navigate(TvRoutes.musicCollection(it)) },
            )
        }
    }
}
