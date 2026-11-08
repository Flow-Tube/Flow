package io.github.aedev.flow.ui.screens.player.stage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.shared.FlowNoteEditorDialog
import io.github.aedev.flow.ui.screens.player.dialogs.PlayerBottomSheetsContainer
import io.github.aedev.flow.ui.screens.player.dialogs.PlayerDialogsContainer
import io.github.aedev.flow.ui.screens.player.dialogs.SbSubmitSegmentDialog
import io.github.aedev.flow.ui.screens.player.state.MediaSheetHeights
import io.github.aedev.flow.ui.screens.player.state.PlayerCommentsUiState
import io.github.aedev.flow.ui.screens.player.state.PlayerLayoutMode
import io.github.aedev.flow.ui.screens.player.state.PlayerSheet
import io.github.aedev.flow.ui.screens.player.state.selectCommentSort

/** Every dialog and bottom sheet the player overlay raises above its own stage. */
@UnstableApi
@Composable
internal fun VideoPlayerDialogs(
    session: VideoPlayerStageSession,
    completeVideo: Video,
    mediaSheetHeights: MediaSheetHeights,
    onMediaSheetProgressChange: (Float) -> Unit,
    canUseFullscreenSidePanel: Boolean,
    playerLayoutMode: PlayerLayoutMode,
    commentsUiState: PlayerCommentsUiState,
    onNavigateToChannel: (String) -> Unit,
    onNavigateToShorts: (String) -> Unit,
    onClose: () -> Unit,
) {
    val video = session.video
    val screenState = session.screenState
    val playerState = session.playerState
    val playerUiState = session.uiState
    val playerViewModel = session.viewModel
    val prefs = session.prefs
    val hostedInSidePanel = canUseFullscreenSidePanel || playerLayoutMode == PlayerLayoutMode.WIDE

    // Dialogs
    PlayerDialogsContainer(
        screenState = screenState,
        playerState = playerState,
        uiState = playerUiState,
        video = completeVideo,
        viewModel = playerViewModel,
        prefs = prefs,
        hostedInSidePanel = hostedInSidePanel,
        mediaSheetExpandedHeight = mediaSheetHeights.expanded,
        mediaSheetCollapsedHeight = mediaSheetHeights.collapsed,
        onMediaSheetProgressChange = onMediaSheetProgressChange,
    )

    // SB Submit dialog
    if (screenState.activeSheet == PlayerSheet.SbSubmit) {
        val initialPosition = remember { screenState.currentPosition }
        SbSubmitSegmentDialog(
            videoId = video.id,
            currentPositionMs = initialPosition,
            onDismiss = { screenState.closeSheet() },
            onSubmitted = { playerViewModel.reloadSponsorSegments(video.id) },
        )
    }

    if (screenState.activeSheet == PlayerSheet.Note) {
        val note by playerViewModel.videoNote.collectAsStateWithLifecycle()
        val insertPositionMs = remember { screenState.currentPosition.takeUnless { video.isLive } }
        FlowNoteEditorDialog(
            initialText = note.orEmpty(),
            title = stringResource(R.string.note_video_title),
            onSave = { text -> playerViewModel.saveVideoNote(video.id, text) },
            onDismiss = { screenState.closeSheet() },
            insertPositionMs = insertPositionMs,
        )
    }

    // Bottom Sheets
    PlayerBottomSheetsContainer(
        screenState = screenState,
        uiState = playerUiState,
        video = video,
        completeVideo = completeVideo,
        disableShortsPlayer = prefs.disableShortsPlayer,
        showShortsPlayerPrompt = prefs.showShortsPlayerPrompt,
        viewModel = playerViewModel,
        playerState = playerState,
        prefs = prefs,
        commentsUiState = commentsUiState,
        commentsEnabled = prefs.commentsEnabled,
        onLoadMoreComments = { videoId -> playerViewModel.loadMoreComments(videoId) },
        onSelectCommentSort = { filter ->
            commentsUiState.selectCommentSort(filter, video.id, screenState, playerViewModel)
        },
        mediaSheetExpandedHeight = mediaSheetHeights.expanded,
        mediaSheetCollapsedHeight = mediaSheetHeights.collapsed,
        onPlayAsShort = { videoId ->
            onClose()
            onNavigateToShorts(videoId)
        },
        onLoadReplies = { comment ->
            playerViewModel.loadCommentReplies(comment)
        },
        onLoadMoreReplies = { comment ->
            playerViewModel.loadMoreCommentReplies(comment)
        },
        onNavigateToChannel = { channelId ->
            onNavigateToChannel(channelId)
        },
        hostedInSidePanel = hostedInSidePanel,
        onMediaSheetProgressChange = onMediaSheetProgressChange,
    )
}
