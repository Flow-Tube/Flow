package io.github.aedev.flow.player

import android.app.Activity
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Rational
import androidx.annotation.RequiresApi
import io.github.aedev.flow.MainActivity
import io.github.aedev.flow.R
import io.github.aedev.flow.service.VideoPlayerService
import kotlin.math.roundToInt

/**
 * Helper class for Picture-in-Picture mode support
 */
object PictureInPictureHelper {
    const val ACTION_PLAY = "io.github.aedev.flow.action.PIP_PLAY"
    const val ACTION_PAUSE = "io.github.aedev.flow.action.PIP_PAUSE"
    const val ACTION_PREVIOUS = "io.github.aedev.flow.action.PIP_PREVIOUS"
    const val ACTION_NEXT = "io.github.aedev.flow.action.PIP_NEXT"
    const val ACTION_CLOSE = "io.github.aedev.flow.action.PIP_CLOSE"
    const val ACTION_BACKGROUND = "io.github.aedev.flow.action.PIP_BACKGROUND"

    private const val REQUEST_CODE_PLAY = 1
    private const val REQUEST_CODE_PAUSE = 2
    private const val REQUEST_CODE_PREVIOUS = 3
    private const val REQUEST_CODE_NEXT = 4
    private const val REQUEST_CODE_CLOSE = 5
    private const val REQUEST_CODE_BACKGROUND = 6
    private const val ACTION_PICTURE_IN_PICTURE_SETTINGS = "android.settings.PICTURE_IN_PICTURE_SETTINGS"

    /** The video player view's bounds in the window; the PiP source hint is the video's part of it. */
    @Volatile
    internal var videoViewBounds: PipBounds? = null
        private set

    private class ArmedParams(
        val isPlaying: Boolean,
        val navigation: PipQueueNavigation?,
        val offerBackground: Boolean,
    )

    @Volatile
    private var armedParams: ArmedParams? = null

    /**
     * Records where the player view sits. Auto-enter uses the params already handed to the window, so
     * when they are armed they are handed over again with the new source hint, or the system finds an
     * empty hint and scales the whole page into the window.
     */
    internal fun setVideoViewBounds(
        activity: Activity?,
        bounds: PipBounds?,
    ) {
        if (bounds == videoViewBounds) return
        videoViewBounds = bounds
        val armed = armedParams?.takeIf { isAutoEnterArmed } ?: return
        if (activity == null || bounds == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (activity.isInPictureInPictureMode || GlobalPlayerState.isEnteringPip.value) return
        updatePipParams(
            activity = activity,
            isPlaying = armed.isPlaying,
            autoEnterEnabled = true,
            navigation = armed.navigation,
            offerBackground = armed.offerBackground,
        )
    }

    /** Published by the Shorts pager so the window it enters PiP with already carries skip buttons. */
    @Volatile
    var shortsNavigation: PipQueueNavigation? = null
        private set

    internal fun setShortsNavigation(navigation: PipQueueNavigation?) {
        shortsNavigation = navigation
    }

    /** Published by the video player so every way into PiP shows Next as it stands right now. */
    @Volatile
    var videoHasNext: Boolean = false
        private set

    internal fun setVideoHasNext(hasNext: Boolean) {
        videoHasNext = hasNext
    }

    @Volatile
    var currentVideoAspectRatio: Float = DEFAULT_VIDEO_ASPECT_RATIO
        private set

    @Volatile
    var isPopupActive: Boolean = false
        private set

    /** Whether the params last given to the window let Android 12+ enter PiP on its own when the user leaves. */
    @Volatile
    var isAutoEnterArmed: Boolean = false
        private set

    /**
     * True when leaving the app hands PiP to the system's own entry animation; entering by hand on top of
     * it scales the whole page into the window instead of morphing the video.
     */
    fun systemEntersPipOnLeave(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isAutoEnterArmed

    internal fun setPopupActive(active: Boolean) {
        isPopupActive = active
    }

    /**
     * Check if the device supports PiP
     */
    fun isPipSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun isPlayerPopupSupported(context: Context): Boolean = isPipSupported(context) || Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    fun isPipAllowed(context: Context): Boolean {
        if (!isPipSupported(context)) return false
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return true
        return try {
            val mode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                        Process.myUid(),
                        context.packageName,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                        Process.myUid(),
                        context.packageName,
                    )
                }
            mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
        } catch (_: Exception) {
            true
        }
    }

