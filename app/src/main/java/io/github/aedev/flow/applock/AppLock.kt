package io.github.aedev.flow.applock

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether Flow must ask for the phone's unlock before showing anything. It locks at the first start
 * of the process and after Flow has been away from the screen for the chosen time. PiP is not away:
 * the wait starts there, but only opening the full app can lock. Main thread only.
 */
@Singleton
class AppLock
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val preferences: PlayerPreferences,
        @ApplicationScope private val scope: CoroutineScope,
    ) : DefaultLifecycleObserver {
        private val away = AwayClock()
        private var authenticating = false
        private var checkAfterAuthentication = false

        private val _settings = MutableStateFlow<AppLockSettings?>(null)
        val settings: StateFlow<AppLockSettings?> = _settings.asStateFlow()

        private val _locked = MutableStateFlow<Boolean?>(null)

        /** Null until the setting is first read, so nothing is shown or opened before then. */
        val locked: StateFlow<Boolean?> = _locked.asStateFlow()

        fun install() {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            scope.launch(Dispatchers.Main.immediate) {
                preferences.appLockSettings.collect { current ->
                    val isFirstRead = _settings.value == null
                    _settings.value = current
                    when {
                        isFirstRead -> _locked.value = current.enabled && isDeviceSecure()
                        !current.enabled -> unlock()
                    }
                }
            }
        }

        /** The lock needs a PIN, pattern or password on the phone; without one there's nothing to ask for. */
        fun isDeviceSecure(): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

        suspend fun awaitUnlocked() {
            locked.first { it == false }
        }

        /** The phone's credential screen can be an activity of its own, and leaving for it is not leaving Flow. */
        fun beginAuthentication() {
            authenticating = true
        }

        fun endAuthentication(succeeded: Boolean) {
            authenticating = false
            val check = checkAfterAuthentication
            checkAfterAuthentication = false
            when {
                succeeded -> unlock()
                check -> lockIfAwayTooLong()
            }
        }

        /** A lock the phone can no longer ask for, because its screen lock was removed, gives way. */
        fun unlockIfDeviceInsecure() {
            if (!isDeviceSecure()) unlock()
        }

        fun onPictureInPictureChanged(isInPictureInPicture: Boolean) {
            if (isInPictureInPicture) away.left(now()) else lockIfAwayTooLong()
        }

        /** The flag lasts one trip out and back, so a prompt that never answers can't switch the lock off for good. */
        override fun onStart(owner: LifecycleOwner) {
            if (authenticating) {
                authenticating = false
                checkAfterAuthentication = true
            } else {
                lockIfAwayTooLong()
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            away.left(now())
        }

        private fun lockIfAwayTooLong() {
            val current = _settings.value ?: return
            val awayTooLong = away.returned(now(), current.delayMinutes)
            if (current.enabled && awayTooLong && isDeviceSecure()) _locked.value = true
        }

        private fun unlock() {
            away.reset()
            _locked.value = false
        }

        private fun now(): Long = SystemClock.elapsedRealtime()
    }
