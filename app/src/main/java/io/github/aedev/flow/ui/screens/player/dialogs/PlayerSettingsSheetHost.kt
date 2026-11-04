package io.github.aedev.flow.ui.screens.player.dialogs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.PictureInPictureHelper
import io.github.aedev.flow.player.dlna.DlnaCastManager
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.ui.components.videoplayer.settings.SettingsMenuDialog
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModel
import io.github.aedev.flow.ui.screens.player.saveChosenPlaybackSpeed
import io.github.aedev.flow.ui.screens.player.state.PlayerScreenState
import io.github.aedev.flow.ui.screens.player.state.PlayerSheet
import io.github.aedev.flow.ui.screens.player.state.SubtitleSelection
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The single wiring of the player settings sheet. Both the bottom-sheet slot over the portrait
 * player and the landscape fullscreen side panel render it; [asSidePanel] is the only thing that
 * separates the two, alongside the height each has to fill.
 *
 * @param pipAspectRatio aspect ratio to hand the PiP request, or null to let the helper pick one.
 */
@Composable
internal fun PlayerSettingsSheetHost(
    screenState: PlayerScreenState,
    playerState: EnhancedPlayerState,
    uiState: VideoPlayerUiState,
    viewModel: VideoPlayerViewModel,
    playerPreferences: PlayerPreferences,
    scope: CoroutineScope,
    rememberPlaybackSpeed: Boolean,
    ambientModeEnabled: Boolean,
    groupedQualitySelectorEnabled: Boolean,
    rememberSubtitleLanguage: (String) -> Unit,
    asSidePanel: Boolean,
    expandedHeight: Dp?,
    onDismiss: () -> Unit,
    collapsedHeight: Dp = 0.dp,
    pipAspectRatio: Float? = null,
    onSheetProgressChange: (Float) -> Unit = {},
) {
    val context = LocalContext.current
    val playerManager = EnhancedPlayerManager.getInstance()
    val sponsorSegments by playerManager.sponsorSegments.collectAsState()
    val sponsorBlockOffForVideo by playerManager.sponsorBlockOffForVideo.collectAsState()
    val sponsorBlockEnabled by playerPreferences.sponsorBlockEnabled.collectAsState(initial = false)
    val videoNotesEnabled by viewModel.videoNotesEnabled.collectAsState()
    val subtitleFilePicker =
        rememberLauncherForActivityResult(OpenSubtitleFile()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                if (!viewModel.addSubtitleFile(uri)) {
                    Toast.makeText(context, R.string.subtitle_add_file_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

    SettingsMenuDialog(
        playerState = playerState,
        autoplayEnabled = uiState.autoplayEnabled,
        subtitlesEnabled = playerState.selectedSubtitleUrl != null,
        initialPage = screenState.settingsPage,
        onDismiss = onDismiss,
        onQualitySelected = { option ->
            EnhancedPlayerManager.getInstance().switchQuality(option)
        },
        onAudioTrackSelected = { index ->
            EnhancedPlayerManager.getInstance().switchAudioTrack(index)
            SubtitleSelection
                .subtitleFollowingAudio(
                    subtitles = playerState.availableSubtitles,
                    audioLanguage = playerState.availableAudioTracks.getOrNull(index)?.language,
                    selectedUrl = playerState.selectedSubtitleUrl,
                )?.let { subtitleIndex ->
                    SubtitleSelection.applyAt(
                        subtitles = playerState.availableSubtitles,
                        index = subtitleIndex,
                        rememberLanguage = rememberSubtitleLanguage,
                    )
                }
        },
        onSpeedSelected = { speed ->
            EnhancedPlayerManager.getInstance().setPlaybackSpeed(speed)
            screenState.normalSpeed = speed
            scope.saveChosenPlaybackSpeed(
                playerPreferences = playerPreferences,
                speed = speed,
                rememberPlaybackSpeed = rememberPlaybackSpeed,
                channelId = uiState.cachedVideo?.channelId,
            )
        },
        selectedSubtitleUrl = playerState.selectedSubtitleUrl,
        onSubtitleSelected = { index ->
            SubtitleSelection.applyAt(
                subtitles = playerState.availableSubtitles,
                index = index,
                rememberLanguage = rememberSubtitleLanguage,
            )
        },
        onDisableSubtitles = { SubtitleSelection.disable() },
        onSubtitleOffsetChange = viewModel::setSubtitleOffset,
        onAddSubtitleFile =
            {
                scope.launch { subtitleFilePicker.launch(viewModel.subtitleFolder()) }
                Unit
            }.takeIf { uiState.localFilePath != null },
        onAutoplayToggle = { viewModel.toggleAutoplay(it) },
        onSkipSilenceToggle = { viewModel.toggleSkipSilence(it) },
        onVolumeNormalizationToggle = { viewModel.toggleVolumeNormalization(it) },
        subtitleStyle = screenState.subtitleStyle,
        onSubtitleStyleChange = { style ->
            screenState.subtitleStyle = style
            scope.launch { playerPreferences.setSubtitleStyle(style) }
        },
        onLoopToggle = { viewModel.toggleLoop(it) },
        ambientModeEnabled = ambientModeEnabled,
        onAmbientModeToggle = { scope.launch { playerPreferences.setVideoAmbientModeEnabled(it) } },
        onStatsForNerds = { screenState.showStatsForNerds = true },
        onCastClick = {
            DlnaCastManager.startDiscovery(context)
            screenState.open(PlayerSheet.Dlna)
        },
        onPipClick = {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                PictureInPictureHelper.isPlayerPopupSupported(context)
            ) {
                onDismiss()
                PictureInPictureHelper.requestPlayerPipMode(
                    activity = context as ComponentActivity,
                    aspectRatio = pipAspectRatio ?: PictureInPictureHelper.currentVideoAspectRatio,
                    isPlaying = playerState.isPlaying,
                )
            }
        },
        onSleepTimerClick = { screenState.open(PlayerSheet.SleepTimer) },
        sponsorBlockSegmentCount = if (sponsorBlockEnabled) sponsorSegments.count { it.endTime > it.startTime } else 0,
        sponsorBlockOffForVideo = sponsorBlockOffForVideo,
        onSponsorBlockToggle = playerManager::setSponsorBlockOffForVideo,
        notePositionMs = screenState.currentPosition.takeIf { videoNotesEnabled && !playerState.isLive },
        onAddNote = { screenState.open(PlayerSheet.Note) },
        expandedHeight = expandedHeight,
        collapsedHeight = collapsedHeight,
        enableVerticalDismiss = !asSidePanel,
        useGroupedQualitySelector = groupedQualitySelectorEnabled,
        onSheetProgressChange = onSheetProgressChange,
        modifier = if (asSidePanel) Modifier.fillMaxSize() else Modifier,
    )
}

/**
 * The system file picker, opening in the given folder. Subtitle files have no reliable MIME type, so
 * every file is offered and the extension decides.
 */
private class OpenSubtitleFile : ActivityResultContract<Uri?, Uri?>() {
    private val openDocument = ActivityResultContracts.OpenDocument()

    override fun createIntent(
        context: Context,
        input: Uri?,
    ): Intent =
        openDocument.createIntent(context, arrayOf("*/*")).apply {
            input?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Uri? = openDocument.parseResult(resultCode, intent)
}
