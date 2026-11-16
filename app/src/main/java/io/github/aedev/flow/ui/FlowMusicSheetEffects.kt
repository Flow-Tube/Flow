package io.github.aedev.flow.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.ui.components.musicplayer.sheet.MusicPlayerSheetState

/**
 * Keeps the music sheet in step with the rest of the shell: which song is loaded, whether a video
 * took over the mini player, the restored-session setting, and requests to open the player.
 */
@Composable
internal fun FlowMusicSheetEffects(
    musicPlayerSheetState: MusicPlayerSheetState,
    currentMusicTrack: MusicTrack?,
    activeVideoId: String?,
    currentRoute: String,
    showRestoredMusicMiniPlayer: Boolean?,
    openMusicPlayerRequest: Int,
    suppressMusicMiniAfterVideo: (Boolean) -> Unit,
    playerVisible: () -> Boolean,
    collapseVideoPlayer: () -> Unit,
) {
    var handledMusicPlayerRequest by remember { mutableIntStateOf(0) }

    LaunchedEffect(activeVideoId) {
        if (activeVideoId != null) {
            suppressMusicMiniAfterVideo(true)
        }
    }

    LaunchedEffect(currentMusicTrack?.videoId) {
        if (currentMusicTrack == null) {
            suppressMusicMiniAfterVideo(false)
        }
    }

    LaunchedEffect(currentRoute) {
        if (currentRoute == "musicPlayer") {
            suppressMusicMiniAfterVideo(false)
        }
    }

    val isRestoredMusicQueue by EnhancedMusicPlayerManager.isRestoredQueue.collectAsStateWithLifecycle()
    val hidesRestoredMusic = hidesRestoredMusicMiniPlayer(showRestoredMusicMiniPlayer, isRestoredMusicQueue)
    LaunchedEffect(currentMusicTrack, showRestoredMusicMiniPlayer, hidesRestoredMusic) {
        if (currentMusicTrack != null &&
            showRestoredMusicMiniPlayer != null &&
            !hidesRestoredMusic &&
            musicPlayerSheetState.isDismissed
        ) {
            musicPlayerSheetState.collapse()
        } else if (currentMusicTrack == null) {
            musicPlayerSheetState.dismiss()
        }
    }

    LaunchedEffect(hidesRestoredMusic) {
        if (hidesRestoredMusic && !musicPlayerSheetState.isExpanded) {
            musicPlayerSheetState.dismiss()
        }
    }

    LaunchedEffect(openMusicPlayerRequest, currentMusicTrack?.videoId) {
        if (openMusicPlayerRequest > handledMusicPlayerRequest && currentMusicTrack != null) {
            handledMusicPlayerRequest = openMusicPlayerRequest
            suppressMusicMiniAfterVideo(false)
            if (playerVisible()) {
                collapseVideoPlayer()
            }
            musicPlayerSheetState.expand()
        }
    }
}
