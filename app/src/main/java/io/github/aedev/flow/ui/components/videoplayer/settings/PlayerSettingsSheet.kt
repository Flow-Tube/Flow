package io.github.aedev.flow.ui.components.videoplayer.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.player.EnhancedPlayerState
import io.github.aedev.flow.player.QualityOption
import io.github.aedev.flow.ui.components.equalizer.EqQuickPanel
import io.github.aedev.flow.ui.components.layout.navigation.LocalMediaNavigator
import io.github.aedev.flow.ui.components.shared.FlowBottomSheet
import io.github.aedev.flow.ui.components.shared.FlowSheetHeader
import io.github.aedev.flow.ui.components.shared.defaultSheetExpandedHeight
import io.github.aedev.flow.ui.components.shared.rememberFlowBottomSheetState
import io.github.aedev.flow.ui.components.videoplayer.subtitle.SubtitleCustomizer
import io.github.aedev.flow.ui.components.videoplayer.subtitle.SubtitleStyle

@Composable
fun SettingsMenuDialog(
    playerState: EnhancedPlayerState,
    autoplayEnabled: Boolean,
    subtitlesEnabled: Boolean,
    onDismiss: () -> Unit,
    initialPage: PlayerSettingsPage = PlayerSettingsPage.Main,
    onQualitySelected: (QualityOption) -> Unit = {},
    onAudioTrackSelected: (Int) -> Unit = {},
    onSpeedSelected: (Float) -> Unit = {},
    selectedSubtitleUrl: String? = null,
    onSubtitleSelected: (Int) -> Unit = {},
    onDisableSubtitles: () -> Unit = {},
    onAddSubtitleFile: (() -> Unit)? = null,
    onSubtitleOffsetChange: ((Long) -> Unit)? = null,
    onAutoplayToggle: (Boolean) -> Unit,
    onSkipSilenceToggle: (Boolean) -> Unit,
    onVolumeNormalizationToggle: (Boolean) -> Unit,
    subtitleStyle: SubtitleStyle,
    onSubtitleStyleChange: (SubtitleStyle) -> Unit,
    onLoopToggle: (Boolean) -> Unit,
    ambientModeEnabled: Boolean = false,
    onAmbientModeToggle: (Boolean) -> Unit = {},
    ambientGlowEnabled: Boolean? = null,
    onAmbientGlowToggle: (Boolean) -> Unit = {},
    sphericalViewEnabled: Boolean? = null,
    onSphericalViewToggle: (Boolean) -> Unit = {},
    sphericalMotionEnabled: Boolean? = null,
    onSphericalMotionToggle: (Boolean) -> Unit = {},
    onCastClick: () -> Unit = {},
    onPipClick: () -> Unit = {},
    onSleepTimerClick: () -> Unit = {},
    onStatsForNerds: () -> Unit = {},
    sponsorBlockSegmentCount: Int = 0,
    sponsorBlockOffForVideo: Boolean = false,
    onSponsorBlockToggle: (off: Boolean) -> Unit = {},
    notePositionMs: Long? = null,
    onAddNote: () -> Unit = {},
    expandedHeight: Dp? = null,
    collapsedHeight: Dp = 0.dp,
    enableVerticalDismiss: Boolean = true,
    useGroupedQualitySelector: Boolean = false,
    onSheetProgressChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberFlowBottomSheetState()
    var currentPage by remember { mutableStateOf(initialPage) }
    var subtitleStyleReturnPage by remember { mutableStateOf(PlayerSettingsPage.Main) }
    val currentTitle =
        when (currentPage) {
            PlayerSettingsPage.Main -> stringResource(R.string.player_settings)
            PlayerSettingsPage.Quality -> stringResource(R.string.video_quality_title)
            PlayerSettingsPage.Speed -> stringResource(R.string.playback_speed)
            PlayerSettingsPage.Audio -> stringResource(R.string.audio_track)
            PlayerSettingsPage.Subtitles -> stringResource(R.string.filter_subtitles)
            PlayerSettingsPage.SubtitleStyle -> stringResource(R.string.subtitle_style)
            PlayerSettingsPage.Equalizer -> stringResource(R.string.equalizer)
        }
    val backPage =
        when (currentPage) {
            PlayerSettingsPage.Main -> null
            PlayerSettingsPage.SubtitleStyle -> subtitleStyleReturnPage
            else -> PlayerSettingsPage.Main
        }
    // A panel host swaps the panel's content in place, so a row that opens another surface must not
    // run the exit animation there: the host's onDismiss would close the panel under the new surface.
    val leaveFor: (() -> Unit) -> Unit =
        if (enableVerticalDismiss) {
            { after -> sheetState.dismiss(after) }
        } else {
            { after -> after() }
        }

    LaunchedEffect(initialPage) {
        currentPage = initialPage
    }

    FlowBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        state = sheetState,
        expandedHeight = expandedHeight ?: defaultSheetExpandedHeight(),
        collapsedHeight = collapsedHeight,
        dismissible = enableVerticalDismiss,
        dismissOnOutsideTap = false,
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.surface,
        onBack = backPage?.let { page -> { currentPage = page } },
        onProgressChange = onSheetProgressChange,
        header = { dragModifier ->
            FlowSheetHeader(
                inSidePane = !enableVerticalDismiss,
                title = currentTitle,
                onClose = { sheetState.dismiss() },
                modifier = dragModifier,
                onBack = backPage?.let { page -> { currentPage = page } },
                contentPadding = PaddingValues(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
                closeButtonSize = null,
                dividerAlpha = 0.4f,
            )
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = SheetContentVerticalPadding),
        ) {
            when (currentPage) {
                PlayerSettingsPage.Main -> {
                    PlayerSettingsMainPage(
                        playerState = playerState,
                        autoplayEnabled = autoplayEnabled,
                        subtitlesEnabled = subtitlesEnabled,
                        ambientModeEnabled = ambientModeEnabled,
                        onNavigateToPage = { currentPage = it },
                        onShowSubtitleStyle = {
                            subtitleStyleReturnPage = PlayerSettingsPage.Main
                            currentPage = PlayerSettingsPage.SubtitleStyle
                        },
                        onCastClick = { leaveFor(onCastClick) },
                        onPipClick = { sheetState.dismiss(onPipClick) },
                        onSleepTimerClick = { leaveFor(onSleepTimerClick) },
                        onStatsForNerds = { leaveFor(onStatsForNerds) },
                        onLoopToggle = onLoopToggle,
                        onAutoplayToggle = onAutoplayToggle,
                        onSkipSilenceToggle = onSkipSilenceToggle,
                        onVolumeNormalizationToggle = onVolumeNormalizationToggle,
                        onAmbientModeToggle = onAmbientModeToggle,
                        ambientGlowEnabled = ambientGlowEnabled,
                        onAmbientGlowToggle = onAmbientGlowToggle,
                        sponsorBlockSegmentCount = sponsorBlockSegmentCount,
                        sponsorBlockOffForVideo = sponsorBlockOffForVideo,
                        onSponsorBlockToggle = onSponsorBlockToggle,
                        notePositionMs = notePositionMs,
                        onAddNote = onAddNote,
                        sphericalViewEnabled = sphericalViewEnabled,
                        onSphericalViewToggle = onSphericalViewToggle,
                        sphericalMotionEnabled = sphericalMotionEnabled,
                        onSphericalMotionToggle = onSphericalMotionToggle,
                    )
                }

                PlayerSettingsPage.Quality -> {
                    PlayerSettingsQualityPage(
                        availableQualities = playerState.availableQualities,
                        currentQuality = playerState.currentQuality,
                        currentQualityKey = playerState.currentQualityKey,
                        useGroupedQualitySelector = useGroupedQualitySelector,
                        onQualitySelected = {
                            onQualitySelected(it)
                            sheetState.dismiss()
                        },
                    )
                }

                PlayerSettingsPage.Speed -> {
                    PlayerSettingsSpeedPage(
                        currentSpeed = playerState.playbackSpeed,
                        onSpeedSelected = onSpeedSelected,
                        onSpeedSelectionFinished = { sheetState.dismiss() },
                    )
                }

                PlayerSettingsPage.Audio -> {
                    PlayerSettingsAudioPage(
                        availableAudioTracks = playerState.availableAudioTracks,
                        currentAudioTrack = playerState.currentAudioTrack,
                        onTrackSelected = {
                            onAudioTrackSelected(it)
                            sheetState.dismiss()
                        },
                    )
                }

                PlayerSettingsPage.Equalizer -> {
                    val navigator = LocalMediaNavigator.current
                    EqQuickPanel(
                        onEdit = { sheetState.dismiss { navigator.openEqualizer() } },
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    )
                }

                PlayerSettingsPage.Subtitles -> {
                    PlayerSettingsSubtitlesPage(
                        availableSubtitles = playerState.availableSubtitles,
                        selectedSubtitleUrl = selectedSubtitleUrl,
                        subtitlesEnabled = subtitlesEnabled,
                        onSubtitleSelected = { index ->
                            onSubtitleSelected(index)
                            sheetState.dismiss()
                        },
                        onDisableSubtitles = {
                            onDisableSubtitles()
                            sheetState.dismiss()
                        },
                        // Stays open: the picker's result comes back to this composition.
                        onAddSubtitleFile = onAddSubtitleFile,
                        subtitleOffsetMs = playerState.subtitleOffsetMs,
                        onSubtitleOffsetChange = onSubtitleOffsetChange,
                        onShowStyleCustomizer = {
                            subtitleStyleReturnPage = PlayerSettingsPage.Subtitles
                            currentPage = PlayerSettingsPage.SubtitleStyle
                        },
                    )
                }

                PlayerSettingsPage.SubtitleStyle -> {
                    SubtitleCustomizer(
                        currentStyle = subtitleStyle,
                        onStyleChange = onSubtitleStyleChange,
                    )
                }
            }
        }
    }
}

enum class PlayerSettingsPage {
    Main,
    Quality,
    Speed,
    Audio,
    Subtitles,
    SubtitleStyle,
    Equalizer,
}

private val SheetContentVerticalPadding = 12.dp
