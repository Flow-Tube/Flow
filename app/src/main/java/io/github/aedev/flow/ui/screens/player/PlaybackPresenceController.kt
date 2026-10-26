package io.github.aedev.flow.ui.screens.player

import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.video.SavedVideoQueue
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.service.MusicServiceState
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where playback is shown: the mini player a launch restores, the media notification a start arms,
 * the move into background playback and the return to the screen.
 *
 * Nothing here starts or resolves a load — the restored session hands back to [resumePlayback] for
 * that, so the one path that begins playback stays the ViewModel's.
 */
internal class PlaybackPresenceController(
    private val uiState: MutableStateFlow<VideoPlayerUiState>,
    private val playerManager: EnhancedPlayerManager,
    private val playerPreferences: PlayerPreferences,
    private val viewHistory: ViewHistory,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val resumePlayback: (Video) -> Unit,
    private val savedQueue: suspend () -> SavedVideoQueue?,
    private val resumeQueue: (videos: List<Video>, index: Int, title: String?) -> Unit,
) {
    // The queue the restored video was playing in, so resuming it carries on with the rest.
    private var restoredQueue: SavedVideoQueue? = null

    private val _fullscreenOpen = MutableStateFlow<String?>(null)

    /**
     * The video the viewer just opened that the player takes to fullscreen once its sheet is up. It
     * is held until the screen applies it, because the screen is not composed yet when a first
     * video is opened.
     */
    val fullscreenOpen: StateFlow<String?> = _fullscreenOpen.asStateFlow()

    fun armFullscreenOpen(videoId: String?) {
        _fullscreenOpen.value = videoId
    }

    fun consumeFullscreenOpen(videoId: String) {
        _fullscreenOpen.compareAndSet(videoId, null)
    }

    /** Brings the last unfinished video back as a mini player, unless music already owns it. */
    fun restoreLastWatchedSession() {
        scope.launch {
            if (!playerPreferences.miniPlayerContinueWatchingEnabled.first()) return@launch
            if (EnhancedMusicPlayerManager.currentTrack.value != null || MusicServiceState.isRunning) return@launch
            val lastVideo = withContext(ioDispatcher) { viewHistory.getLatestUnfinishedVideo() } ?: return@launch
            if (uiState.value.cachedVideo != null) return@launch
            uiState.update { it.copy(cachedVideo = lastVideo.toVideo(), isRestoredSession = true) }
            restoredQueue =
                withContext(ioDispatcher) { savedQueue() }?.takeIf { it.videos.getOrNull(it.index)?.id == lastVideo.videoId }
        }
    }

    /** The media notification every playback start arms, with the one title fallback it uses. */
    fun armNotificationFor(video: Video) =
        playerManager.startBackgroundService(
            videoId = video.id,
            title = video.title.ifEmpty { "Flow Player" },
            channel = video.channelName,
            thumbnail = video.thumbnailUrl,
        )

    /**
     * Called when the user interacts with the restored-session mini player (taps play
     * or expands the sheet). Starts loading streams and transitions to active playback.
     * @param stayMini if true, the player will keep playing in mini mode (don't auto-expand)
     */
    fun resumeRestoredSession(stayMini: Boolean) {
        val video = uiState.value.cachedVideo ?: return
        if (!uiState.value.isRestoredSession) return
        uiState.update {
            it.copy(
                isRestoredSession = false,
                resumedInMiniPlayer = stayMini,
                isBackgroundPlaybackMode = false,
            )
        }
        val queue = restoredQueue
        restoredQueue = null
        if (queue != null) resumeQueue(queue.videos, queue.index, queue.title) else resumePlayback(video)
        armFullscreenOpen(null)
    }

    fun dismissContinueWatching() {
        val videoId = uiState.value.cachedVideo?.id ?: return
        scope.launch {
            viewHistory.markAsWatched(videoId)
        }
    }

    fun ensureNotificationServiceRunning() {
        val video = uiState.value.cachedVideo ?: return
        armNotificationFor(video)
    }

    fun clearResumedInMiniPlayer() {
        uiState.update { it.copy(resumedInMiniPlayer = false) }
    }

    fun startBackgroundPlayback() {
        val state = uiState.value
        val video = state.cachedVideo ?: GlobalPlayerState.currentVideo.value ?: return
        playerManager.startBackgroundService(
            videoId = video.id,
            title = video.title,
            channel = video.channelName,
            thumbnail = video.thumbnailUrl,
        )
        GlobalPlayerState.setExplicitBackgroundPlaybackActive(true)
        playerManager.continueVideoPlaybackInBackground()
        uiState.update {
            it.copy(
                shouldDismissPlayer = true,
                isBackgroundPlaybackMode = true,
            )
        }
    }

    fun showVideoPlayer() {
        GlobalPlayerState.setExplicitBackgroundPlaybackActive(false)
        playerManager.restoreVideoOutput()
        uiState.update {
            it.copy(
                shouldDismissPlayer = false,
                isBackgroundPlaybackMode = false,
            )
        }
    }

    fun resetDismissState() {
        uiState.update { it.copy(shouldDismissPlayer = false) }
    }
}
