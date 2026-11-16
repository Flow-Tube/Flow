package io.github.aedev.flow.ui.screens.settings

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation
import io.github.aedev.flow.ui.screens.settings.index.DestinationIndex
import io.github.aedev.flow.ui.screens.settings.index.NewSettingsPolicy
import io.github.aedev.flow.ui.screens.settings.index.SettingsIndex
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import javax.inject.Inject

/** Records the settings this build has, and reads which of them are new (#1676). */
@HiltViewModel
class NewSettingsViewModel
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
    ) : SettingsViewModel() {
        val firstSeen = preferences.settingsFirstSeen.asState(null)

        /** The days a new setting keeps its dot; 0 while highlighting is off, which marks nothing. */
        val days =
            combine(preferences.newSettingsHighlight, preferences.newSettingsHighlightDays) { on, days -> if (on) days else 0 }
                .asState(0)

        init {
            write {
                val keys = SettingsIndex.all.map { it.key }
                preferences.updateSettingsFirstSeen { NewSettingsPolicy.record(it, keys, LocalDate.now().toEpochDay()) }
            }
        }
    }

/** The keys of the settings and pages to mark as new on this device, for [LocalNewSettings]. */
@Composable
internal fun rememberNewSettings(viewModel: NewSettingsViewModel = hiltViewModel()): Set<String> {
    val firstSeen by viewModel.firstSeen.collectAsStateWithLifecycle()
    val days by viewModel.days.collectAsStateWithLifecycle()
    val onTv = LocalDpadNavigation.current
    return remember(firstSeen, days, onTv) {
        val shown =
            SettingsIndex.all
                .filter {
                    it.availability.isAvailable(
                        githubFeatures = BuildConfig.UPDATER_ENABLED,
                        sdk = Build.VERSION.SDK_INT,
                        tv = onTv,
                    )
                }.filter { DestinationIndex.isReachable(it, onTv) }
        NewSettingsPolicy.newKeys(firstSeen, shown, LocalDate.now().toEpochDay(), days)
    }
}
