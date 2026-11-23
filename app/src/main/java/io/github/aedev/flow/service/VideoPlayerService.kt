package io.github.aedev.flow.service

import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.EntryPointAccessors
import io.github.aedev.flow.R
import io.github.aedev.flow.notification.NotificationHelper
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.PopupPlayerWindow
import io.github.aedev.flow.player.error.PlayerDiagnostics
import io.github.aedev.flow.utils.FlowCrashHandler
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@UnstableApi
class VideoPlayerService : MediaSessionService() {
    companion object {
        private const val TAG = "VideoPlayerService"
        const val ACTION_SHOW_POPUP = "io.github.aedev.flow.action.SHOW_POPUP_PLAYER"
        const val ACTION_HIDE_POPUP = "io.github.aedev.flow.action.HIDE_POPUP_PLAYER"

        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_VIDEO_TITLE = "video_title"
        const val EXTRA_VIDEO_CHANNEL = "video_channel"
        const val EXTRA_VIDEO_THUMBNAIL = "video_thumbnail"
    }

    private var popupPlayerWindow: PopupPlayerWindow? = null

    private fun serviceSnapshot(): String {
        val player = EnhancedPlayerManager.getInstance().getPlayer()
        val state =
            when (player?.playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                null -> "NO_PLAYER"
                else -> "UNKNOWN(${player.playbackState})"
            }
        return "exo=$state pwr=${player?.playWhenReady} playing=${player?.isPlaying} " +
            "pos=${player?.currentPosition}/${player?.duration} " +
            "idx=${player?.currentMediaItemIndex} count=${player?.mediaItemCount} " +
            "ongoing=${runCatching { isPlaybackOngoing() }.getOrDefault(false)}"
    }

    private fun serviceLog(message: String) {
        val full = "$message | ${serviceSnapshot()}"
        Log.w(TAG, full)
        PlayerDiagnostics.logWarning(TAG, full)
    }

    override fun onCreate() {
        super.onCreate()
        val notificationProvider =
            DefaultMediaNotificationProvider
                .Builder(this)
                .setChannelId(NotificationHelper.CHANNEL_PLAYBACK)
                .setChannelName(R.string.notification_channel_video_playback)
                .setNotificationId(NotificationHelper.NOTIFICATION_PLAYBACK)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification_logo) }
        setMediaNotificationProvider(notificationProvider)
        recordForegroundStartFailures("video-service")

        FlowCrashHandler.recordPhase("video-service", "onCreate")
        serviceLog("onCreate")

        EnhancedPlayerManager.getInstance().initialize(applicationContext)

        lifecycleScope.launch {
            // A headphone Play after a video must not start the saved music queue (#1450).
            EnhancedPlayerManager
                .getInstance()
                .playerState
                .map { it.isPlaying }
                .distinctUntilChanged()
                .filter { it }
                .collect { HeadphoneResume.set(this@VideoPlayerService, allowed = false) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        val manager = EnhancedPlayerManager.getInstance()
        manager.initialize(applicationContext)
        serviceLog("onGetSession controller=${controllerInfo.packageName}")
        if (controllerInfo.declinesMediaButtonStart(hasMediaItem = manager.getPlayer()?.currentMediaItem != null)) {
            serviceLog("onGetSession declined: media button with nothing to play")
            return null
        }
        return manager.getVideoMediaSession()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val startResult = super.onStartCommand(intent, flags, startId)
        FlowCrashHandler.recordPhase("video-service", "onStartCommand action=${intent?.action}")
        serviceLog("onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_SHOW_POPUP -> showPopupPlayer()
            ACTION_HIDE_POPUP -> popupPlayerWindow?.dismiss()
        }

        return startResult
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val root = rootIntent?.component?.className
        if (isSideTaskRemoval(root)) {
            serviceLog("onTaskRemoved ignored, side task $root")
            return
        }
        serviceLog("onTaskRemoved root=$root pip=${GlobalPlayerState.isInPipMode.value}")
        if (GlobalPlayerState.isInPipMode.value) {
            GlobalPlayerState.requestDismiss()
            EnhancedPlayerManager.getInstance().stop()
            stopSelf()
            return
        }

        val keepPlayingAfterSwipe =
            EntryPointAccessors
                .fromApplication(applicationContext, PlaybackServiceEntryPoint::class.java)
                .lifecyclePlaybackPreferences()
                .settings.keepPlayingAfterSwipe
        val player = EnhancedPlayerManager.getInstance().getPlayer()
        if (player?.keepsPlayingOnTaskRemoved(keepPlayingAfterSwipe, isPlaybackOngoing()) == true) return

        EnhancedPlayerManager.getInstance().stop()
        // Unlike a bare stopSelf(), this clears Media3's user-engaged state, so the pause cannot put
        // the stopped service back into the foreground (#1025).
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        serviceLog("onDestroy")
        popupPlayerWindow?.dismiss()
        popupPlayerWindow = null
        // lifecycleScope is cancelled by super.onDestroy() dispatching ON_DESTROY.
        super.onDestroy()
    }

    private fun showPopupPlayer() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !android.provider.Settings.canDrawOverlays(this)) return
        val player = EnhancedPlayerManager.getInstance().getPlayer() ?: return
        if (popupPlayerWindow == null) {
            popupPlayerWindow =
                PopupPlayerWindow(this) {
                    popupPlayerWindow?.dismiss()
                    EnhancedPlayerManager.getInstance().stop()
                    stopSelf()
                }
        }
        popupPlayerWindow?.show(player)
    }
}
