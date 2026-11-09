package io.github.aedev.flow.ui.screens.settings.security

import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.applock.AppLock
import io.github.aedev.flow.applock.AppLockSettings
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel
    @Inject
    constructor(
        val appLock: AppLock,
        private val preferences: PlayerPreferences,
    ) : SettingsViewModel() {
        val settings: StateFlow<AppLockSettings?> = appLock.settings

        fun isDeviceSecure(): Boolean = appLock.isDeviceSecure()

        fun setEnabled(enabled: Boolean) = write { preferences.setAppLockEnabled(enabled) }

        fun setDelayMinutes(minutes: Int) = write { preferences.setAppLockDelayMinutes(minutes) }

        fun setHideInRecents(hide: Boolean) = write { preferences.setAppLockHideInRecents(hide) }
    }
