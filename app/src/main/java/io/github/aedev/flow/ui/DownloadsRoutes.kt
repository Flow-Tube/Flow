package io.github.aedev.flow.ui

import androidx.compose.runtime.MutableState
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.aedev.flow.R
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.ui.components.layout.navigation.DOWNLOADS_ROUTE
import io.github.aedev.flow.ui.components.layout.navigation.DOWNLOADS_TAB_ROUTE
import io.github.aedev.flow.ui.components.layout.navigation.MediaNavigator
import io.github.aedev.flow.ui.screens.library.DownloadsScreen
import io.github.aedev.flow.ui.screens.music.sharedMusicPlayerViewModel
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModel

/** The downloads page, opened over a screen with a back arrow, and as the Downloads tab without one. */
internal fun NavGraphBuilder.downloadsRoutes(
    navController: NavHostController,
    mediaNavigator: MediaNavigator,
    currentRoute: MutableState<String>,
    playerViewModel: VideoPlayerViewModel,
    onMusicStarted: () -> Unit,
) {
    listOf(DOWNLOADS_ROUTE, DOWNLOADS_TAB_ROUTE).forEach { route ->
        composable(route) {
            currentRoute.value = route
            val musicPlayerViewModel = sharedMusicPlayerViewModel()
            val downloadsTitle = stringResource(R.string.library_downloads_label)
            DownloadsScreen(
                onBackClick = if (route == DOWNLOADS_ROUTE) ({ navController.popBackStack() }) else null,
                onVideoClick = { videos, index ->
                    val videoList = videos.map { it.video }
                    playerViewModel.playPlaylist(videoList, index, downloadsTitle)
                    GlobalPlayerState.setCurrentVideo(videoList[index])
                },
                onMusicClick = { tracks, index ->
                    val musicTracks = tracks.map { it.track }
                    musicPlayerViewModel.loadAndPlayTrack(musicTracks[index], musicTracks, downloadsTitle)
                    onMusicStarted()
                },
                onOpenCollection = { summary ->
                    if (summary.collection.kind.isMusic) {
                        mediaNavigator.openMusicPlaylist(summary.collection.id)
                    } else {
                        navController.navigate("playlist/${summary.collection.id}")
                    }
                },
                onHomeClick = {
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = true }
                    }
                },
            )
        }
    }
}
