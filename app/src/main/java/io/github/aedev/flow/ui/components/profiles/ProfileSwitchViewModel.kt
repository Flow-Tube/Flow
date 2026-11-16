package io.github.aedev.flow.ui.components.profiles

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.profile.ActiveProfile
import io.github.aedev.flow.data.profile.ProfileRegistryState
import io.github.aedev.flow.data.profile.ProfileRepository
import io.github.aedev.flow.data.profile.ProfileSwitcher
import io.github.aedev.flow.data.profile.SwitchRefusal
import io.github.aedev.flow.data.profile.SwitchResult
import io.github.aedev.flow.ui.components.layout.topbar.TopBarAccountSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Who is in and the way to someone else, for the profile avatar in a top bar. A switch that works restarts the app. */
@HiltViewModel
class ProfileSwitchViewModel
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val profiles: ProfileRepository,
        private val switcher: ProfileSwitcher,
        preferences: PlayerPreferences,
    ) : ViewModel() {
        val state: StateFlow<ProfileRegistryState?> = profiles.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        /** Whether the top bars show the avatar and whether its menu holds Settings; null until read. */
        val topBarAccount: StateFlow<TopBarAccountSettings?> =
            combine(preferences.showProfileInTopBar, preferences.settingsInProfileMenu, ::TopBarAccountSettings)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        val runningId: String = ActiveProfile.get(context).id

        /** This is the first process after a switch: what a cold start would ask has been answered. */
        val afterSwitch: Boolean = ActiveProfile.get(context).afterSwitch

        /** Why the last switch didn't happen; null while there is nothing to say. */
        private val _refused = MutableStateFlow<SwitchRefused?>(null)
        val refused: StateFlow<SwitchRefused?> = _refused.asStateFlow()

        private var switching = false

        fun switchTo(id: String) {
            if (switching || id == runningId) return
            switching = true
            viewModelScope.launch {
                val result =
                    try {
                        switcher.switchTo(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                if (result !is SwitchResult.Restarting) _refused.value = SwitchRefused((result as? SwitchResult.Refused)?.reason)
                switching = false
            }
        }

        /** Keeps the running profile's splash tone in the registry, for a switch back to it. */
        fun recordSplashTone(tone: String) {
            viewModelScope.launch { profiles.setSplashTone(runningId, tone) }
        }

        fun consumeRefused() {
            _refused.value = null
        }
    }

/** A switch that didn't happen, and why, when the switcher said. */
data class SwitchRefused(
    val reason: SwitchRefusal?,
)
