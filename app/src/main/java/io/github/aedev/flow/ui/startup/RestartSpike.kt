package io.github.aedev.flow.ui.startup

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.window.SplashScreen
import com.jakewharton.processphoenix.ProcessPhoenix
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.MainActivity
import java.io.File

/**
 * Debug-only measurement for profiles plan PR 2.0: restarts the app by ProcessPhoenix or by the platform route on an
 * adb intent, and logs the time from the restart call to the new process's first content frame.
 */
object RestartSpike {
    private const val TAG = "RestartSpike"
    const val EXTRA_METHOD = "io.github.aedev.flow.extra.DEBUG_RESTART"

    fun handle(
        activity: Activity,
        intent: Intent,
    ): Boolean {
        if (!BuildConfig.DEBUG) return false
        val method = intent.getStringExtra(EXTRA_METHOD) ?: return false
        intent.removeExtra(EXTRA_METHOD)
        marker(activity).writeText("$method ${SystemClock.elapsedRealtime()}")
        Log.i(TAG, "begin method=$method pid=${Process.myPid()}")
        when (method) {
            "phoenix" -> {
                ProcessPhoenix.triggerRebirth(activity, Intent(activity, MainActivity::class.java))
            }

            "platform" -> {
                activity.startActivity(Intent.makeRestartActivityTask(ComponentName(activity, MainActivity::class.java)))
                Runtime.getRuntime().exit(0)
            }

            "platform_icon" -> {
                val options =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ActivityOptions.makeBasic().setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_ICON).toBundle()
                    } else {
                        null
                    }
                activity.startActivity(Intent.makeRestartActivityTask(ComponentName(activity, MainActivity::class.java)), options)
                Runtime.getRuntime().exit(0)
            }
        }
        return true
    }

    fun contentShown(context: Context) {
        if (!BuildConfig.DEBUG) return
        val marker = marker(context)
        if (!marker.exists()) return
        val (method, startedAt) = marker.readText().split(' ')
        marker.delete()
        Log.i(TAG, "frame method=$method pid=${Process.myPid()} ms=${SystemClock.elapsedRealtime() - startedAt.toLong()}")
    }

    private fun marker(context: Context) = File(context.noBackupFilesDir, "restart_spike")
}
