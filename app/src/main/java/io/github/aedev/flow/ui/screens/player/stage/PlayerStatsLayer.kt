package io.github.aedev.flow.ui.screens.player.stage

import android.content.ClipData
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.aedev.flow.player.stats.PlayerStatsFormatter
import io.github.aedev.flow.player.stats.PlayerStatsSnapshot
import io.github.aedev.flow.ui.components.videoplayer.overlay.PlayerStatsOverlay
import io.github.aedev.flow.ui.screens.player.PlayerStatsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val STATS_REFRESH_MS = 1_000L

/**
 * The stats panel and its one read a second. The read runs only while the panel is open, placed and
 * started, and only keeps going while the video plays or buffers: a paused player's numbers stand
 * still, while a stall is when the buffer and bandwidth matter most.
 */
@Composable
internal fun BoxScope.PlayerStatsLayer(session: VideoPlayerStageSession) {
    val screenState = session.screenState
    if (!screenState.showStatsForNerds) return

    val viewModel = hiltViewModel<PlayerStatsViewModel>()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val videoId = session.video.id
    val client = session.uiState.streamClient
    val isRunning = session.playerState.isPlaying || session.playerState.isBuffering
    var snapshot by remember { mutableStateOf<PlayerStatsSnapshot?>(null) }

    LaunchedEffect(videoId, client, isRunning, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshot = viewModel.read(videoId, client)
            while (isRunning) {
                delay(STATS_REFRESH_MS)
                snapshot = viewModel.read(videoId, client)
            }
        }
    }

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    snapshot?.let { current ->
        PlayerStatsOverlay(
            lines = PlayerStatsFormatter.lines(current),
            onCopy = { text ->
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(videoId, text))) }
            },
            onClose = { screenState.showStatsForNerds = false },
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}
