package io.github.aedev.flow.ui.components.musicplayer.full

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.player.PlayerHardwareController
import io.github.aedev.flow.ui.components.shared.MediaKeyAction
import io.github.aedev.flow.ui.components.shared.mediaKeyboardShortcuts
import io.github.aedev.flow.ui.screens.music.MusicPlayerViewModel

/** Keyboard shortcuts for the open music player. Seeks step by the double-tap seek setting, as on video. */
@Composable
internal fun Modifier.musicPlayerKeyboardShortcuts(
    enabled: Boolean,
    viewModel: MusicPlayerViewModel,
): Modifier {
    val context = LocalContext.current
    val playerPreferences = remember(context) { PlayerPreferences(context) }
    val seekSeconds by playerPreferences.doubleTapSeekSeconds.collectAsState(initial = 10)

    return mediaKeyboardShortcuts(enabled) { action ->
        val duration = viewModel.uiState.value.duration
        when (action) {
            MediaKeyAction.TogglePlayback -> {
                viewModel.togglePlayPause()
                true
            }

            MediaKeyAction.Play -> {
                viewModel.play()
                true
            }

            MediaKeyAction.Pause -> {
                viewModel.pause()
                true
            }

            MediaKeyAction.SeekBack -> {
                viewModel.seekTo(viewModel.currentPositionMs.value - seekSeconds * 1000L)
                true
            }

            MediaKeyAction.SeekForward -> {
                viewModel.seekTo(viewModel.currentPositionMs.value + seekSeconds * 1000L)
                true
            }

            MediaKeyAction.VolumeUp -> {
                PlayerHardwareController.adjustVolume(context, raise = true)
            }

            MediaKeyAction.VolumeDown -> {
                PlayerHardwareController.adjustVolume(context, raise = false)
            }

            MediaKeyAction.Next -> {
                viewModel.skipToNext()
                true
            }

            MediaKeyAction.Previous -> {
                viewModel.skipToPrevious()
                true
            }

            is MediaKeyAction.JumpTo -> {
                if (duration > 0) viewModel.seekTo(duration * action.tenths / 10)
                duration > 0
            }

            else -> {
                false
            }
        }
    }
}
