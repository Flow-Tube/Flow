package io.github.aedev.flow.ui.screens.update

import android.content.Intent
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.update.AppRelease
import io.github.aedev.flow.data.update.ReleaseNotes
import io.github.aedev.flow.data.update.UpdateFailure
import io.github.aedev.flow.data.update.UpdateRepository
import io.github.aedev.flow.data.update.parseReleaseNotes
import io.github.aedev.flow.updater.AppUpdateInstaller
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

@Immutable
data class UpdateUiState(
    val release: AppRelease? = null,
    val notes: ReleaseNotes? = null,
    val stage: UpdateStage = UpdateStage.Available,
    val loading: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class UpdateViewModel
    @Inject
    constructor(
        private val updates: UpdateRepository,
        private val installer: AppUpdateInstaller,
    ) : ViewModel() {
        private val canInstall = MutableStateFlow(installer.canInstall())
        private val loading = MutableStateFlow(updates.latest.value == null)

        val state: StateFlow<UpdateUiState> =
            combine(
                updates.latest.flatMapLatest { release ->
                    if (release == null) {
                        flowOf(UpdateUiState())
                    } else {
                        val notes = parseReleaseNotes(release.notes)
                        combine(
                            installer.state(release.version),
                            canInstall,
                            installer.installPhase,
                        ) { download, allowed, install ->
                            UpdateUiState(release, notes, updateStage(download, allowed, install))
                        }
                    }
                },
                loading,
            ) { state, isLoading -> state.copy(loading = isLoading && state.release == null) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UpdateUiState(loading = loading.value))

        init {
            if (loading.value) {
                viewModelScope.launch {
                    runCatching { updates.fetch() }
                    loading.value = false
                }
            }
        }

        /** Runs the action the current stage offers; the permission stage is handled by the screen. */
        fun primaryAction() {
            val current = state.value
            val release = current.release ?: return
            when (val stage = current.stage) {
                UpdateStage.Available -> installer.download(release)
                is UpdateStage.Failed -> if (stage.reason == UpdateFailure.INSTALL) install(release) else installer.download(release)
                UpdateStage.Ready -> install(release)
                else -> Unit
            }
        }

        fun cancelDownload() = installer.cancel()

        fun installPermissionIntent(): Intent = installer.installPermissionIntent()

        fun skip() {
            val release = state.value.release ?: return
            viewModelScope.launch { updates.skip(release.version) }
            installer.cancel()
        }

        /** Re-reads what may have changed while the user was in system settings or the installer. */
        fun onResume() {
            canInstall.value = installer.canInstall()
            installer.reconcile()
        }

        private fun install(release: AppRelease) {
            viewModelScope.launch { installer.install(release.version) }
        }
    }
