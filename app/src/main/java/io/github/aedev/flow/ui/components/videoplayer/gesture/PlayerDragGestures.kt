package io.github.aedev.flow.ui.components.videoplayer.gesture

import android.app.Activity
import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.local.SpeedSwipeSide
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.ui.components.shared.PlayerSpeedSwipe
import io.github.aedev.flow.ui.components.shared.SpeedSwipeDrag
import io.github.aedev.flow.ui.components.shared.SpeedSwipeStepTravel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val SEEK_DRAG_SPAN_MS = 90_000L

/** Target movement between haptic ticks while dragging to seek. */
private const val SEEK_DRAG_HAPTIC_STEP_MS = 5_000L

/** Distance from the top and bottom edges where drags are left to the controls beneath them. */
private val DragEdgeIgnore = 40.dp

/** Downward travel in the centre zone that commits to leaving fullscreen. */
private const val EXIT_FULLSCREEN_DRAG_PX = 80f

private const val EXIT_FULLSCREEN_OVERSHOOT_PX = 140f

private const val VERTICAL_DRAG_SENSITIVITY = 1.5f

/** Where a drag leaving auto brightness resumes from, and how far below zero one may bank. */
private const val AUTO_BRIGHTNESS_SEED = -0.06f
private const val AUTO_BRIGHTNESS_FLOOR = -0.12f

/** Float slack when comparing the stream against its maximum. */
private const val STREAM_MAX_EPSILON = 0.001f

private fun resistedTravel(
    distance: Float,
    limit: Float,
): Float = limit * distance / (limit + distance)

