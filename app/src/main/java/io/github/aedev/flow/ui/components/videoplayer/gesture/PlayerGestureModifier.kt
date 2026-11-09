package io.github.aedev.flow.ui.components.videoplayer.gesture

import android.app.Activity
import android.media.AudioManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.local.DEFAULT_SPEED_SWIPE_STEP
import io.github.aedev.flow.data.local.DoubleTapSeekZone
import io.github.aedev.flow.data.local.SpeedSwipeSide
import kotlinx.coroutines.CoroutineScope

private val MinSideEdgeIgnore = 24.dp

@Composable
fun Modifier.videoPlayerControls(
    isSpeedBoostActive: Boolean,
    onSpeedBoostChange: (Boolean) -> Unit,
    showControls: Boolean,
    onShowControlsChange: (Boolean) -> Unit,
    onShowSeekBackChange: (Boolean) -> Unit,
    onShowSeekForwardChange: (Boolean) -> Unit,
    onSeekAccumulate: (Int) -> Unit = {},
    currentPosition: () -> Long,
    duration: Long,
    onNormalSpeedChange: (Float) -> Unit = {},
    scope: CoroutineScope,
    isFullscreen: Boolean,
    onBrightnessChange: (Float) -> Unit,
    onShowBrightnessChange: (Boolean) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onShowVolumeChange: (Boolean) -> Unit,
    onSeekDragChange: (Boolean) -> Unit = {},
    onSeekDragUpdate: (targetMs: Long, deltaMs: Long) -> Unit = { _, _ -> },
    brightnessLevel: () -> Float,
    volumeLevel: () -> Float,
    maxVolume: Int,
    audioManager: AudioManager?,
    activity: Activity?,
    brightnessSwipeGesturesEnabled: Boolean = true,
    volumeSwipeGesturesEnabled: Boolean = true,
    seekSwipeGesturesEnabled: Boolean = true,
    allowVolumeBoost: Boolean = false,
    speedSwipeSide: SpeedSwipeSide = SpeedSwipeSide.OFF,
    speedSwipeStep: Float = DEFAULT_SPEED_SWIPE_STEP,
    onSpeedSwipe: (speed: Float) -> Unit = {},
    onSpeedSwipeEnd: (speed: Float) -> Unit = {},
    doubleTapSeekMs: Long = 10_000L,
    seekZoneFraction: Float = DoubleTapSeekZone.NORMAL.sideFraction,
    seekZoneReachesEdges: Boolean = false,
    longPressPlaybackSpeed: Float = 2.0f,
    onExitFullscreen: ((releaseOffsetPx: Float, releaseVelocityPx: Float) -> Unit)? = null,
    onExitFullscreenDrag: (offsetPx: Float) -> Unit = {},
    onMoreVideos: (() -> Unit)? = null,
    onMoreVideosDrag: (offsetPx: Float) -> Unit = {},
    isSeekForwardActive: Boolean = false,
    isSeekBackActive: Boolean = false,
    onTapWindowChange: (Boolean) -> Unit = {},
): Modifier {
    val isSpeedBoostActiveState = rememberUpdatedState(isSpeedBoostActive)
    val onSpeedBoostChangeState = rememberUpdatedState(onSpeedBoostChange)
    val showControlsState = rememberUpdatedState(showControls)
    val onShowControlsChangeState = rememberUpdatedState(onShowControlsChange)
    val onShowSeekBackChangeState = rememberUpdatedState(onShowSeekBackChange)
    val onShowSeekForwardChangeState = rememberUpdatedState(onShowSeekForwardChange)
    val currentPositionState = rememberUpdatedState(currentPosition)
    val durationState = rememberUpdatedState(duration)
    val onNormalSpeedChangeState = rememberUpdatedState(onNormalSpeedChange)
    val isFullscreenState = rememberUpdatedState(isFullscreen)
    val onBrightnessChangeState = rememberUpdatedState(onBrightnessChange)
    val onShowBrightnessChangeState = rememberUpdatedState(onShowBrightnessChange)
    val onVolumeChangeState = rememberUpdatedState(onVolumeChange)
    val onShowVolumeChangeState = rememberUpdatedState(onShowVolumeChange)
    val onSeekDragChangeState = rememberUpdatedState(onSeekDragChange)
    val onSeekDragUpdateState = rememberUpdatedState(onSeekDragUpdate)
    val brightnessLevelState = rememberUpdatedState(brightnessLevel)
    val volumeLevelState = rememberUpdatedState(volumeLevel)
    val maxVolumeState = rememberUpdatedState(maxVolume)
    val audioManagerState = rememberUpdatedState(audioManager)
    val activityState = rememberUpdatedState(activity)
    val brightnessSwipeGesturesEnabledState = rememberUpdatedState(brightnessSwipeGesturesEnabled)
    val volumeSwipeGesturesEnabledState = rememberUpdatedState(volumeSwipeGesturesEnabled)
    val seekSwipeGesturesEnabledState = rememberUpdatedState(seekSwipeGesturesEnabled)
    val allowVolumeBoostState = rememberUpdatedState(allowVolumeBoost)
    val speedSwipeSideState = rememberUpdatedState(speedSwipeSide)
    val speedSwipeStepState = rememberUpdatedState(speedSwipeStep)
    val onSpeedSwipeState = rememberUpdatedState(onSpeedSwipe)
    val onSpeedSwipeEndState = rememberUpdatedState(onSpeedSwipeEnd)
    val doubleTapSeekMsState = rememberUpdatedState(doubleTapSeekMs)
    val seekZoneFractionState = rememberUpdatedState(seekZoneFraction)
    val seekZoneReachesEdgesState = rememberUpdatedState(seekZoneReachesEdges)
    val longPressPlaybackSpeedState = rememberUpdatedState(longPressPlaybackSpeed)
    val onSeekAccumulateState = rememberUpdatedState(onSeekAccumulate)
    val onExitFullscreenState = rememberUpdatedState(onExitFullscreen)
    val onExitFullscreenDragState = rememberUpdatedState(onExitFullscreenDrag)
    val onMoreVideosState = rememberUpdatedState(onMoreVideos)
    val onMoreVideosDragState = rememberUpdatedState(onMoreVideosDrag)
    val isSeekForwardActiveState = rememberUpdatedState(isSeekForwardActive)
    val isSeekBackActiveState = rememberUpdatedState(isSeekBackActive)
    val onTapWindowChangeState = rememberUpdatedState(onTapWindowChange)

    // The width the system itself reserves for the back gesture, so the player's dead zones match
    // the ones the user is already swiping against rather than a number of our own.
    val gestureDensity = LocalDensity.current
    val gestureLayoutDirection = LocalLayoutDirection.current
    val systemGestureInsets = WindowInsets.systemGestures
    val sideEdgeIgnorePxState =
        rememberUpdatedState(
            maxOf(
                systemGestureInsets.getLeft(gestureDensity, gestureLayoutDirection).toFloat(),
                systemGestureInsets.getRight(gestureDensity, gestureLayoutDirection).toFloat(),
                // Held open even on button navigation, where the system claims nothing: a swipe
                // starting within a fingertip of the edge is as likely to be aimed past the app.
                with(gestureDensity) { MinSideEdgeIgnore.toPx() },
            ),
        )

    val haptics = LocalHapticFeedback.current

    val lastBrightnessApplied = remember { floatArrayOf(-2f) }
    val lastBrightnessAppliedAt = remember { longArrayOf(0L) }

    return this
        .playerTapGestures(
            isSpeedBoostActive = isSpeedBoostActiveState,
            onSpeedBoostChange = onSpeedBoostChangeState,
            showControls = showControlsState,
            onShowControlsChange = onShowControlsChangeState,
            onShowSeekBackChange = onShowSeekBackChangeState,
            onShowSeekForwardChange = onShowSeekForwardChangeState,
            onSeekAccumulate = onSeekAccumulateState,
            currentPosition = currentPositionState,
            duration = durationState,
            onNormalSpeedChange = onNormalSpeedChangeState,
            isFullscreen = isFullscreenState,
            doubleTapSeekMs = doubleTapSeekMsState,
            seekZoneFraction = seekZoneFractionState,
            seekZoneReachesEdges = seekZoneReachesEdgesState,
            longPressPlaybackSpeed = longPressPlaybackSpeedState,
            isSeekForwardActive = isSeekForwardActiveState,
            isSeekBackActive = isSeekBackActiveState,
            sideEdgeIgnorePx = sideEdgeIgnorePxState,
            onTapWindowChange = onTapWindowChangeState,
            haptics = haptics,
        ).playerDragGestures(
            currentPosition = currentPositionState,
            duration = durationState,
            scope = scope,
            isFullscreen = isFullscreenState,
            onBrightnessChange = onBrightnessChangeState,
            onShowBrightnessChange = onShowBrightnessChangeState,
            onVolumeChange = onVolumeChangeState,
            onShowVolumeChange = onShowVolumeChangeState,
            onSeekDragChange = onSeekDragChangeState,
            onSeekDragUpdate = onSeekDragUpdateState,
            brightnessLevel = brightnessLevelState,
            volumeLevel = volumeLevelState,
            maxVolume = maxVolumeState,
            audioManager = audioManagerState,
            activity = activityState,
            brightnessSwipeGesturesEnabled = brightnessSwipeGesturesEnabledState,
            volumeSwipeGesturesEnabled = volumeSwipeGesturesEnabledState,
            seekSwipeGesturesEnabled = seekSwipeGesturesEnabledState,
            allowVolumeBoost = allowVolumeBoostState,
            speedSwipeSide = speedSwipeSideState,
            speedSwipeStep = speedSwipeStepState,
            onSpeedSwipe = onSpeedSwipeState,
            onSpeedSwipeEnd = onSpeedSwipeEndState,
            sideEdgeIgnorePx = sideEdgeIgnorePxState,
            onExitFullscreen = onExitFullscreenState,
            onExitFullscreenDrag = onExitFullscreenDragState,
            onMoreVideos = onMoreVideosState,
            onMoreVideosDrag = onMoreVideosDragState,
            haptics = haptics,
            lastBrightnessApplied = lastBrightnessApplied,
            lastBrightnessAppliedAt = lastBrightnessAppliedAt,
        )
}
