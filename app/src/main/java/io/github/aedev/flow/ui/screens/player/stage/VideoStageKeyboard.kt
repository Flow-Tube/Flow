package io.github.aedev.flow.ui.screens.player.stage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.PlayerHardwareController
import io.github.aedev.flow.ui.components.shared.MediaKeyAction
import io.github.aedev.flow.ui.components.shared.mediaKeyboardShortcuts
import io.github.aedev.flow.ui.components.shared.playbackSpeedOptions
import io.github.aedev.flow.ui.components.shared.stepPlaybackSpeed
import io.github.aedev.flow.ui.components.videoplayer.controls.PlayerControlActions
import io.github.aedev.flow.ui.screens.player.saveChosenPlaybackSpeed
import io.github.aedev.flow.ui.screens.player.state.PlayerSheet

/**
 * Keyboard shortcuts for the expanded video player, through the same actions as its buttons. They
 * stand down under the sheets that hold a text field, which draw inside the player's window.
 */
@UnstableApi
@Composable
internal fun Modifier.videoStageKeyboardShortcuts(
    session: VideoPlayerStageSession,
    actions: PlayerControlActions,
    hasPrevious: Boolean,
    hasNext: Boolean,
): Modifier {
    val screenState = session.screenState
    val playerState = session.playerState
    val playerPreferences = remember(session.context) { PlayerPreferences(session.context) }
    val customSpeedsEnabled by playerPreferences.customSpeedsEnabled.collectAsState(initial = false)
    val customSpeedPresets by playerPreferences.customSpeedPresets.collectAsState(initial = "")
    val sheet = screenState.activeSheet
    val enabled = !screenState.isTouchLocked && sheet != PlayerSheet.Transcript && sheet != PlayerSheet.SleepTimer

    fun seekBy(forward: Boolean) {
        val manager = EnhancedPlayerManager.getInstance()
        val player = manager.getPlayer()
        val step = session.prefs.doubleTapSeekSeconds * 1000L
        val position = player?.currentPosition ?: screenState.currentPosition
        val target = if (forward) (position + step).coerceAtMost(screenState.duration) else (position - step).coerceAtLeast(0L)
        if (playerState.isLive || player?.isCurrentMediaItemLive == true) manager.seekToLiveTimeline(target) else manager.seekTo(target)
        val stepSeconds = session.prefs.doubleTapSeekSeconds
        val continuing = if (forward) screenState.showSeekForwardAnimation else screenState.showSeekBackAnimation
        screenState.seekAccumulation = if (continuing) screenState.seekAccumulation + stepSeconds else stepSeconds
        screenState.showSeekForwardAnimation = forward
        screenState.showSeekBackAnimation = !forward
    }

    fun stepSpeed(faster: Boolean): Boolean {
        val options = playbackSpeedOptions(customSpeedsEnabled, customSpeedPresets)
        val speed = stepPlaybackSpeed(options, playerState.playbackSpeed, faster) ?: return false
        EnhancedPlayerManager.getInstance().setPlaybackSpeed(speed)
        screenState.normalSpeed = speed
        screenState.speedSwipeLevel = speed
        screenState.showSpeedSwipeOverlay = true
        session.scope.saveChosenPlaybackSpeed(
            playerPreferences = playerPreferences,
            speed = speed,
            rememberPlaybackSpeed = session.prefs.rememberPlaybackSpeed,
            channelId = session.uiState.cachedVideo?.channelId,
        )
        return true
    }

    return mediaKeyboardShortcuts(enabled) { action ->
        screenState.onInteraction()
        when (action) {
            MediaKeyAction.TogglePlayback -> {
                screenState.showControls = true
                actions.onPlayPause()
                true
            }

            MediaKeyAction.Play, MediaKeyAction.Pause -> {
                screenState.showControls = true
                if (playerState.playWhenReady != (action == MediaKeyAction.Play)) actions.onPlayPause()
                true
            }

            MediaKeyAction.SeekBack, MediaKeyAction.SeekForward -> {
                val seekable = playerState.isLive || screenState.duration > 0
                if (seekable) seekBy(forward = action == MediaKeyAction.SeekForward)
                seekable
            }

            MediaKeyAction.VolumeUp -> {
                PlayerHardwareController.adjustVolume(session.context, raise = true)
            }

            MediaKeyAction.VolumeDown -> {
                PlayerHardwareController.adjustVolume(session.context, raise = false)
            }

            MediaKeyAction.ToggleFullscreen -> {
                actions.onFullscreenClick()
                true
            }

            MediaKeyAction.ExitFullscreen -> {
                screenState.isFullscreen.also { if (it) actions.onFullscreenClick() }
            }

            MediaKeyAction.ToggleCaptions -> {
                val hasCaptions = playerState.availableSubtitles.isNotEmpty() || playerState.selectedSubtitleUrl != null
                if (hasCaptions) actions.onSubtitleClick()
                hasCaptions
            }

            MediaKeyAction.Next -> {
                if (hasNext) actions.onNext()
                hasNext
            }

            MediaKeyAction.Previous -> {
                if (hasPrevious) actions.onPrevious()
                hasPrevious
            }

            MediaKeyAction.SpeedUp -> {
                stepSpeed(faster = true)
            }

            MediaKeyAction.SpeedDown -> {
                stepSpeed(faster = false)
            }

            MediaKeyAction.FocusList -> {
                screenState.relatedFocus.focusFirstCard(session.scope)
            }

            is MediaKeyAction.JumpTo -> {
                val canJump = !playerState.isLive && screenState.duration > 0
                if (canJump) {
                    screenState.showControls = true
                    EnhancedPlayerManager.getInstance().seekTo(screenState.duration * action.tenths / 10)
                }
                canJump
            }
        }
    }
}
