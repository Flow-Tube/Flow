package io.github.aedev.flow.ui.screens.player.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.model.LiveChatMessage
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.model.toVideo
import io.github.aedev.flow.data.model.uploadDateMillis
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.player.state.SubtitleOption
import io.github.aedev.flow.ui.components.shared.FlowDescriptionBottomSheet
import io.github.aedev.flow.ui.components.shared.FlowNoteEditorDialog
import io.github.aedev.flow.ui.components.shared.MediaDownloadPrompt
import io.github.aedev.flow.ui.components.shared.MediaSheetSlot
import io.github.aedev.flow.ui.components.shared.MediaSleepTimerSheet
import io.github.aedev.flow.ui.components.shared.commentTimestampToMs
import io.github.aedev.flow.ui.components.shared.defaultSheetExpandedHeight
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionUndo
import io.github.aedev.flow.ui.components.shared.quickactions.sharedQuickActionsViewModel
import io.github.aedev.flow.ui.components.shared.rememberDateDisplaySettings
import io.github.aedev.flow.ui.components.videoplayer.sheet.FlowChaptersBottomSheet
import io.github.aedev.flow.ui.components.videoplayer.sheet.FlowPlaylistQueueBottomSheet
import io.github.aedev.flow.ui.components.videoplayer.sheet.FlowTranscriptBottomSheet
import io.github.aedev.flow.ui.components.videoplayer.sheet.LiveChatList
import io.github.aedev.flow.ui.components.videoplayer.sheet.PlayerCommentsPanel
import io.github.aedev.flow.ui.components.videoplayer.sheet.TranscriptChapter
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModel
import io.github.aedev.flow.ui.screens.player.state.PlayerCommentsUiState
import io.github.aedev.flow.ui.screens.player.state.PlayerScreenState
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.ui.screens.player.state.selectCommentSort
import io.github.aedev.flow.ui.screens.player.state.visibleComments
import io.github.aedev.flow.utils.DateContext
import org.schabi.newpipe.extractor.stream.StreamSegment

/**
 * The surfaces the portrait bottom-sheet slot, the landscape fullscreen side panel and the tablet
 * detail column all raise. Each is wired once here; `asSidePanel` picks the drawer geometry (no
 * vertical dismiss, fills the drawer) over the bottom-sheet geometry.
 */
@Composable
internal fun PlayerChaptersSheetHost(
    screenState: PlayerScreenState,
    chapters: List<StreamSegment>,
    thumbnailUrl: String,
    asSidePanel: Boolean,
    expandedHeight: Dp?,
    onDismiss: () -> Unit,
    collapsedHeight: Dp = 0.dp,
    onSheetProgressChange: (Float) -> Unit = {},
) {
    val chaptersPositionMs by remember {
        derivedStateOf { (screenState.currentPosition / 1_000L) * 1_000L }
    }
    FlowChaptersBottomSheet(
        chapters = chapters,
        currentPosition = chaptersPositionMs,
        durationMs = screenState.duration,
        onChapterClick = { newPosition ->
            EnhancedPlayerManager.getInstance().seekTo(newPosition)
        },
        onDismiss = onDismiss,
        thumbnailUrl = thumbnailUrl,
        expandedHeight = expandedHeight,
        collapsedHeight = collapsedHeight,
        enableVerticalDismiss = !asSidePanel,
        onSheetProgressChange = onSheetProgressChange,
        modifier = if (asSidePanel) Modifier.fillMaxSize() else Modifier,
    )
}

