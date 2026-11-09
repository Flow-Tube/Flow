package io.github.aedev.flow.ui.tv.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.EnhancedPlayerState
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModel
import io.github.aedev.flow.ui.screens.player.dialogs.PlayerChaptersSheetHost
import io.github.aedev.flow.ui.screens.player.dialogs.PlayerCommentsPanelHost
import io.github.aedev.flow.ui.screens.player.dialogs.PlayerDescriptionSheetHost
import io.github.aedev.flow.ui.screens.player.dialogs.PlayerLiveChatColumn
import io.github.aedev.flow.ui.screens.player.dialogs.PlayerQueueSheetHost
import io.github.aedev.flow.ui.screens.player.dialogs.PlayerSettingsSheetHost
import io.github.aedev.flow.ui.screens.player.dialogs.PlayerSleepTimerSheetHost
import io.github.aedev.flow.ui.screens.player.dialogs.PlayerTranscriptSheetHost
import io.github.aedev.flow.ui.screens.player.state.PlayerCommentsUiState
import io.github.aedev.flow.ui.screens.player.state.PlayerScreenState
import io.github.aedev.flow.ui.screens.player.state.PlayerSheet
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerPreferencesState
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.ui.screens.player.state.hasTranscriptTrack
import io.github.aedev.flow.ui.tv.components.TvSidePanelSurface
import kotlinx.coroutines.launch

/**
 * TV's player panels: whichever of the phone player's sheets the shared screen state has open, in
 * the side-panel form the phone's landscape fullscreen player gives them, behind a focus trap. A
 * sheet's own Back steps between its pages; [onClose] runs when it is done.
 */
@Composable
internal fun BoxScope.TvPlayerSheets(
    video: Video,
    screenState: PlayerScreenState,
    playerState: EnhancedPlayerState,
    uiState: VideoPlayerUiState,
    viewModel: VideoPlayerViewModel,
    prefs: VideoPlayerPreferencesState,
    commentsUiState: PlayerCommentsUiState,
    onNavigateToChannel: (String) -> Unit,
    onClose: () -> Unit,
) {
    val sheet = screenState.activeSheet
    // The panel keeps drawing the sheet it closed while it slides away.
    val lastSheet = remember { mutableStateOf<PlayerSheet>(PlayerSheet.None) }
    SideEffect { if (sheet != PlayerSheet.None) lastSheet.value = sheet }
    val shownSheet = if (sheet != PlayerSheet.None) sheet else lastSheet.value
    val scope = rememberCoroutineScope()
    val motion = MaterialTheme.motionScheme

    AnimatedVisibility(
        visible = sheet != PlayerSheet.None,
        modifier = Modifier.align(Alignment.CenterEnd),
        enter = fadeIn(motion.defaultEffectsSpec()) + slideInHorizontally(motion.defaultSpatialSpec()) { it / 3 },
        exit = fadeOut(motion.fastEffectsSpec()) + slideOutHorizontally(motion.fastSpatialSpec()) { it / 3 },
    ) {
        TvSidePanelSurface(
            title = null,
            onClose = null,
            keepsFocus = { transition.targetState == EnterExitState.Visible },
            color = MaterialTheme.colorScheme.surface,
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val panelHeight = maxHeight
                when (shownSheet) {
                    is PlayerSheet.Settings -> {
                        PlayerSettingsSheetHost(
                            screenState = screenState,
                            playerState = playerState,
                            uiState = uiState,
                            viewModel = viewModel,
                            playerPreferences = prefs.preferences,
                            scope = scope,
                            rememberPlaybackSpeed = prefs.rememberPlaybackSpeed,
                            ambientModeEnabled = prefs.ambientModeEnabled,
                            groupedQualitySelectorEnabled = prefs.groupedQualitySelectorEnabled,
                            rememberSubtitleLanguage = { language ->
                                scope.launch { prefs.preferences.setPreferredSubtitleLanguage(language) }
                            },
                            asSidePanel = true,
                            expandedHeight = panelHeight,
                            onDismiss = onClose,
                        )
                    }

                    PlayerSheet.Chapters -> {
                        PlayerChaptersSheetHost(
                            screenState = screenState,
                            chapters = uiState.chapters,
                            thumbnailUrl = video.thumbnailUrl,
                            asSidePanel = true,
                            expandedHeight = panelHeight,
                            onDismiss = onClose,
                        )
                    }

                    PlayerSheet.Description -> {
                        PlayerDescriptionSheetHost(
                            video = video,
                            uiState = uiState,
                            viewModel = viewModel,
                            asSidePanel = true,
                            expandedHeight = panelHeight,
                            onDismiss = onClose,
                            onChaptersClick = { screenState.open(PlayerSheet.Chapters) },
                            onTranscriptClick = { screenState.open(PlayerSheet.Transcript) },
                            onChannelClick = onNavigateToChannel,
                            hasTranscriptTrack = hasTranscriptTrack(playerState),
                        )
                    }

                    PlayerSheet.Transcript -> {
                        PlayerTranscriptSheetHost(
                            viewModel = viewModel,
                            screenState = screenState,
                            playerState = playerState,
                            prefs = prefs,
                            chapters = uiState.chapters,
                            artworkUrl = video.thumbnailUrl,
                            asSidePanel = true,
                            expandedHeight = panelHeight,
                            onDismiss = onClose,
                        )
                    }

                    PlayerSheet.Queue -> {
                        PlayerQueueSheetHost(asSidePanel = true, expandedHeight = panelHeight, onDismiss = onClose)
                    }

                    PlayerSheet.SleepTimer -> {
                        PlayerSleepTimerSheetHost(asSidePanel = true, expandedHeight = panelHeight, onDismiss = onClose)
                    }

                    is PlayerSheet.LiveChat -> {
                        PlayerLiveChatColumn(
                            messages = uiState.liveChatMessages,
                            isLoading = uiState.isLiveChatLoading,
                            onClose = onClose,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    is PlayerSheet.Comments -> {
                        PlayerCommentsPanelHost(
                            videoId = video.id,
                            screenState = screenState,
                            viewModel = viewModel,
                            commentsUiState = commentsUiState,
                            artworkUrl = video.thumbnailUrl,
                            onNavigateToChannel = onNavigateToChannel,
                            onClose = onClose,
                            modifier = Modifier.fillMaxSize(),
                            canFollowVideo = false,
                        )
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }
    }
}
