package io.github.aedev.flow.ui.screens.player.content

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.ui.components.shared.FlowNoteEditorDialog
import io.github.aedev.flow.ui.components.shared.SaveVideoSheet
import io.github.aedev.flow.ui.components.shared.rememberVideoShareAction
import io.github.aedev.flow.ui.components.shared.shareMediaFiles
import io.github.aedev.flow.ui.components.shared.subscribedCollaboratorIds
import io.github.aedev.flow.ui.components.videoplayer.info.CommentsPreview
import io.github.aedev.flow.ui.components.videoplayer.info.VideoInfoSection
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModel
import io.github.aedev.flow.ui.screens.player.state.PlayerCommentsUiState
import io.github.aedev.flow.ui.screens.player.state.PlayerScreenState
import io.github.aedev.flow.ui.screens.player.state.PlayerSheet
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.utils.youtubeWatchUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun VideoInfoContent(
    video: Video,
    uiState: VideoPlayerUiState,
    viewModel: VideoPlayerViewModel,
    screenState: PlayerScreenState,
    commentsUiState: PlayerCommentsUiState,
    commentsEnabled: Boolean = true,
    showCommentsPreview: Boolean = true,
    deArrowEnabled: Boolean,
    context: Context,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    onChannelClick: (String) -> Unit,
) {
    var showAddToPlaylistDialog by remember(video.id) { mutableStateOf(false) }
    val shareVideoAction = rememberVideoShareAction()
    val metadata =
        rememberPlayerVideoMetadata(
            video = video,
            uiState = uiState,
            deArrowEnabled = deArrowEnabled,
            context = context,
        )
    val resolvedVideoTitle = metadata.resolvedVideoTitle
    val resolvedCollaborators = metadata.resolvedCollaborators
    val resolvedChannelName = metadata.resolvedChannelName
    val streamUploadDate = metadata.streamUploadDate
    val dialogVideo = metadata.dialogVideo

    // ── Error details panel ─────────────────────────────────────────────────
    if (uiState.error != null) {
        PlayerErrorPanel(
            errorHint = uiState.errorHint,
            videoId = video.id,
            context = context,
            onRetryClick = { viewModel.retryLoadVideo() },
            onResetSession = if (uiState.errorOffersSessionReset) ({ viewModel.resetSessionAndRetry() }) else null,
        )
    }

    val downloadedVideoIds by viewModel.downloadedVideoIds.collectAsStateWithLifecycle()
    val videoNote by viewModel.videoNote.collectAsStateWithLifecycle()
    val videoNotesEnabled by viewModel.videoNotesEnabled.collectAsStateWithLifecycle()
    var showNoteEditor by rememberSaveable(video.id) { mutableStateOf(false) }
    val isVideoDownloaded = remember(downloadedVideoIds, video.id) { downloadedVideoIds.contains(video.id) }
    val isVideoSaved by remember(video.id) { viewModel.isVideoSavedToAnyPlaylist(video.id) }
        .collectAsStateWithLifecycle(initialValue = false)

    if (showAddToPlaylistDialog) {
        SaveVideoSheet(
            video = dialogVideo,
            onDismiss = { showAddToPlaylistDialog = false },
        )
    }

    VideoInfoSection(
        video = video,
        title = resolvedVideoTitle,
        viewCount = video.viewCount,
        uploadDate = streamUploadDate ?: video.uploadDate,
        description = video.description,
        isUpcoming = uiState.isUpcoming,
        channelName = resolvedChannelName,
        channelAvatarUrl = uiState.channelAvatarUrl ?: video.channelThumbnailUrl,
        channelAvatarUrls = video.channelThumbnailUrls,
        collaborators = resolvedCollaborators,
        subscribedCollaboratorIds = if (resolvedCollaborators.size > 1) subscribedCollaboratorIds(resolvedCollaborators) else emptySet(),
        subscriberCount = uiState.channelSubscriberCount,
        isSubscribed = uiState.isSubscribed,
        isNotificationsEnabled = uiState.isNotificationsEnabled,
        likeState = uiState.likeState ?: "NONE",
        likeCount = video.likeCount,
        dislikeCount = uiState.dislikeCount,
        onLikeClick = {
            val thumbnailUrl = video.thumbnailUrl

            when (uiState.likeState) {
                "LIKED" -> {
                    viewModel.removeLikeState(video.id)
                }

                else -> {
                    viewModel.likeVideo(
                        video.id,
                        resolvedVideoTitle,
                        thumbnailUrl,
                        video.channelName,
                    )
                }
            }
        },
        onDislikeClick = {
            when (uiState.likeState) {
                "DISLIKED" -> viewModel.removeLikeState(video.id)
                else -> viewModel.dislikeVideo(video.id)
            }
        },
        onSubscribeClick = {
            val channelThumbSafe =
                uiState.channelAvatarUrl?.takeIf { it.isNotEmpty() }
                    ?: video.channelThumbnailUrl?.takeIf { it.isNotEmpty() }
                    ?: ""

            viewModel.toggleSubscription(video.channelId, video.channelName, channelThumbSafe)

            scope.launch {
                val message =
                    if (uiState.isSubscribed) {
                        context.getString(R.string.unsubscribed_from, video.channelName)
                    } else {
                        context.getString(R.string.subscribed_to, video.channelName)
                    }

                val result =
                    snackbarHostState.showSnackbar(
                        message,
                        actionLabel = if (uiState.isSubscribed) context.getString(R.string.undo) else null,
                    )

                if (result == SnackbarResult.ActionPerformed && uiState.isSubscribed) {
                    viewModel.toggleSubscription(video.channelId, video.channelName, channelThumbSafe)
                }
            }
        },
        onUnsubscribeClick = {
            val channelThumbSafe =
                uiState.channelAvatarUrl?.takeIf { it.isNotEmpty() }
                    ?: video.channelThumbnailUrl?.takeIf { it.isNotEmpty() }
                    ?: ""
            viewModel.toggleSubscription(video.channelId, video.channelName, channelThumbSafe)
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.unsubscribed_from, video.channelName),
                )
            }
        },
        onNotificationChange = { enabled -> viewModel.setNotificationEnabled(video.channelId, enabled) },
        onChannelClick = { onChannelClick(video.channelId) },
        onSaveClick = { showAddToPlaylistDialog = true },
        onShareClick = {
            val fileUri = LocalMediaIds.videoUri(video.id)
            if (fileUri != null) {
                context.shareMediaFiles(listOf(fileUri), mimeType = "video/*")
            } else {
                shareVideoAction(video.id, resolvedVideoTitle, video.isShort)
            }
        },
        isDeviceFile = LocalMediaIds.isLocal(video.id),
        onDownloadClick = { screenState.open(PlayerSheet.Download) },
        isSaved = isVideoSaved,
        isDownloaded = isVideoDownloaded,
        onNoteClick = { showNoteEditor = true }.takeIf { videoNotesEnabled },
        hasNote = !videoNote.isNullOrBlank(),
        onBackgroundPlayClick = { viewModel.startBackgroundPlayback() },
        onCopyLinkClick = {
            val url = youtubeWatchUrl(video.id)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("video_link", url))
            Toast.makeText(context, context.getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
        },
        onCopyLinkAtTimeClick = {
            val positionMs = EnhancedPlayerManager.getInstance().getCurrentPosition()
            val url = youtubeWatchUrl(video.id, positionMs / 1000L)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("video_link_at_time", url))
            Toast.makeText(context, context.getString(R.string.link_with_timestamp_copied), Toast.LENGTH_SHORT).show()
        },
        onDescriptionClick = { screenState.open(PlayerSheet.Description) },
    )

    if (uiState.isLiveChatAvailable) {
        io.github.aedev.flow.ui.components.videoplayer.sheet.LiveChatPreview(
            onClick = { screenState.open(PlayerSheet.LiveChat()) },
        )
    }

    if (commentsEnabled) {
        CommentsPreview(
            latestComment = if (showCommentsPreview) commentsUiState.comments.firstOrNull()?.text else null,
            authorAvatar = if (showCommentsPreview) commentsUiState.comments.firstOrNull()?.authorThumbnail else null,
            totalText = commentsUiState.totalText,
            showPreviewText = showCommentsPreview,
            isDisabled = commentsUiState.isDisabled,
            onClick = { screenState.open(PlayerSheet.Comments()) },
        )
    }

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
