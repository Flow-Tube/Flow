package io.github.aedev.flow.ui.components.musicplayer.mini

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.MiniBarButton
import io.github.aedev.flow.data.local.MiniLongPress
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.RepeatMode
import io.github.aedev.flow.ui.components.music.sheet.MusicQuickActionsSheet
import io.github.aedev.flow.ui.components.music.sheet.SaveSongSheet
import io.github.aedev.flow.ui.components.musicplayer.sheet.AudioSettingsSheet
import io.github.aedev.flow.ui.components.musicplayer.sheet.MusicPlayerSheetState
import io.github.aedev.flow.ui.components.musicplayer.sheet.PlayerPanel
import io.github.aedev.flow.ui.components.shared.MediaPlayPauseButton
import io.github.aedev.flow.ui.components.shared.MediaSleepTimerSheet
import io.github.aedev.flow.ui.screens.music.MusicPlayerViewModel
import io.github.aedev.flow.ui.screens.music.sharedMusicPlayerViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private const val SKIP_MS = 10_000L

/**
 * What the mini player's buttons and its long press can reach beyond the transport: the player's
 * view model, the sheets the bar opens itself, and the full player's panels.
 */
@Stable
internal class MiniBarActions(
    val viewModel: MusicPlayerViewModel,
    private val sheetState: MusicPlayerSheetState,
    val onClose: () -> Unit,
) {
    var saveOpen by mutableStateOf(false)
    var sleepTimerOpen by mutableStateOf(false)
    var optionsOpen by mutableStateOf(false)
    var audioOpen by mutableStateOf(false)

    fun run(button: MiniBarButton) {
        when (button) {
            MiniBarButton.PREVIOUS -> EnhancedMusicPlayerManager.playPrevious()
            MiniBarButton.PLAY_PAUSE -> EnhancedMusicPlayerManager.togglePlayPause()
            MiniBarButton.NEXT -> EnhancedMusicPlayerManager.playNext()
            MiniBarButton.LIKE -> viewModel.toggleLike()
            MiniBarButton.ADD_TO_PLAYLIST -> saveOpen = true
            MiniBarButton.SHUFFLE -> viewModel.toggleShuffle()
            MiniBarButton.REPEAT -> viewModel.toggleRepeat()
            MiniBarButton.QUEUE -> sheetState.expandTo(PlayerPanel.QUEUE)
            MiniBarButton.LYRICS -> sheetState.expandTo(PlayerPanel.LYRICS)
            MiniBarButton.SLEEP_TIMER -> sleepTimerOpen = true
            MiniBarButton.BACK_10 -> seekBy(-SKIP_MS)
            MiniBarButton.FORWARD_10 -> seekBy(SKIP_MS)
            MiniBarButton.CLOSE -> onClose()
        }
    }

    /** The long press, or null when it does nothing, so the bar keeps a plain click. */
    fun longPress(press: MiniLongPress): (() -> Unit)? =
        when (press) {
            MiniLongPress.NOTHING -> null
            MiniLongPress.OPTIONS -> ({ optionsOpen = true })
            MiniLongPress.LIKE -> ({ viewModel.toggleLike() })
            MiniLongPress.QUEUE -> ({ sheetState.expandTo(PlayerPanel.QUEUE) })
        }

    private fun seekBy(deltaMs: Long) {
        EnhancedMusicPlayerManager.seekTo((EnhancedMusicPlayerManager.getCurrentPosition() + deltaMs).coerceAtLeast(0L))
    }
}

@Composable
internal fun rememberMiniBarActions(
    sheetState: MusicPlayerSheetState,
    onClose: () -> Unit,
    viewModel: MusicPlayerViewModel = sharedMusicPlayerViewModel(),
): MiniBarActions = remember(sheetState, viewModel, onClose) { MiniBarActions(viewModel, sheetState, onClose) }

/** The sheets the mini player opens without the full player. */
@Composable
internal fun MiniBarActionSheets(
    actions: MiniBarActions,
    track: MusicTrack,
) {
    if (actions.saveOpen) SaveSongSheet(track = track, onDismiss = { actions.saveOpen = false })
    if (actions.sleepTimerOpen) MediaSleepTimerSheet(onDismiss = { actions.sleepTimerOpen = false })
    if (actions.audioOpen) AudioSettingsSheet(onDismiss = { actions.audioOpen = false })
    if (actions.optionsOpen) {
        MusicQuickActionsSheet(
            track = track,
            onDismiss = { actions.optionsOpen = false },
            onAudioEffectsClick = { actions.audioOpen = true },
            onSleepTimerClick = { actions.sleepTimerOpen = true },
        )
    }
}

/**
 * The mini player's buttons in the user's order. Without [actions] (a host such as TV) only the
 * transport buttons work. Like, shuffle and repeat read their state here, only while shown.
 */
