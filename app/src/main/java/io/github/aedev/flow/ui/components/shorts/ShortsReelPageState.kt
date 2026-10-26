package io.github.aedev.flow.ui.components.shorts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.ShortsPlayerUiMode

internal const val SHORTS_PLAYBACK_LOOP = "loop"
internal const val SHORTS_PLAYBACK_AUTO_NEXT = "auto_next"
internal const val SHORTS_PLAYBACK_AUTO_INTERVAL = "auto_interval"

@Immutable
internal data class ShortsReelActions(
    val onChannelClick: () -> Unit,
    val onCommentsClick: () -> Unit,
    val onDescriptionClick: () -> Unit,
    val onShareClick: () -> Unit,
    val onMoreClick: () -> Unit,
    val onVideoEnded: () -> Unit = {},
)

@Immutable
internal data class ShortsReelSettings(
    val playbackMode: String,
    val autoScrollSeconds: Int,
    val style: ShortsOverlayStyle,
    val ambientModeEnabled: Boolean,
    val playbackSpeed: Float,
    val groupedQualitySelectorEnabled: Boolean,
    val customSpeedsEnabled: Boolean,
    val customSpeedPresetsRaw: String,
    val speedSliderEnabled: Boolean,
)

@Stable
internal class ShortsReelPageState {
    var isPlaying by mutableStateOf(false)
    var currentPosition by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var isBuffering by mutableStateOf(false)
    var showPauseIndicator by mutableStateOf(false)
    var showLikeAnimation by mutableStateOf(false)
    var isFastForwarding by mutableStateOf(false)
    var hasStartedPlaying by mutableStateOf(false)
    var isDragging by mutableStateOf(false)
    var dragProgress by mutableFloatStateOf(0f)
}

@Stable
internal class ShortsReelSessionState {
    var hasRecordedWatched by mutableStateOf(false)
    var hasTouchedHistory by mutableStateOf(false)
    var lastProgressSavedAt by mutableLongStateOf(0L)
    var showOnDemandControls by mutableStateOf(false)
    var hasReportedDwell by mutableStateOf(false)
}

@Stable
internal class ShortsReelAutoAdvanceState {
    var hasAutoAdvanced by mutableStateOf(false)

    /** An advance that came due while a sheet was open, held back until the sheet is gone. */
    var deferredWhileSheetOpen by mutableStateOf(false)
}

@Composable
internal fun rememberShortsReelSettings(playerPreferences: PlayerPreferences): ShortsReelSettings {
    val playbackMode by playerPreferences.shortsPlaybackMode.collectAsState(initial = SHORTS_PLAYBACK_LOOP)
    val autoScrollSeconds by playerPreferences.shortsAutoScrollSeconds.collectAsState(initial = 10)
    val uiMode by playerPreferences.shortsPlayerUiMode.collectAsState(initial = ShortsPlayerUiMode.DEFAULT)
    val ambientModeEnabled by playerPreferences.videoAmbientModeEnabled.collectAsState(initial = false)
    val playbackSpeed by playerPreferences.shortsPlaybackSpeed.collectAsState(initial = 1f)
    val groupedQualitySelectorEnabled by playerPreferences.groupedQualitySelectorEnabled.collectAsState(initial = false)
    val customSpeedsEnabled by playerPreferences.customSpeedsEnabled.collectAsState(initial = false)
    val customSpeedPresetsRaw by playerPreferences.customSpeedPresets.collectAsState(initial = "")
    val speedSliderEnabled by playerPreferences.speedSliderEnabled.collectAsState(initial = false)

    return ShortsReelSettings(
        playbackMode = playbackMode,
        autoScrollSeconds = autoScrollSeconds,
        style = ShortsOverlayStyle.from(uiMode),
        ambientModeEnabled = ambientModeEnabled,
        playbackSpeed = playbackSpeed,
        groupedQualitySelectorEnabled = groupedQualitySelectorEnabled,
        customSpeedsEnabled = customSpeedsEnabled,
        customSpeedPresetsRaw = customSpeedPresetsRaw,
        speedSliderEnabled = speedSliderEnabled,
    )
}