@Composable
internal fun PlayerQueueSheetHost(
    asSidePanel: Boolean,
    expandedHeight: Dp?,
    onDismiss: () -> Unit,
    collapsedHeight: Dp = 0.dp,
    onSheetProgressChange: (Float) -> Unit = {},
    loadStreamsInPlayer: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
) {
    val manager = EnhancedPlayerManager.getInstance()
    val queueVideos by manager.queueVideos.collectAsStateWithLifecycle(initialValue = emptyList())
    val currentQueueIndex by manager.currentQueueIndexState.collectAsStateWithLifecycle(initialValue = -1)
    val playerState by manager.playerState.collectAsStateWithLifecycle()
    val quickActions = sharedQuickActionsViewModel()
    val removedFromQueue = stringResource(R.string.removed_from_queue)

    FlowPlaylistQueueBottomSheet(
        queueVideos = queueVideos,
        currentQueueIndex = currentQueueIndex,
        playlistTitle = playerState.queueTitle,
        isLooping = playerState.isQueueLooping,
        isShuffled = playerState.isQueueShuffled,
        onLoopToggle = manager::toggleQueueLoop,
        onShuffleToggle = manager::toggleQueueShuffle,
        onPlayVideoAtIndex = { index -> manager.playVideoAtIndex(index, loadStreamsInPlayer = loadStreamsInPlayer) },
        onRemoveVideoAtIndex = { index ->
            manager.removeVideoAtIndex(index)?.let { removed ->
                quickActions.announce(removedFromQueue, QuickActionUndo.QueueRemoval(removed))
            }
        },
        onMoveVideoAtIndex = manager::moveVideoAtIndex,
        onDismiss = onDismiss,
        expandedHeight = expandedHeight,
        collapsedHeight = collapsedHeight,
        enableVerticalDismiss = !asSidePanel,
        onSheetProgressChange = onSheetProgressChange,
        modifier = if (asSidePanel) Modifier.fillMaxSize() else Modifier,
        footer = footer,
    )
}

@Composable
internal fun PlayerDescriptionSheetHost(
    video: Video,
    uiState: VideoPlayerUiState,
    viewModel: VideoPlayerViewModel,
    asSidePanel: Boolean,
    expandedHeight: Dp?,
    onDismiss: () -> Unit,
    onChaptersClick: (() -> Unit)? = null,
    onTranscriptClick: (() -> Unit)? = null,
    onChannelClick: ((String) -> Unit)? = null,
    hasTranscriptTrack: Boolean = false,
    collapsedHeight: Dp = 0.dp,
    onSheetProgressChange: (Float) -> Unit = {},
) {
    val descriptionPage by viewModel.descriptionState.collectAsStateWithLifecycle()
    val videoNote by viewModel.videoNote.collectAsStateWithLifecycle()
    val videoNotesEnabled by viewModel.videoNotesEnabled.collectAsStateWithLifecycle()
    var showNoteEditor by rememberSaveable(video.id) { mutableStateOf(false) }
    LaunchedEffect(video.id) {
        viewModel.loadDescription(video.id)
    }
    FlowDescriptionBottomSheet(
        video = video,
        descriptionPage = descriptionPage,
        tags = (listOfNotNull(video.category) + video.tags).distinct(),
        chapterCount = uiState.chapters.size,
        onChaptersClick = onChaptersClick,
        onTranscriptClick = onTranscriptClick?.takeIf { hasTranscriptTrack },
        note = videoNote.takeIf { videoNotesEnabled },
        onEditNote = { showNoteEditor = true }.takeIf { videoNotesEnabled },
        onChannelClick = onChannelClick,
        artworkUrl = video.thumbnailUrl,
        onSeekMs = { EnhancedPlayerManager.getInstance().seekTo(it) },
        expandedHeight = expandedHeight,
        collapsedHeight = collapsedHeight,
        enableVerticalDismiss = !asSidePanel,
        onSheetProgressChange = onSheetProgressChange,
        onDismiss = onDismiss,
        modifier = if (asSidePanel) Modifier.fillMaxSize() else Modifier,
    )

    if (showNoteEditor && videoNotesEnabled) {
        val insertPositionMs = remember { EnhancedPlayerManager.getInstance().getCurrentPosition().takeUnless { video.isLive } }
        FlowNoteEditorDialog(
            initialText = videoNote.orEmpty(),
            title = stringResource(R.string.note_video_title),
            onSave = { text -> viewModel.saveVideoNote(video.id, text) },
            onDismiss = { showNoteEditor = false },
            insertPositionMs = insertPositionMs,
        )
    }
}

