package io.github.aedev.flow.ui.screens.player.effects

import androidx.compose.runtime.*
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModel
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import kotlinx.coroutines.delay

internal fun VideoPlayerUiState.isCurrentLiveStream(): Boolean = !hlsUrl.isNullOrEmpty()

internal data class WatchHistoryEntry(
    val videoId: String,
    val position: Long,
    val duration: Long,
    val title: String,
    val thumbnailUrl: String,
    val channelName: String,
    val channelId: String,
    val isShort: Boolean,
)

private fun resolveHistoryChannelName(
    video: Video,
    extractedName: String?,
): String {
    val cachedName = video.channelName
    val normalized = " ${cachedName.trim().lowercase()} "
    val isCollaboration =
        normalized.contains(" and ") ||
            normalized.contains(" & ") ||
            normalized.contains(" x ") ||
            normalized.contains(" with ")

    return when {
        isCollaboration && cachedName.isNotBlank() -> cachedName
        !extractedName.isNullOrBlank() -> extractedName
        else -> cachedName
    }
}

/**
 * The one description of a watch-history write, shared by the initial save, the 10 s loop and the
 * player host's dispose block. Null means the entry must not be written at all: a live stream has
 * no meaningful resume position, and a zero duration or empty title would poison the row.
 */
internal fun buildWatchHistoryEntry(
    video: Video,
    uiState: VideoPlayerUiState,
    position: Long,
    duration: Long,
): WatchHistoryEntry? {
    if (uiState.isCurrentLiveStream() || duration <= 0L) return null
    val title = video.title
    if (title.isEmpty()) return null

    return WatchHistoryEntry(
        videoId = video.id,
        position = position,
        duration = duration,
        title = title,
        thumbnailUrl =
            video.thumbnailUrl.takeIf { it.isNotEmpty() }
                ?: ThumbnailUrlResolver.buildHighQualityYoutubeThumbnail(video.id),
        channelName = resolveHistoryChannelName(video, null),
        channelId = video.channelId,
        isShort = video.isShort,
    )
}

internal fun saveWatchProgress(
    viewModel: VideoPlayerViewModel,
    video: Video,
    uiState: VideoPlayerUiState,
    position: Long,
    duration: Long,
) {
    if (uiState.isCurrentLiveStream()) {
        viewModel.trackLivePlayback(video, position)
        return
    }
    val entry = buildWatchHistoryEntry(video, uiState, position, duration) ?: return
    viewModel.savePlaybackPosition(
        videoId = entry.videoId,
        position = entry.position,
        duration = entry.duration,
        title = entry.title,
        thumbnailUrl = entry.thumbnailUrl,
        channelName = entry.channelName,
        channelId = entry.channelId,
        isShort = entry.isShort,
    )
}

@Composable
internal fun WatchProgressSaveEffect(
    video: Video,
    isPlaying: Boolean,
    currentPosition: () -> Long,
    duration: () -> Long,
    uiState: VideoPlayerUiState,
    viewModel: VideoPlayerViewModel,
) {
    val currentPosProvider by rememberUpdatedState(currentPosition)
    val currentDurProvider by rememberUpdatedState(duration)
    val currentUi by rememberUpdatedState(uiState)

    LaunchedEffect(video.id) {
        delay(3000)
        saveWatchProgress(
            viewModel = viewModel,
            video = video,
            uiState = currentUi,
            position = currentPosProvider(),
            duration = currentDurProvider(),
        )
    }

    LaunchedEffect(video.id, isPlaying) {
        while (isPlaying) {
            delay(10000)
            saveWatchProgress(
                viewModel = viewModel,
                video = video,
                uiState = currentUi,
                position = currentPosProvider(),
                duration = currentDurProvider(),
            )
        }
    }
}
