package io.github.aedev.flow.ui

import android.net.Uri
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.toMusicTrack
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.ui.screens.music.sharedMusicPlayerViewModel
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModel
import io.github.aedev.flow.ui.screens.widgets.WidgetPlaybackViewModel

internal const val ON_REPEAT_SHUFFLE_ROUTE = "onRepeatShuffle"
internal const val WIDGET_PLAYLIST_ROUTE = "widgetPlaylist/{playlistId}?shuffle={shuffle}&start={start}"

internal fun widgetPlaylistRoute(
    playlistId: String,
    shuffle: Boolean,
    startVideoId: String? = null,
): String =
    "widgetPlaylist/${Uri.encode(playlistId)}?shuffle=$shuffle" +
        startVideoId?.let { "&start=${Uri.encode(it)}" }.orEmpty()

/** Routes a home-screen widget opens to start playback; each plays, then leaves at once. */
internal fun NavGraphBuilder.widgetPlaybackRoutes(
    navController: NavHostController,
    defaultStartRoute: String,
    playerViewModel: VideoPlayerViewModel,
    onMusicStarted: () -> Unit,
) {
    composable(ON_REPEAT_SHUFFLE_ROUTE) {
        val musicPlayerViewModel = sharedMusicPlayerViewModel()
        val sourceName = stringResource(R.string.widget_on_repeat)
        LaunchedEffect(Unit) {
            // The music player stays hidden while a video is loaded, so it closes first.
            if (playerViewModel.uiState.value.cachedVideo != null) GlobalPlayerState.requestDismiss()
            musicPlayerViewModel.shuffleOnRepeat(sourceName)
            onMusicStarted()
            withFrameNanos { }
            navController.popTransientRouteOrNavigateStart(defaultStartRoute)
        }
    }

    composable(
        route = WIDGET_PLAYLIST_ROUTE,
        arguments =
            listOf(
                navArgument("playlistId") { type = NavType.StringType },
                navArgument("shuffle") {
                    type = NavType.BoolType
                    defaultValue = false
                },
                navArgument("start") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { entry ->
        val playlistId = entry.arguments?.getString("playlistId").orEmpty()
        val shuffle = entry.arguments?.getBoolean("shuffle") ?: false
        val startVideoId = entry.arguments?.getString("start")
        val musicPlayerViewModel = sharedMusicPlayerViewModel()
        val playlists: WidgetPlaybackViewModel = hiltViewModel()
        LaunchedEffect(playlistId, shuffle, startVideoId) {
            val (playlist, videos) = playlists.playlist(playlistId)
            if (playlist != null && videos.isNotEmpty()) {
                val start = videos.indexOfFirst { it.id == startVideoId }.coerceAtLeast(0)
                // A tapped row decides the player, as on the playlist page: Watch later mixes songs and videos.
                val music = if (startVideoId != null) videos[start].isMusic else playlist.isMusic
                if (music) {
                    if (playerViewModel.uiState.value.cachedVideo != null) GlobalPlayerState.requestDismiss()
                    val tracks = videos.filter { it.isMusic }.map { it.toMusicTrack() }.let { if (shuffle) it.shuffled() else it }
                    val first = if (startVideoId != null) videos[start].toMusicTrack() else tracks.first()
                    musicPlayerViewModel.loadAndPlayTrack(first, tracks, playlist.name)
                    onMusicStarted()
                } else {
                    playerViewModel.playPlaylist(videos, start, playlist.name, shuffle)
                }
            }
            withFrameNanos { }
            navController.popTransientRouteOrNavigateStart(defaultStartRoute)
        }
    }
}

internal fun NavHostController.popTransientRouteOrNavigateStart(defaultStartRoute: String) {
    if (previousBackStackEntry != null) {
        popBackStack()
    } else {
        navigate(defaultStartRoute) {
            launchSingleTop = true
        }
    }
}