    fun openPipSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val packageUri = Uri.parse("package:${activity.packageName}")
        val intents =
            listOf(
                Intent(ACTION_PICTURE_IN_PICTURE_SETTINGS, packageUri),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
            )
        for (intent in intents) {
            runCatching {
                activity.startActivity(intent)
                return
            }
        }
    }

    /**
     * Enter Picture-in-Picture mode with the given aspect ratio
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun enterPipMode(
        activity: Activity,
        aspectRatio: Float = currentVideoAspectRatio,
        isPlaying: Boolean = true,
        autoEnterEnabled: Boolean = false,
        navigation: PipQueueNavigation? = null,
        offerBackground: Boolean = false,
    ): Boolean {
        if (!isPipSupported(activity)) return false

        return try {
            val params =
                buildPipParams(
                    activity,
                    aspectRatio,
                    isPlaying,
                    autoEnterEnabled,
                    navigation,
                    offerBackground,
                )
            activity.enterPictureInPictureMode(params)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun requestPlayerPipMode(
        activity: Activity,
        aspectRatio: Float = currentVideoAspectRatio,
        isPlaying: Boolean = true,
        autoEnterEnabled: Boolean = false,
    ): Boolean {
        if (isPipSupported(activity)) {
            if (!isPipAllowed(activity)) {
                openPipSettings(activity)
                return false
            }
            val entered =
                if (activity is MainActivity) {
                    activity.enterPlayerPictureInPictureMode(
                        aspectRatio = aspectRatio,
                        isPlaying = isPlaying,
                        openSettingsOnDenied = true,
                    )
                } else {
                    enterPipMode(
                        activity = activity,
                        aspectRatio = aspectRatio,
                        isPlaying = isPlaying,
                        autoEnterEnabled = autoEnterEnabled,
                    )
                }
            if (entered) return true
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        if (!Settings.canDrawOverlays(activity)) {
            openOverlaySettings(activity)
            return false
        }
        // A plain start: the activity is still in the foreground here, and Media3 promotes the
        // service itself once the popup plays. A foreground start would crash a paused popup (#1309).
        activity.startService(
            Intent(activity, VideoPlayerService::class.java).setAction(VideoPlayerService.ACTION_SHOW_POPUP),
        )
        return true
    }

    fun dismissPopup(context: Context) {
        if (!isPopupActive) return
        context.startService(
            Intent(context, VideoPlayerService::class.java).setAction(VideoPlayerService.ACTION_HIDE_POPUP),
        )
    }

    private fun openOverlaySettings(activity: Activity) {
        val intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            } else {
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}"),
                )
            }
        runCatching { activity.startActivity(intent) }
            .onFailure {
                activity.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")),
                )
            }
    }

    /**
     * Update PiP params (for playback state changes)
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun updatePipParams(
        activity: Activity,
        aspectRatio: Float = currentVideoAspectRatio,
        isPlaying: Boolean = true,
        autoEnterEnabled: Boolean = false,
        navigation: PipQueueNavigation? = null,
        offerBackground: Boolean = false,
    ) {
        if (!isPipSupported(activity)) return
        armedParams = ArmedParams(isPlaying, navigation, offerBackground).takeIf { autoEnterEnabled }

        try {
            val params =
                buildPipParams(
                    activity,
                    aspectRatio,
                    isPlaying,
                    autoEnterEnabled,
                    navigation,
                    offerBackground,
                )
            activity.setPictureInPictureParams(params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(
        context: Context,
        requestedAspectRatio: Float,
        isPlaying: Boolean,
        autoEnterEnabled: Boolean,
        navigation: PipQueueNavigation?,
        offerBackground: Boolean,
    ): PictureInPictureParams {
        val safeAspectRatio = sanitizePipAspectRatio(requestedAspectRatio)
        currentVideoAspectRatio = safeAspectRatio
        val aspectRatio = Rational((safeAspectRatio * 1_000).roundToInt(), 1_000)

        val maxActions = (context as? Activity)?.maxNumPictureInPictureActions ?: Int.MAX_VALUE
        val actions =
            pipActionSpecs(isPlaying, navigation, maxActions, offerBackground, videoHasNext)
                .map { createRemoteAction(context, it) }

        val builder =
            PictureInPictureParams
                .Builder()
                .setAspectRatio(aspectRatio)
                .setActions(actions)

        videoViewBounds
            ?.let { pipSourceBounds(it, safeAspectRatio) }
            ?.let { builder.setSourceRectHint(android.graphics.Rect(it.left, it.top, it.right, it.bottom)) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnterEnabled)
            builder.setSeamlessResizeEnabled(false)
        }
        isAutoEnterArmed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && autoEnterEnabled

        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createRemoteAction(
        context: Context,
        spec: PipActionSpec,
    ): RemoteAction {
        val (iconResId, titleResId, action, requestCode) =
            when (spec.control) {
                PipControl.BACKGROUND -> {
                    PipButton(
                        R.drawable.ic_pip_listen_background,
                        R.string.pip_listen_in_background,
                        ACTION_BACKGROUND,
                        REQUEST_CODE_BACKGROUND,
                    )
                }

                PipControl.PREVIOUS -> {
                    PipButton(
                        android.R.drawable.ic_media_previous,
                        R.string.previous,
                        ACTION_PREVIOUS,
                        REQUEST_CODE_PREVIOUS,
                    )
                }

                PipControl.PLAY -> {
                    PipButton(android.R.drawable.ic_media_play, R.string.play, ACTION_PLAY, REQUEST_CODE_PLAY)
                }

                PipControl.PAUSE -> {
                    PipButton(android.R.drawable.ic_media_pause, R.string.pause, ACTION_PAUSE, REQUEST_CODE_PAUSE)
                }

                PipControl.NEXT -> {
                    PipButton(android.R.drawable.ic_media_next, R.string.next, ACTION_NEXT, REQUEST_CODE_NEXT)
                }
            }
        val title = context.getString(titleResId)
        val intent =
            Intent(action).apply {
                setPackage(context.packageName)
            }
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                flags,
            )

        return RemoteAction(
            Icon.createWithResource(context, iconResId),
            title,
            title,
            pendingIntent,
        ).apply { isEnabled = spec.enabled }
    }

    private data class PipButton(
        val iconResId: Int,
        val titleResId: Int,
        val action: String,
        val requestCode: Int,
    )

    /**
     * Create a broadcast receiver for PiP actions
     */
    fun createPipActionReceiver(
        onPlay: () -> Unit,
        onPause: () -> Unit,
        onPrevious: () -> Unit = {},
        onNext: () -> Unit = {},
        onClose: () -> Unit = {},
        onBackground: () -> Unit = {},
    ): BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                when (intent?.action) {
                    ACTION_PLAY -> onPlay()
                    ACTION_PAUSE -> onPause()
                    ACTION_PREVIOUS -> onPrevious()
                    ACTION_NEXT -> onNext()
                    ACTION_CLOSE -> onClose()
                    ACTION_BACKGROUND -> onBackground()
                }
            }
        }

    /**
     * Get the intent filter for PiP actions
     */
    fun getPipIntentFilter(): IntentFilter =
        IntentFilter().apply {
            addAction(ACTION_PLAY)
            addAction(ACTION_PAUSE)
            addAction(ACTION_PREVIOUS)
            addAction(ACTION_NEXT)
            addAction(ACTION_CLOSE)
            addAction(ACTION_BACKGROUND)
        }
}