internal fun Modifier.playerDragGestures(
    currentPosition: State<() -> Long>,
    duration: State<Long>,
    scope: CoroutineScope,
    isFullscreen: State<Boolean>,
    onBrightnessChange: State<(Float) -> Unit>,
    onShowBrightnessChange: State<(Boolean) -> Unit>,
    onVolumeChange: State<(Float) -> Unit>,
    onShowVolumeChange: State<(Boolean) -> Unit>,
    onSeekDragChange: State<(Boolean) -> Unit>,
    onSeekDragUpdate: State<(targetMs: Long, deltaMs: Long) -> Unit>,
    brightnessLevel: State<() -> Float>,
    volumeLevel: State<() -> Float>,
    maxVolume: State<Int>,
    audioManager: State<AudioManager?>,
    activity: State<Activity?>,
    brightnessSwipeGesturesEnabled: State<Boolean>,
    volumeSwipeGesturesEnabled: State<Boolean>,
    seekSwipeGesturesEnabled: State<Boolean>,
    allowVolumeBoost: State<Boolean>,
    speedSwipeSide: State<SpeedSwipeSide>,
    speedSwipeStep: State<Float>,
    onSpeedSwipe: State<(speed: Float) -> Unit>,
    onSpeedSwipeEnd: State<(speed: Float) -> Unit>,
    sideEdgeIgnorePx: State<Float>,
    onExitFullscreen: State<((releaseOffsetPx: Float, releaseVelocityPx: Float) -> Unit)?>,
    onExitFullscreenDrag: State<(offsetPx: Float) -> Unit>,
    haptics: HapticFeedback,
    lastBrightnessApplied: FloatArray,
    lastBrightnessAppliedAt: LongArray,
): Modifier {
    val currentPositionProvider by currentPosition
    val currentDuration by duration
    val currentIsFullscreen by isFullscreen
    val currentOnBrightnessChange by onBrightnessChange
    val currentOnShowBrightnessChange by onShowBrightnessChange
    val currentOnVolumeChange by onVolumeChange
    val currentOnShowVolumeChange by onShowVolumeChange
    val currentOnSeekDragChange by onSeekDragChange
    val currentOnSeekDragUpdate by onSeekDragUpdate
    val currentBrightnessLevel by brightnessLevel
    val currentVolumeLevel by volumeLevel
    val currentMaxVolume by maxVolume
    val currentAudioManager by audioManager
    val currentActivity by activity
    val currentBrightnessSwipeGesturesEnabled by brightnessSwipeGesturesEnabled
    val currentVolumeSwipeGesturesEnabled by volumeSwipeGesturesEnabled
    val currentSeekSwipeGesturesEnabled by seekSwipeGesturesEnabled
    val currentAllowVolumeBoost by allowVolumeBoost
    val currentSpeedSwipeSide by speedSwipeSide
    val currentSpeedSwipeStep by speedSwipeStep
    val currentOnSpeedSwipe by onSpeedSwipe
    val currentOnSpeedSwipeEnd by onSpeedSwipeEnd
    val currentSideEdgeIgnorePx by sideEdgeIgnorePx
    val currentOnExitFullscreen by onExitFullscreen
    val currentOnExitFullscreenDrag by onExitFullscreenDrag

    // Outside fullscreen only the sideways seek is claimed: vertical drags stay unconsumed so the
    // player sheet keeps them for minimising and entering fullscreen.
    return this.pointerInput(currentIsFullscreen) {
        var isCenterZone = false
        var isSpeedDrag = false
        val speedDrag =
            SpeedSwipeDrag(
                haptics = haptics,
                step = { currentSpeedSwipeStep },
                currentSpeed = {
                    val manager = EnhancedPlayerManager.getInstance()
                    manager.getPlayer()?.playbackParameters?.speed ?: manager.playerState.value.playbackSpeed
                },
                isLiveStream = {
                    val manager = EnhancedPlayerManager.getInstance()
                    manager.playerState.value.isLive || manager.getPlayer()?.isCurrentMediaItemLive == true
                },
                setSpeed = { EnhancedPlayerManager.getInstance().setPlaybackSpeed(it) },
                onSpeed = { currentOnSpeedSwipe(it) },
                onEnd = { currentOnSpeedSwipeEnd(it) },
            )

        fun endSpeedDrag() {
            speedDrag.end()
            isSpeedDrag = false
        }
        var exitDragTravel = 0f
        var exitDragPastCommit = false
        var exitSettleJob: Job? = null
        val exitVelocity = VelocityTracker()
        var lastVolumeStep = -1
        val brightnessTicks = GestureTicker { haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }
        val volumeTicks = GestureTicker { haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }

        // Each vertical drag accumulates its own level rather than re-reading the one it just
        // published. screenState is snapshot state and a pointer handler can run several times
        // between two frames, so the read-back lags the write: every event after the first in a
        // frame started from a stale base, which is what made brightness jitter and made volume
        // appear to slide back down when a second swipe continued from a boosted level.
        var volumeGestureLevel = Float.NaN
        var brightnessGestureLevel = Float.NaN

        var seekDragStarted = false
        var seekDragBaseMs = 0L
        var seekDragTargetMs = 0L
        var seekDragTravelPx = 0f
        var lastSeekHapticMs = 0L

        fun applyBrightnessDrag(dy: Float) {
            val screenHeight = size.height.toFloat()
            if (screenHeight <= 0f) return

            if (brightnessGestureLevel.isNaN()) {
                val level = currentBrightnessLevel()
                // Auto reads as -1; seed just under the auto threshold so the first upward nudge
                // leaves auto instead of jumping to whatever brightness it had before.
                brightnessGestureLevel = if (level < 0f) AUTO_BRIGHTNESS_SEED else level
            }

            val delta = -dy / screenHeight * VERTICAL_DRAG_SENSITIVITY
            // Tracked slightly below zero so the auto threshold stays reachable, and clamped so a
            // long downward drag cannot bank travel the user then has to undo.
            brightnessGestureLevel = (brightnessGestureLevel + delta).coerceIn(AUTO_BRIGHTNESS_FLOOR, 1f)

            // Auto brightness logic: if dragging down past -5%
            val newBrightness =
                if (brightnessGestureLevel < -0.05f) {
                    -1.0f // Auto mode
                } else {
                    brightnessGestureLevel.coerceIn(0f, 1f)
                }

            currentOnBrightnessChange(newBrightness)

            brightnessTicks.onStep(gestureLevelStep(newBrightness))

            val now = SystemClock.uptimeMillis()
            val brightnessDelta = abs(newBrightness - lastBrightnessApplied[0])
            val timeDelta = now - lastBrightnessAppliedAt[0]
            // Apply window brightness only when the change is perceptible
            // or 16 ms has elapsed; this keeps WindowManager relayouts off
            // every drag tick so the video pipeline doesn't drop frames.
            if (brightnessDelta > 0.004f || timeDelta >= 16L) {
                try {
                    currentActivity?.window?.let { window ->
                        val layoutParams = window.attributes
                        layoutParams.screenBrightness = newBrightness
                        window.attributes = layoutParams
                    }
                    lastBrightnessApplied[0] = newBrightness
                    lastBrightnessAppliedAt[0] = now
                } catch (e: Exception) {
                }
            }
            currentOnShowBrightnessChange(true)
        }

        fun systemVolumeFraction(): Float? {
            val max = currentMaxVolume
            if (max <= 0) return null
            val system = currentAudioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: return null
            return (system.toFloat() / max).coerceIn(0f, 1f)
        }

        fun applyVolumeDrag(dy: Float) {
            val screenHeight = size.height.toFloat()
            if (screenHeight <= 0f) return

            val ceiling = if (currentAllowVolumeBoost) 2.0f else 1.0f

            if (volumeGestureLevel.isNaN()) {
                // The system stream is the source of truth (#1062). Our own level goes stale
                // whenever the volume moves outside the player — quick settings, another app, a
                // paused session — so each gesture starts from what the stream actually holds
                // rather than a remembered value the user has since overridden. A boost above the
                // system ceiling is app-only state, so it survives only while the stream is maxed.
                val remembered = currentVolumeLevel().coerceIn(0f, ceiling)
                val systemFraction = systemVolumeFraction()
                // "Still maxed" allows one step of slack: a drag crossing 100% lands on a fraction
                // like 0.98, whose integer step is one below maximum, and reading that as "the user
                // turned it down elsewhere" is what used to knock a boosted level back to 100%.
                val step = if (currentMaxVolume > 0) 1f / currentMaxVolume else 0f
                val streamLoweredElsewhere =
                    systemFraction != null && systemFraction < 1f - step - STREAM_MAX_EPSILON
                volumeGestureLevel =
                    if (systemFraction != null && (remembered <= 1f || streamLoweredElsewhere)) {
                        systemFraction
                    } else {
                        remembered
                    }
            }

            val delta = -dy / screenHeight * VERTICAL_DRAG_SENSITIVITY
            volumeGestureLevel = (volumeGestureLevel + delta).coerceIn(0f, ceiling)
            val newVolumeLevel = volumeGestureLevel
            currentOnVolumeChange(newVolumeLevel)

            // Above the ceiling the app supplies the extra gain, but the stream still has to sit
            // at maximum — both so the boost is applied on top of full volume, and so the next
            // gesture can tell a live boost from a volume the user lowered somewhere else.
            val newVolume =
                if (newVolumeLevel <= 1.0f) {
                    (newVolumeLevel * currentMaxVolume).toInt()
                } else {
                    currentMaxVolume
                }
            if (newVolume != lastVolumeStep) {
                currentAudioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                lastVolumeStep = newVolume
            }
            volumeTicks.onStep(volumeGestureStep(newVolumeLevel, currentMaxVolume))
            currentOnShowVolumeChange(true)
        }

        fun exitDragOffset(): Float =
            if (exitDragTravel > EXIT_FULLSCREEN_DRAG_PX) {
                EXIT_FULLSCREEN_DRAG_PX +
                    resistedTravel(
                        exitDragTravel - EXIT_FULLSCREEN_DRAG_PX,
                        EXIT_FULLSCREEN_OVERSHOOT_PX,
                    )
            } else {
                exitDragTravel
            }

        fun publishExitDrag() {
            currentOnExitFullscreenDrag(exitDragOffset())
        }

        fun applyExitDrag(dy: Float) {
            exitSettleJob?.cancel()
            exitDragTravel = (exitDragTravel + dy).coerceAtLeast(0f)

            val pastCommit = exitDragTravel >= EXIT_FULLSCREEN_DRAG_PX
            if (pastCommit != exitDragPastCommit) {
                exitDragPastCommit = pastCommit
                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }
            publishExitDrag()
        }

        fun endExitDrag(commit: Boolean) {
            val exiting = commit && exitDragPastCommit
            exitDragPastCommit = false

            if (exiting) {
                // The video is handed over where the finger left it, so the exit continues from there.
                val releaseOffset = exitDragOffset()
                val releaseVelocity = exitVelocity.calculateVelocity().y
                exitSettleJob?.cancel()
                exitDragTravel = 0f
                currentOnExitFullscreen?.invoke(releaseOffset, releaseVelocity)
                return
            }
            if (exitDragTravel == 0f || exitSettleJob?.isActive == true) return

            val from = exitDragTravel
            exitSettleJob =
                scope.launch {
                    animate(
                        initialValue = from,
                        targetValue = 0f,
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                    ) { travel, _ ->
                        exitDragTravel = travel
                        publishExitDrag()
                    }
                }
        }

        fun beginSeekDrag() {
            val manager = EnhancedPlayerManager.getInstance()
            seekDragBaseMs = manager.getPlayer()?.currentPosition ?: currentPositionProvider()
            seekDragTargetMs = seekDragBaseMs
            lastSeekHapticMs = seekDragBaseMs
            seekDragTravelPx = 0f
            currentOnSeekDragUpdate(seekDragTargetMs, 0L)
            currentOnSeekDragChange(true)
        }

        fun updateSeekDrag(dx: Float) {
            val width = size.width.toFloat()
            if (width <= 0f || currentDuration <= 0L) return

            seekDragTravelPx += dx
            val spanMs = minOf(SEEK_DRAG_SPAN_MS, currentDuration)
            val rawTarget = seekDragBaseMs + (seekDragTravelPx / width * spanMs).toLong()
            val clamped = rawTarget.coerceIn(0L, currentDuration)
            if (clamped != rawTarget) {
                seekDragTravelPx = (clamped - seekDragBaseMs).toFloat() / spanMs * width
            }
            seekDragTargetMs = clamped

            currentOnSeekDragUpdate(clamped, clamped - seekDragBaseMs)

            if (abs(clamped - lastSeekHapticMs) >= SEEK_DRAG_HAPTIC_STEP_MS) {
                lastSeekHapticMs = clamped
                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }
        }

        fun endSeekDrag(commit: Boolean) {
            if (commit && seekDragTargetMs != seekDragBaseMs) {
                val manager = EnhancedPlayerManager.getInstance()
                val player = manager.getPlayer()
                val isLive = manager.playerState.value.isLive || player?.isCurrentMediaItemLive == true
                if (isLive) {
                    manager.seekToLiveTimeline(seekDragTargetMs)
                } else {
                    manager.seekTo(seekDragTargetMs)
                }
            }
            seekDragStarted = false
            currentOnSeekDragChange(false)
        }

        try {
            detectPlayerDrags(
                onDragStart = { offset ->
                    lastVolumeStep = -1
                    volumeGestureLevel = Float.NaN
                    brightnessGestureLevel = Float.NaN
                    brightnessTicks.reset()
                    volumeTicks.reset()
                    seekDragStarted = false
                    endSpeedDrag()

                    // The side dead zones belong to the system's back gesture and to nothing
                    // else: a swipe that starts there is on its way out of the app, and claiming
                    // it is what turned a back swipe into a seek and an edge flick into a
                    // brightness change (#1068).
                    val sideIgnore = currentSideEdgeIgnorePx
                    val topBottomIgnore = DragEdgeIgnore.toPx()
                    val nearEdge =
                        offset.y < topBottomIgnore ||
                            size.height - offset.y < topBottomIgnore ||
                            offset.x < sideIgnore ||
                            size.width - offset.x < sideIgnore
                    if (nearEdge) {
                        false
                    } else {
                        val width = size.width
                        isCenterZone = offset.x > width * 0.33f && offset.x < width * 0.67f
                        isSpeedDrag =
                            currentIsFullscreen &&
                            !isCenterZone &&
                            PlayerSpeedSwipe.ownsDrag(currentSpeedSwipeSide, offset.x, width.toFloat())
                        if (isSpeedDrag) speedDrag.begin()
                        true
                    }
                },
                onAxisAccepted = { axis ->
                    when (axis) {
                        PlayerDragAxis.HORIZONTAL -> currentSeekSwipeGesturesEnabled && currentDuration > 0L
                        PlayerDragAxis.VERTICAL -> currentIsFullscreen
                    }
                },
                onDrag = { change, delta, axis ->
                    when (axis) {
                        PlayerDragAxis.HORIZONTAL -> {
                            if (!seekDragStarted) {
                                seekDragStarted = true
                                beginSeekDrag()
                            }
                            updateSeekDrag(delta.x)
                        }

                        PlayerDragAxis.VERTICAL -> {
                            val width = size.width
                            when {
                                isCenterZone -> {
                                    if (exitDragTravel == 0f) exitVelocity.resetTracking()
                                    exitVelocity.addPosition(change.uptimeMillis, change.position)
                                    applyExitDrag(delta.y)
                                }

                                isSpeedDrag -> {
                                    speedDrag.drag(delta.y, SpeedSwipeStepTravel.toPx())
                                }

                                change.position.x < width / 2 -> {
                                    if (currentBrightnessSwipeGesturesEnabled) applyBrightnessDrag(delta.y)
                                }

                                else -> {
                                    if (currentVolumeSwipeGesturesEnabled) applyVolumeDrag(delta.y)
                                }
                            }
                        }
                    }
                },
                onDragEnd = { axis ->
                    when (axis) {
                        PlayerDragAxis.HORIZONTAL -> {
                            if (seekDragStarted) endSeekDrag(commit = true)
                            endExitDrag(commit = false)
                        }

                        PlayerDragAxis.VERTICAL -> {
                            endExitDrag(commit = isCenterZone)
                            endSpeedDrag()
                            scope.launch {
                                delay(500) // Delay hiding controls
                                currentOnShowBrightnessChange(false)
                                currentOnShowVolumeChange(false)
                            }
                        }
                    }
                    isCenterZone = false
                },
                onDragCancel = { axis ->
                    endExitDrag(commit = false)
                    when (axis) {
                        PlayerDragAxis.HORIZONTAL -> {
                            if (seekDragStarted) endSeekDrag(commit = false)
                        }

                        PlayerDragAxis.VERTICAL -> {
                            endSpeedDrag()
                            scope.launch {
                                currentOnShowBrightnessChange(false)
                                currentOnShowVolumeChange(false)
                            }
                        }
                    }
                    isCenterZone = false
                },
            )
        } finally {
            // A system takeover (the status bar sliding in over the video) cancels this
            // coroutine outright, so neither onDragEnd nor onDragCancel runs (#906).
            exitSettleJob?.cancel()
            currentOnExitFullscreenDrag(0f)
            if (seekDragStarted) currentOnSeekDragChange(false)
            endSpeedDrag()
        }
    }
}
