package io.github.aedev.flow.player

import android.app.ActivityManager
import android.content.Context
import io.github.aedev.flow.service.HeadphoneResume

/**
 * The sleep timer's Close app. It ends both players the way closing them yourself does, so their
 * services stop and their notifications go, then removes Flow's tasks. It holds no Activity, so it
 * works however the timer was set and whether or not a screen is still open. The process is left to
 * Android: with nothing running it is only cached.
 */
internal object PlaybackShutdown {
    fun closeApp(context: Context) {
        HeadphoneResume.set(context, allowed = false)
        EnhancedPlayerManager.getInstance().stopBackgroundService()
        GlobalPlayerState.release()
        EnhancedMusicPlayerManager.clearCurrentTrack()
        context.getSystemService(ActivityManager::class.java)?.appTasks?.forEach { it.finishAndRemoveTask() }
    }
}