@Composable
internal fun PlayerTranscriptSheetHost(
    viewModel: VideoPlayerViewModel,
    screenState: PlayerScreenState,
    trackUrl: String?,
    tracks: List<SubtitleOption>,
    chapters: List<StreamSegment>,
    artworkUrl: String?,
    asSidePanel: Boolean,
    expandedHeight: Dp?,
    onDismiss: () -> Unit,
    collapsedHeight: Dp = 0.dp,
    onSheetProgressChange: (Float) -> Unit = {},
) {
    val transcript by viewModel.transcriptState.collectAsStateWithLifecycle()
    LaunchedEffect(trackUrl) {
        viewModel.loadTranscript(trackUrl)
    }
    val transcriptChapters =
        remember(chapters) {
            chapters.map { chapter ->
                TranscriptChapter(startMs = chapter.startTimeSeconds.toLong() * 1000L, title = chapter.title)
            }
        }
    FlowTranscriptBottomSheet(
        cues = transcript.cues,
        isLoading = transcript.isLoading,
        currentPositionMs = { screenState.currentPosition },
        artworkUrl = artworkUrl,
        onSeekMs = { EnhancedPlayerManager.getInstance().seekTo(it) },
        onDismiss = onDismiss,
        chapters = transcriptChapters,
        tracks = tracks,
        selectedTrackUrl = trackUrl,
        onTrackSelected = { screenState.selectedTranscriptUrl = it },
        expandedHeight = expandedHeight,
        collapsedHeight = collapsedHeight,
        enableVerticalDismiss = !asSidePanel,
        onSheetProgressChange = onSheetProgressChange,
        modifier = if (asSidePanel) Modifier.fillMaxSize() else Modifier,
    )
}

@Composable
internal fun PlayerDownloadSheetHost(
    video: Video,
    uiState: VideoPlayerUiState,
    playerState: EnhancedPlayerState,
    asSidePanel: Boolean,
    expandedHeight: Dp?,
    onDismiss: () -> Unit,
    collapsedHeight: Dp = 0.dp,
    onSheetProgressChange: (Float) -> Unit = {},
) {
    MediaDownloadPrompt(
        streamSizes = uiState.streamSizes,
        videoFormats = uiState.innerTubeVideoFormats,
        audioFormats = uiState.innerTubeAudioFormats,
        video = video,
        subtitles = playerState.availableSubtitles,
        currentPlayingHeight = playerState.effectiveQuality,
        onDismiss = onDismiss,
        slot =
            MediaSheetSlot(
                expandedHeight = expandedHeight ?: defaultSheetExpandedHeight(),
                collapsedHeight = collapsedHeight,
                asSidePanel = asSidePanel,
                onProgressChange = onSheetProgressChange,
            ),
    )
}

@Composable
internal fun PlayerSleepTimerSheetHost(
    asSidePanel: Boolean,
    expandedHeight: Dp?,
    onDismiss: () -> Unit,
    collapsedHeight: Dp = 0.dp,
    onSheetProgressChange: (Float) -> Unit = {},
) {
    MediaSleepTimerSheet(
        onDismiss = onDismiss,
        expandedHeight = expandedHeight ?: defaultSheetExpandedHeight(),
        collapsedHeight = collapsedHeight,
        enableVerticalDismiss = !asSidePanel,
        asBottomSheet = !asSidePanel,
        onSheetProgressChange = onSheetProgressChange,
        modifier = if (asSidePanel) Modifier.fillMaxSize() else Modifier,
    )
}

@Composable
internal fun PlayerCommentsPanelHost(
    videoId: String,
    screenState: PlayerScreenState,
    viewModel: VideoPlayerViewModel,
    commentsUiState: PlayerCommentsUiState,
    artworkUrl: String?,
    onNavigateToChannel: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerCommentsPanel(
        comments = commentsUiState.visibleComments(screenState),
        isLoading = commentsUiState.isLoading,
        isLoadingMore = commentsUiState.isLoadingMore,
        hasMore = commentsUiState.hasMore,
        selectedFilter = screenState.commentSortFilter,
        totalText = commentsUiState.totalText,
        artworkUrl = artworkUrl,
        timedOnly = screenState.commentsTimedOnly,
        onTimedChange = { screenState.commentsTimedOnly = it },
        onFilterChanged = { filter ->
            commentsUiState.selectCommentSort(filter, videoId, screenState, viewModel)
        },
        onSeekMs = { EnhancedPlayerManager.getInstance().seekTo(it) },
        onLoadReplies = { viewModel.loadCommentReplies(it) },
        onLoadMoreReplies = { viewModel.loadMoreCommentReplies(it) },
        onAuthorClick = { authorChannelRef ->
            onClose()
            onNavigateToChannel(authorChannelRef)
        },
        onLoadMore = { viewModel.loadMoreComments(videoId) },
        onClose = onClose,
        modifier = modifier,
    )
}

@Composable
internal fun PlayerLiveChatColumn(
    messages: List<LiveChatMessage>,
    isLoading: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxHeight()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.live_chat),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.close),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
        LiveChatList(
            messages = messages,
            isLoading = isLoading,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}