@Composable
internal fun MiniBarButtons(
    buttons: List<MiniBarButton>,
    isPlaying: Boolean,
    isBuffering: Boolean,
    actions: MiniBarActions?,
) {
    val haptics = LocalHapticFeedback.current
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        buttons.forEach { button ->
            when (button) {
                MiniBarButton.PLAY_PAUSE -> {
                    MediaPlayPauseButton(
                        isPlaying = isPlaying,
                        isBuffering = isBuffering,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            EnhancedMusicPlayerManager.togglePlayPause()
                        },
                    )
                }

                MiniBarButton.LIKE -> {
                    if (actions != null) {
                        val liked by remember(actions) {
                            actions.viewModel.uiState
                                .map { it.isLiked }
                                .distinctUntilChanged()
                        }.collectAsState(initial = false)
                        MiniBarIcon(
                            icon = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            label = stringResource(if (liked) R.string.unlike else R.string.like),
                            active = liked,
                            onClick = { actions.run(button) },
                        )
                    }
                }

                MiniBarButton.SHUFFLE -> {
                    if (actions != null) {
                        val on by EnhancedMusicPlayerManager.shuffleEnabled.collectAsState()
                        MiniBarIcon(Icons.Rounded.Shuffle, stringResource(R.string.shuffle), active = on, onClick = { actions.run(button) })
                    }
                }

                MiniBarButton.REPEAT -> {
                    if (actions != null) {
                        val mode by EnhancedMusicPlayerManager.repeatMode.collectAsState()
                        MiniBarIcon(
                            icon = if (mode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            label = stringResource(R.string.repeat),
                            active = mode != RepeatMode.OFF,
                            onClick = { actions.run(button) },
                        )
                    }
                }

                MiniBarButton.PREVIOUS, MiniBarButton.NEXT -> {
                    MiniBarIcon(button.icon(), stringResource(button.labelRes()), onClick = {
                        if (button ==
                            MiniBarButton.NEXT
                        ) {
                            EnhancedMusicPlayerManager.playNext()
                        } else {
                            EnhancedMusicPlayerManager.playPrevious()
                        }
                    })
                }

                else -> {
                    if (actions != null) MiniBarIcon(button.icon(), stringResource(button.labelRes()), onClick = { actions.run(button) })
                }
            }
        }
    }
}

@Composable
private fun MiniBarIcon(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The icon each button draws; play/pause, like and repeat change theirs with the state they show. */
internal fun MiniBarButton.icon(): ImageVector =
    when (this) {
        MiniBarButton.PREVIOUS -> Icons.Rounded.SkipPrevious
        MiniBarButton.PLAY_PAUSE -> Icons.Rounded.PlayArrow
        MiniBarButton.NEXT -> Icons.Rounded.SkipNext
        MiniBarButton.LIKE -> Icons.Rounded.FavoriteBorder
        MiniBarButton.ADD_TO_PLAYLIST -> Icons.AutoMirrored.Rounded.PlaylistAdd
        MiniBarButton.SHUFFLE -> Icons.Rounded.Shuffle
        MiniBarButton.REPEAT -> Icons.Rounded.Repeat
        MiniBarButton.QUEUE -> Icons.AutoMirrored.Rounded.QueueMusic
        MiniBarButton.LYRICS -> Icons.Rounded.Lyrics
        MiniBarButton.SLEEP_TIMER -> Icons.Rounded.Bedtime
        MiniBarButton.BACK_10 -> Icons.Rounded.Replay10
        MiniBarButton.FORWARD_10 -> Icons.Rounded.Forward10
        MiniBarButton.CLOSE -> Icons.Rounded.Close
    }

internal fun MiniBarButton.labelRes(): Int =
    when (this) {
        MiniBarButton.PREVIOUS -> R.string.previous
        MiniBarButton.PLAY_PAUSE -> R.string.mini_button_play_pause
        MiniBarButton.NEXT -> R.string.next
        MiniBarButton.LIKE -> R.string.like
        MiniBarButton.ADD_TO_PLAYLIST -> R.string.add_to_playlist
        MiniBarButton.SHUFFLE -> R.string.shuffle
        MiniBarButton.REPEAT -> R.string.repeat
        MiniBarButton.QUEUE -> R.string.mini_button_queue
        MiniBarButton.LYRICS -> R.string.mini_button_lyrics
        MiniBarButton.SLEEP_TIMER -> R.string.mini_button_sleep_timer
        MiniBarButton.BACK_10 -> R.string.mini_button_back_10
        MiniBarButton.FORWARD_10 -> R.string.mini_button_forward_10
        MiniBarButton.CLOSE -> R.string.mini_button_close
    }
