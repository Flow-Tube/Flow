package io.github.aedev.flow.ui.screens.lockscreen

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.aedev.flow.data.local.AppFontPreferences
import io.github.aedev.flow.data.local.LocalDataManager
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.service.LockScreenPlayerLauncher
import io.github.aedev.flow.ui.startup.FlowTheme
import io.github.aedev.flow.ui.startup.themeSettings
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The song over the lock screen (#1859): stock Android stopped drawing album art there in Android 11.
 * Opened by the music service when the screen turns off during music; it never leads into the app,
 * and it closes on unlock, when the queue is cleared, or when the music service stops.
 */
@AndroidEntryPoint
class LockScreenPlayerActivity : ComponentActivity() {
    @Inject
    lateinit var appFontPreferences: AppFontPreferences

    private val unlocked =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        enableEdgeToEdge()
        ContextCompat.registerReceiver(this, unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        finishWhenMusicStops()

        val theme = LocalDataManager(applicationContext).themeSettings(appFontPreferences)
        setContent {
            val settings = theme.collectAsState(initial = null).value ?: return@setContent
            FlowTheme(settings) {
                LockScreenPlayerScreen(onUnlock = ::unlock)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Woken without a lock (no lock screen, or within the lock delay): nothing to sit over.
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        if (interactive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) finish()
    }

    override fun onDestroy() {
        unregisterReceiver(unlocked)
        super.onDestroy()
    }

    private fun finishWhenMusicStops() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(EnhancedMusicPlayerManager.currentTrack, LockScreenPlayerLauncher.serviceAlive) { track, alive ->
                    track == null || !alive
                }.collect { stopped -> if (stopped) finish() }
            }
        }
    }

    private fun unlock() {
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = finish()
            },
        )
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, LockScreenPlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
