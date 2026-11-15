package io.github.aedev.flow.ui.screens.update

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.data.update.UpdateRepository
import io.github.aedev.flow.data.update.UpdateSchedule
import io.github.aedev.flow.ui.startup.LaunchPrompts
import io.github.aedev.flow.updater.AppUpdateInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val KEY_CHECKED = "update_launch_checked"

/** Whether the activity was started to open something (a link, a share, a file) rather than the app itself. */
internal fun launchOpensContent(
    action: String?,
    hasData: Boolean,
): Boolean = hasData || action == Intent.ACTION_VIEW || action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE

/**
 * Runs the launch update check once per activity, and opens the update page while a newer version is out that
 * is neither skipped nor downloading or downloaded. The flag lives in saved state, so a restored activity is not
 * treated as a new launch.
 */
@HiltViewModel
class UpdateLaunchViewModel
    @Inject
    constructor(
        private val updates: UpdateRepository,
        private val installer: AppUpdateInstaller,
        private val prompts: LaunchPrompts,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        private val _openUpdate = MutableStateFlow(false)
        val openUpdate: StateFlow<Boolean> = _openUpdate.asStateFlow()

        fun check(openedForContent: Boolean) {
            if (savedState.get<Boolean>(KEY_CHECKED) == true) return
            savedState[KEY_CHECKED] = true
            if (BuildConfig.DEBUG || !BuildConfig.UPDATER_ENABLED || openedForContent) {
                prompts.updateCheckFinished(shownUpdate = false)
                return
            }
            viewModelScope.launch {
                val release = updates.releaseForLaunch()
                val open =
                    release != null &&
                        UpdateSchedule.shouldOpenOnLaunch(
                            release.version,
                            updates.skippedVersion(),
                            installer.state(release.version).first(),
                        )
                prompts.updateCheckFinished(shownUpdate = open)
                if (open) _openUpdate.value = true
            }
        }

        fun consumeOpenUpdate() {
            _openUpdate.value = false
        }
    }

/** Checks for an update once onboarding is done, and opens the update page when there is one to show. */
@Composable
fun UpdateLaunchEffect(
    needsOnboarding: Boolean?,
    onOpenUpdate: () -> Unit,
) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    val viewModel: UpdateLaunchViewModel = hiltViewModel(activity)
    val openUpdate by viewModel.openUpdate.collectAsStateWithLifecycle()
    // Read once: incoming-intent handling strips extras later, but the launch's action and data stay.
    val openedForContent = remember(activity) { launchOpensContent(activity.intent?.action, activity.intent?.data != null) }
    LaunchedEffect(needsOnboarding) { if (needsOnboarding == false) viewModel.check(openedForContent) }
    LaunchedEffect(openUpdate) {
        if (openUpdate) {
            viewModel.consumeOpenUpdate()
            onOpenUpdate()
        }
    }
}
