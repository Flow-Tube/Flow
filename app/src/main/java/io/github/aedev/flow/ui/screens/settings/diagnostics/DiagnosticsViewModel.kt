package io.github.aedev.flow.ui.screens.settings.diagnostics

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.profile.ActiveProfile
import io.github.aedev.flow.data.profile.ProfileRegistryState
import io.github.aedev.flow.data.profile.ProfileRepository
import io.github.aedev.flow.data.profile.ProfileSwitcher
import io.github.aedev.flow.data.profile.SlicePart
import io.github.aedev.flow.data.recommendation.snapshot.HomeSnapshotStore
import io.github.aedev.flow.player.error.PlaybackErrorLog
import io.github.aedev.flow.player.stream.ResetYouTubeSession
import io.github.aedev.flow.ui.screens.settings.SettingsViewModel
import io.github.aedev.flow.utils.DeviceInfo
import io.github.aedev.flow.utils.FlowDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val preferences: PlayerPreferences,
        private val snapshots: HomeSnapshotStore,
        private val resetYouTubeSession: ResetYouTubeSession,
        private val profiles: ProfileRepository,
        private val switcher: ProfileSwitcher,
        private val playbackErrors: PlaybackErrorLog,
    ) : SettingsViewModel() {
        val device: DeviceInfo by lazy { FlowDiagnostics.deviceInfo(context) }

        private val _session = MutableStateFlow<LogState>(LogState.Loading)
        private val _crashes = MutableStateFlow<LogState>(LogState.Loading)
        val session: StateFlow<LogState> = _session.asStateFlow()
        val crashes: StateFlow<LogState> = _crashes.asStateFlow()

        private var sessionText = ""

        val recordingSnapshots: StateFlow<Boolean> = preferences.homeSnapshotRecording.asState(false)

        private val _snapshotCount = MutableStateFlow(0)
        val snapshotCount: StateFlow<Int> = _snapshotCount.asStateFlow()

        init {
            viewModelScope.launch { _snapshotCount.value = snapshots.count() }
            viewModelScope.launch(Dispatchers.IO) {
                sessionText = FlowDiagnostics.readSessionLogs()
                _session.value = parseLog(sessionText, ::logcatLevel)
                _crashes.value = parseLog(FlowDiagnostics.crashLogsOrNull(context), ::crashLevel)
            }
        }

        /** Switching recording off deletes the snapshots already kept. */
        fun setRecordingSnapshots(enabled: Boolean) =
            write {
                preferences.setHomeSnapshotRecording(enabled)
                if (!enabled) {
                    snapshots.clear()
                    _snapshotCount.value = 0
                }
            }

        suspend fun exportSnapshot(uri: Uri): Boolean =
            withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { snapshots.exportLatest(it) } ?: false }
                    .getOrDefault(false)
            }

        suspend fun report(): String =
            withContext(Dispatchers.IO) { FlowDiagnostics.buildFullReport(context, sessionText, playbackErrors.recent()) }

        fun clearCrashes() =
            write {
                withContext(Dispatchers.IO) { FlowDiagnostics.clearCrashLogs(context) }
                _crashes.value = LogState.Empty
            }

        suspend fun resetSession() = resetYouTubeSession()

        val profileState: StateFlow<ProfileRegistryState?> = profiles.state.asState(null)
        val runningProfileId: String = ActiveProfile.get(context).id

        fun addProfile(
            name: String,
            copy: Set<SlicePart> = emptySet(),
        ) = write { profiles.create(name, copy = copy) }

        fun deleteProfile(id: String) = write { profiles.delete(id) }

        fun setRunningProfilePhoto(source: Uri) = write { profiles.setPhoto(runningProfileId, source) }

        fun switchProfile(id: String) = write { switcher.switchTo(id) }
    }
