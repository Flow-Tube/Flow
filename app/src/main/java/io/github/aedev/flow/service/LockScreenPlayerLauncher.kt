package io.github.aedev.flow.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.aedev.flow.ui.screens.lockscreen.LockScreenPlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Opens [LockScreenPlayerActivity] when the screen turns off, only while the setting is on and music
 * plays. The screen-off receiver is registered just for that time, so nothing listens otherwise.
 * "Display over other apps" is what lets a service start an activity from the background.
 */
internal class LockScreenPlayerLauncher(
    private val context: Context,
) {
    private val playing = MutableStateFlow(false)
    private var registered = false
    private var released = false

    private val screenOff =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) open()
            }
        }

    fun attach(
        scope: CoroutineScope,
        enabled: Flow<Boolean>,
    ) {
        aliveState.value = true
        scope.launch {
            combine(enabled, playing) { on, isPlaying -> on && isPlaying }
                .distinctUntilChanged()
                .collect(::arm)
        }
    }

    fun setPlaying(isPlaying: Boolean) {
        playing.value = isPlaying
    }

    fun release() {
        arm(false)
        released = true
        aliveState.value = false
    }

    private fun arm(armed: Boolean) {
        if (armed == registered || (armed && released)) return
        if (armed) {
            ContextCompat.registerReceiver(context, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            context.unregisterReceiver(screenOff)
        }
        registered = armed
    }

    private fun open() {
        if (!Settings.canDrawOverlays(context)) return
        runCatching { context.startActivity(LockScreenPlayerActivity.intent(context)) }
            .onFailure { Log.w(TAG, "Lock screen player did not open", it) }
    }

    companion object {
        private const val TAG = "LockScreenPlayer"
        private val aliveState = MutableStateFlow(false)

        /** Whether the music service is up; the lock screen player closes when it goes. */
        val serviceAlive: StateFlow<Boolean> = aliveState.asStateFlow()
    }
}
