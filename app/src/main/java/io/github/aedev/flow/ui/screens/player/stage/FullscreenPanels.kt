package io.github.aedev.flow.ui.screens.player.stage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.media3.common.util.UnstableApi
import io.github.aedev.flow.ui.screens.player.state.PlayerCommentsUiState

/** What a landscape fullscreen player raises over the video instead of a bottom sheet. */
@UnstableApi
@Composable
internal fun BoxScope.FullscreenPanels(
    session: VideoPlayerStageSession,
    sidePanelState: FullscreenSidePanelState,
    commentsUiState: PlayerCommentsUiState,
    videoAspectRatio: Float,
    rememberSubtitleLanguage: (String) -> Unit,
    onNavigateToChannel: (String) -> Unit,
) {
    BackHandler(enabled = sidePanelState.visible, onBack = sidePanelState.close)

    if (sidePanelState.visible) {
        FullscreenSidePanel(
            session = session,
            panelState = sidePanelState,
            commentsUiState = commentsUiState,
            videoAspectRatio = videoAspectRatio,
            rememberSubtitleLanguage = rememberSubtitleLanguage,
            onNavigateToChannel = onNavigateToChannel,
        )
    }
}
