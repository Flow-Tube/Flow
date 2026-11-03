package io.github.aedev.flow.ui.screens.player.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.ui.components.videoplayer.MiniPlayerControls
import io.github.aedev.flow.ui.components.videoplayer.MiniPlayerCountdown
import io.github.aedev.flow.ui.theme.PlayerScrim
import io.github.aedev.flow.ui.theme.PlayerScrimContent

/** The controls the collapsed player draws over the mini video surface, or the autoplay countdown while one runs. */
@Composable
internal fun MiniPlayerSlot(
    session: VideoPlayerStageSession,
    miniPlayerShowSkipControls: Boolean,
    miniPlayerShowNextPrevControls: Boolean,
    onClose: () -> Unit,
) {
    val screenState = session.screenState
    val playerState = session.playerState
    val playerUiState = session.uiState
    val playerViewModel = session.viewModel
    val playerSheetState = session.sheetState
    val playerManager = remember { EnhancedPlayerManager.getInstance() }
    val countdown by playerManager.autoplayCountdown.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        val currentSizeScale = playerSheetState.miniSizeScale.targetValue
        if (countdown.isActive) {
            MiniPlayerCountdown(
                state = countdown,
                sizeScale = currentSizeScale,
                onPlayNext = playerManager::skipAutoplayCountdown,
                onCancel = playerManager::cancelAutoplayCountdown,
            )
            return@Box
        }
        MiniPlayerControls(
            playerState = playerState,
            showSkipControls = miniPlayerShowSkipControls,
            showNextPrevControls = miniPlayerShowNextPrevControls,
            sizeScale = currentSizeScale,
            onPlayPause = {
                if (playerUiState.isRestoredSession) {
                    playerViewModel.resumeRestoredSession(stayMini = true)
                } else if (playerState.hasEnded) {
                    playerManager.replay()
                    playerViewModel.ensureNotificationServiceRunning()
                } else if (playerState.playWhenReady) {
                    playerManager.pause()
                } else {
                    playerManager.play()
                    playerViewModel.ensureNotificationServiceRunning()
                }
            },
            onSkipForward = {
                playerManager.seekTo(screenState.currentPosition + 10000)
            },
            onSkipBack = {
                playerManager.seekTo(screenState.currentPosition - 10000)
            },
            onNext = {
                playerViewModel.playNext()
            },
            onPrevious = {
                playerViewModel.playPrevious()
            },
            onClose = onClose,
        )
        if (playerUiState.isRestoredSession) {
            Text(
                text = stringResource(R.string.player_mini_player_continue_watching_label),
                style = MaterialTheme.typography.labelSmall,
                color = PlayerScrimContent,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 4.dp)
                        .background(
                            PlayerScrim.copy(alpha = 0.73f),
                            RoundedCornerShape(3.dp),
                        ).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
