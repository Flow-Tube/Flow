package io.github.aedev.flow.ui.screens.player.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModel

/**
 * Refreshes a live video's counts while its watch page is the thing on screen.
 *
 * [onScreen] is false when the page is collapsed to the mini player, fullscreen or in
 * picture-in-picture; RESUMED also drops it while the app is backgrounded or the screen is off,
 * since the counts are only worth a request when someone can read them.
 */
@Composable
internal fun LiveCountsRefreshEffect(
    videoId: String,
    refreshable: Boolean,
    onScreen: Boolean,
    viewModel: VideoPlayerViewModel,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(videoId, refreshable, onScreen, lifecycleOwner) {
        if (!refreshable || !onScreen) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refreshLiveCounts(videoId)
        }
    }
}
