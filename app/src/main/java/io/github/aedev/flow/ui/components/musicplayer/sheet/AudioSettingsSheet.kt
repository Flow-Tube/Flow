package io.github.aedev.flow.ui.components.musicplayer.sheet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.ui.components.equalizer.EqQuickPanel
import io.github.aedev.flow.ui.components.equalizer.PanelRow
import io.github.aedev.flow.ui.components.layout.navigation.LocalMediaNavigator
import io.github.aedev.flow.ui.components.shared.FlowModalSheetDefaults
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.FlowRowGroup
import io.github.aedev.flow.ui.components.shared.FlowSectionHeader
import io.github.aedev.flow.ui.components.shared.FlowSheetHeader
import io.github.aedev.flow.ui.components.shared.FlowSwitchRow
import io.github.aedev.flow.ui.components.shared.MediaPlaybackSpeedPicker
import io.github.aedev.flow.ui.components.shared.flowRowGroupShape
import io.github.aedev.flow.ui.components.shared.rememberFlowSheetState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val TEMPO_ROWS = 3
private const val OUTPUT_ROWS = 2
private const val PITCH_STEPS = 23
private val SliderRowPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

/** The music player's audio settings: the equalizer panel, tempo and pitch, loudness normalisation and offload. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsSheet(onDismiss: () -> Unit) {
    val speed by EnhancedMusicPlayerManager.playbackSpeed.collectAsState()
    val pitch by EnhancedMusicPlayerManager.playbackPitch.collectAsState()
    val navigator = LocalMediaNavigator.current
    val context = LocalContext.current
    val preferences = remember { PlayerPreferences(context) }
    val normalizationEnabled by preferences.musicLoudnessNormalizationEnabled.collectAsState(initial = true)
    val offloadEnabled by preferences.musicAudioOffloadEnabled.collectAsState(initial = false)
    val customSpeedsEnabled by preferences.customSpeedsEnabled.collectAsState(initial = false)
    val customSpeedPresetsRaw by preferences.customSpeedPresets.collectAsState(initial = "")
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberFlowSheetState(),
        modifier = FlowModalSheetDefaults.modifier,
        contentWindowInsets = FlowModalSheetDefaults.contentWindowInsets,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
        ) {
            FlowSheetHeader(title = stringResource(R.string.audio_settings_title), onClose = onDismiss, showDragHandle = false)

            FlowSectionHeader(stringResource(R.string.equalizer))
            EqQuickPanel(
                onEdit = {
                    onDismiss()
                    navigator.openEqualizer()
                },
            )

            FlowSectionHeader(stringResource(R.string.label_tempo_pitch))
            FlowRowGroup {
                PanelRow(shape = flowRowGroupShape(0, TEMPO_ROWS)) {
                    MediaPlaybackSpeedPicker(
                        currentSpeed = speed,
                        sliderEnabled = true,
                        customSpeedsEnabled = customSpeedsEnabled,
                        customSpeedPresetsRaw = customSpeedPresetsRaw,
                        onSpeedSelected = { EnhancedMusicPlayerManager.setPlaybackSpeed(it, persist = false) },
                        onSliderSelectionFinished = { EnhancedMusicPlayerManager.setPlaybackSpeed(it) },
                    )
                }
                PanelRow(shape = flowRowGroupShape(1, TEMPO_ROWS)) {
                    SliderRow(
                        label = stringResource(R.string.template_pitch, pitch.roundToInt()),
                        value = pitch,
                        onValueChange = { EnhancedMusicPlayerManager.setPlaybackPitch(it, persist = false) },
                        onValueChangeFinished = {
                            EnhancedMusicPlayerManager.setPlaybackPitch(
                                EnhancedMusicPlayerManager.playbackPitch.value,
                            )
                        },
                        range = -12f..12f,
                        steps = PITCH_STEPS,
                    )
                }
                FlowNavRow(
                    title = stringResource(R.string.action_reset),
                    onClick = {
                        EnhancedMusicPlayerManager.setPlaybackSpeed(1f)
                        EnhancedMusicPlayerManager.setPlaybackPitch(0f)
                    },
                    leadingIcon = Icons.Filled.Refresh,
                    showChevron = false,
                    shape = flowRowGroupShape(2, TEMPO_ROWS),
                )
            }

            FlowRowGroup(modifier = Modifier.padding(top = 16.dp)) {
                FlowSwitchRow(
                    title = stringResource(R.string.music_normalize_volume_title),
                    supportingText = stringResource(R.string.music_normalize_volume_desc),
                    checked = normalizationEnabled,
                    onCheckedChange = { enabled -> scope.launch { preferences.setMusicLoudnessNormalizationEnabled(enabled) } },
                    leadingIcon = Icons.Filled.GraphicEq,
                    shape = flowRowGroupShape(0, OUTPUT_ROWS),
                )
                FlowSwitchRow(
                    title = stringResource(R.string.music_audio_offload_title),
                    supportingText = stringResource(R.string.music_audio_offload_desc),
                    checked = offloadEnabled,
                    onCheckedChange = { enabled -> scope.launch { preferences.setMusicAudioOffloadEnabled(enabled) } },
                    leadingIcon = Icons.Filled.BatterySaver,
                    shape = flowRowGroupShape(1, OUTPUT_ROWS),
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(SliderRowPadding)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range,
            steps = steps,
        )
    }
}
