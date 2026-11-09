package io.github.aedev.flow.ui.screens.player.stage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.media3.common.util.UnstableApi
import io.github.aedev.flow.ui.screens.player.state.PlayerCommentsUiState
import io.github.aedev.flow.ui.screens.player.state.PlayerSheet

/** What a landscape fullscreen player raises over the video instead of a bottom sheet. */
@UnstableApi
@Composable
internal fun BoxScope.FullscreenPanels(
    session: VideoPlayerStageSession,
    sidePanelState: FullscreenSidePanelState,
    canUseFullscreenSidePanel: Boolean,
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

    val screenState = session.screenState
    val pullingMoreVideos by remember(screenState) { derivedStateOf { screenState.moreVideosDragY > 0f } }
    if (canUseFullscreenSidePanel && (pullingMoreVideos || screenState.activeSheet == PlayerSheet.MoreVideos)) {
        FullscreenMoreVideos(
            screenState = screenState,
            videos = remember(session.uiState.relatedVideos) { moreVideosFrom(session.uiState.relatedVideos) },
            onVideoClick = { video -> session.viewModel.playVideo(video, userOpened = false) },
        )
    }
}
