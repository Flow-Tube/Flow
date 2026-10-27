package io.github.aedev.flow.ui.screens.player.effects

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

@Composable
internal fun FullscreenEffect(
    isFullscreen: Boolean,
    activity: Activity?,
    videoAspectRatio: Float = 16f / 9f,
    lifecycleOwner: LifecycleOwner,
    fullscreenBrightnessLevel: () -> Float? = { null },
    suppressFullscreenRequest: Boolean = false,
    isPortrait: Boolean = false,
    isLargeWindow: Boolean = false,
    hideSystemBars: Boolean,
) {
    var resumeTrigger by remember { mutableIntStateOf(0) }
    val currentIsLargeWindow by rememberUpdatedState(isLargeWindow)
    var forcePortraitLock by remember { mutableStateOf(false) }
    var wasFullscreen by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME, lifecycleOwner) { resumeTrigger++ }

    // MainActivity drops the activity's own lock in onStop, so holding on to ours would re-pin
    // portrait on the next resume and never let go again (#841).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP, lifecycleOwner) { forcePortraitLock = false }

    LaunchedEffect(isFullscreen, videoAspectRatio, resumeTrigger, suppressFullscreenRequest, isPortrait) {
        activity?.let { act ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && act.isInPictureInPictureMode) return@let
            if (suppressFullscreenRequest && isFullscreen) return@let
            if (isFullscreen) {
                forcePortraitLock = false
                wasFullscreen = true
                val orientation =
                    when {
                        isPortrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        videoAspectRatio < 1f -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                act.requestedOrientation = orientation

                fullscreenBrightnessLevel()?.let { brightnessLevel ->
                    val layoutParams = act.window.attributes
                    layoutParams.screenBrightness =
                        if (brightnessLevel < 0f) {
                            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        } else {
                            brightnessLevel.coerceIn(0f, 1f)
                        }
                    act.window.attributes = layoutParams
                }
            } else {
                val leavingFullscreen = wasFullscreen
                wasFullscreen = false
                val configuration = act.resources.configuration
                val cfgLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val autoRotateOn =
                    try {
                        Settings.System.getInt(
                            act.contentResolver,
                            Settings.System.ACCELEROMETER_ROTATION,
                        ) == 1
                    } catch (e: Exception) {
                        false
                    }

                // A window with no landscape layout outside fullscreen is held in portrait until
                // the user physically rotates back. A window that does have one is showing its
                // primary layout, and the release listener below can never fire on a device whose
                // natural orientation is portrait but is being held sideways, so pinning one here
                // would strand it in portrait for the rest of the session (#918).
                //
                // Only an actual fullscreen exit may pin: this effect also re-runs on every resume
                // and on the first composition, where pinning locks an app that was merely
                // backgrounded in landscape into portrait until it is force-restarted (#841).
                when {
                    leavingFullscreen && cfgLandscape && autoRotateOn && !currentIsLargeWindow -> {
                        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        forcePortraitLock = true
                    }

                    !forcePortraitLock -> {
                        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }

                // Reset screen brightness to default when exiting fullscreen
                val layoutParams = act.window.attributes
                layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                act.window.attributes = layoutParams
            }
        }
    }

    // Keyed on the immersive layout rather than isFullscreen: a compact landscape window shows
    // that layout on expand without ever entering fullscreen, and left the bars over the video.
    LaunchedEffect(hideSystemBars, resumeTrigger) {
        val act = activity ?: return@LaunchedEffect
        if (act.isInPictureInPictureMode) return@LaunchedEffect
        WindowCompat.setDecorFitsSystemWindows(act.window, false)
        val insetsController = WindowCompat.getInsetsController(act.window, act.window.decorView)
        if (hideSystemBars) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val lockActivity = activity
    if (forcePortraitLock && lockActivity != null) {
        DisposableEffect(lockActivity) {
            val listener =
                object : OrientationEventListener(lockActivity) {
                    override fun onOrientationChanged(orientation: Int) {
                        if (orientation == ORIENTATION_UNKNOWN) return
                        val physicallyPortrait =
                            orientation in 0..30 || orientation in 330..359 || orientation in 150..210
                        if (physicallyPortrait) {
                            lockActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            forcePortraitLock = false
                        }
                    }
                }
            listener.enable()
            onDispose { listener.disable() }
        }
    }
}

@Composable
internal fun KeepScreenOnEffect(
    isPlaying: Boolean,
    activity: Activity?,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
) {
    LifecycleStartEffect(activity, isPlaying, lifecycleOwner) {
        val clearScreenOn = {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        if (isPlaying) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            clearScreenOn()
        }

        onStopOrDispose { clearScreenOn() }
    }
}

@Composable
internal fun OrientationResetEffect(activity: Activity?) {
    DisposableEffect(Unit) {
        onDispose {
            if (activity?.isInPictureInPictureMode == false) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.setDecorFitsSystemWindows(activity.window, false)
                val insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

@Composable
internal fun OrientationListenerEffect(
    context: Context,
    isExpanded: Boolean,
    isFullscreen: Boolean,
    videoAspectRatio: Float = 16f / 9f,
    isPortraitFullscreen: Boolean = false,
    ignoreRotationLock: Boolean = false,
    onEnterFullscreen: () -> Unit,
    onExitFullscreen: () -> Unit,
) {
    var physicalOrientation by remember { mutableIntStateOf(-1) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val currentIsFullscreen by rememberUpdatedState(isFullscreen)
    val currentAspectRatio by rememberUpdatedState(videoAspectRatio)
    val currentIsPortraitFullscreen by rememberUpdatedState(isPortraitFullscreen)
    val currentIgnoreRotationLock by rememberUpdatedState(ignoreRotationLock)
    val currentEnter by rememberUpdatedState(onEnterFullscreen)
    val currentExit by rememberUpdatedState(onExitFullscreen)

    val listener =
        remember(context) {
            object : OrientationEventListener(context) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN) return

                    val autoRotateOn = {
                        try {
                            Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION) == 1
                        } catch (e: Exception) {
                            true
                        }
                    }

                    if (!followsTilt(currentIgnoreRotationLock, autoRotateOn)) return

                    val newOrientation = heldOrientation(orientation, physicalOrientation)

                    if (newOrientation != physicalOrientation) {
                        physicalOrientation = newOrientation
                    }
                }
            }
        }

    // Sensor-driven fullscreen changes are only meaningful while the user is looking at the
    // player, and an accelerometer left registered in the background costs battery for nothing.
    LifecycleResumeEffect(listener, lifecycleOwner) {
        listener.enable()
        onPauseOrDispose { listener.disable() }
    }

    LaunchedEffect(physicalOrientation, isExpanded) {
        delay(150)
        if (physicalOrientation != 1 || !isExpanded) return@LaunchedEffect
        if (currentIsPortraitFullscreen || currentAspectRatio < 1f) return@LaunchedEffect
        if (!currentIsFullscreen) currentEnter()
    }

    // Exit answers only the phone turning upright, not the sheet expanding: "Open videos in
    // fullscreen" enters as the sheet settles, and a re-run on that flip left it again 150 ms later.
    LaunchedEffect(physicalOrientation) {
        delay(150)
        if (physicalOrientation != 0) return@LaunchedEffect
        if (currentIsPortraitFullscreen || currentAspectRatio < 1f) return@LaunchedEffect
        if (currentIsFullscreen) currentExit()
    }
}

/**
 * How the phone is held, from [OrientationEventListener] degrees: 0 upright, 1 on either side, and
 * [previous] in between. Upside down also keeps [previous]: counted as portrait, it left fullscreen
 * and pinned normal portrait while the phone was inverted, so the picture showed upside down (#1426).
 */
internal fun heldOrientation(
    degrees: Int,
    previous: Int,
): Int =
    when (degrees) {
        in 60..120, in 240..300 -> 1
        in 0..30, in 330..359 -> 0
        else -> previous
    }

/**
 * Whether a tilt may move the player in or out of fullscreen. Fullscreen asks for
 * SENSOR_LANDSCAPE, which turns the window even under the system rotation lock, so the lock only
 * matters when the viewer has not asked to tilt through it (#1219).
 */
internal fun followsTilt(
    ignoreRotationLock: Boolean,
    autoRotateOn: () -> Boolean,
): Boolean = ignoreRotationLock || autoRotateOn()
