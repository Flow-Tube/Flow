package io.github.aedev.flow.ui.screens.player

import android.content.Context
import android.util.Log
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.video.AutoDownloadTrigger
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.LifecyclePlaybackPreferences
import io.github.aedev.flow.player.MiniPlayerExpansionState
import io.github.aedev.flow.player.PlaybackTarget
import io.github.aedev.flow.player.queue.QueuePageSource
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.player.stream.ResetYouTubeSession
import io.github.aedev.flow.ui.screens.player.state.*
import io.github.aedev.flow.utils.NetworkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What plays on the player screen: the [VideoPlayerViewModel] entry points that start, replace,
 * retry or clear a video, and the reaction to the player moving to a video the screen did not ask for.
 *
 * Owned by the ViewModel and built from its own scope, state flow and controllers; it holds no
 * state beyond the last video it cleared the unplayable mark for.
 */
internal class PlaybackRequestController(
    private val context: Context,
    private val uiState: MutableStateFlow<VideoPlayerUiState>,
    private val playerManager: EnhancedPlayerManager,
    private val playerPreferences: PlayerPreferences,
    private val lifecyclePlayback: LifecyclePlaybackPreferences,
    private val autoDownload: AutoDownloadTrigger,
    private val resetYouTubeSession: ResetYouTubeSession,
    private val collaborators: PlayerCollaborators,
    private val loads: PlaybackLoadController,
    private val recovery: PlaybackRecoveryController,
    private val presence: PlaybackPresenceController,
    private val scope: CoroutineScope,
) {
    private val watchSessions = collaborators.watchSessions
    private val upcomingPremiere = collaborators.upcomingPremiere

    private var clearedUnplayableVideoId: String? = null

    private val _expandPlayerRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val expandPlayerRequest: SharedFlow<Unit> = _expandPlayerRequest.asSharedFlow()

    suspend fun onPlayerStateChanged(playerState: EnhancedPlayerState) {
        uiState.update { it.mirrorPlayerState(playerState) }

        // A video that prepares successfully is not unplayable, whatever a past failure said.
        playerState.currentVideoId
            ?.takeIf { playerState.isPrepared && it != clearedUnplayableVideoId }
            ?.let { preparedVideoId ->
                clearedUnplayableVideoId = preparedVideoId
                playerPreferences.clearVideoUnplayable(preparedVideoId)
            }

        val videoId = uiState.value.foreignVideoIdNeedingLoad(playerState) ?: return
        GlobalPlayerState.currentVideo.value?.takeIf { it.id == videoId }?.let { currentVideo ->
            uiState.update { it.resetForVideo(currentVideo) }
            presence.armNotificationFor(currentVideo)
            watchSessions.saveHistoryEntry(currentVideo)
        }
        load(videoId)
    }

    fun playVideo(
        video: Video,
        userOpened: Boolean,
        startPositionMs: Long?,
    ) {
        // While this device drives another one, the video plays there.
        if (userOpened) PlaybackTarget.current?.let { return it.play(listOf(video.id), 0, music = false) }
        val isMiniPlayerCollapsed =
            GlobalPlayerState.miniPlayerExpansionState.value == MiniPlayerExpansionState.COLLAPSED
        if (uiState.value.shouldReopenInsteadOfPlaying(video.id, playerManager.playerState.value, isMiniPlayerCollapsed)) {
            startPositionMs?.let(playerManager::seekTo)
            presence.showVideoPlayer()
            _expandPlayerRequest.tryEmit(Unit)
            return
        }

        loads.nextToken()
        takeOverPlayback()
        armStartPaused(video.id, userOpened)
        autoDownload.onOpened(video, userOpened)

        uiState.value = uiState.value.startPlaybackOf(video)
        GlobalPlayerState.setCurrentVideo(video)
        GlobalPlayerState.setExplicitBackgroundPlaybackActive(false)
        watchSessions.saveHistoryEntry(video)
        presence.armNotificationFor(video)
        if (upcomingPremiere.applyCountdown(video)) {
            return
        }
        load(video.id, resumePositionOverrideMs = startPositionMs)
    }

    fun playLocalVideo(
        video: Video,
        contentUri: String,
    ) {
        // A file on this device plays here, which ends driving another device.
        PlaybackTarget.current?.release()
        takeOverPlayback()
        armStartPaused(video.id, userOpened = true)
        loads.prepareDeviceFile(video, contentUri)
    }

    private fun armStartPaused(
        videoId: String,
        userOpened: Boolean,
    ) {
        playerManager.armStartPaused(videoId.takeIf { userOpened && lifecyclePlayback.settings.startVideosPaused })
        presence.armFullscreenOpen(videoId.takeIf { userOpened && lifecyclePlayback.settings.openVideosFullscreen })
    }

    /** Drops the load, the queue and the music player so this screen owns playback outright. */
    private fun takeOverPlayback() {
        loads.cancel()
        recovery.onPlaybackRequested()
        playerManager.pause()
        playerManager.clearAll()
        EnhancedMusicPlayerManager.stop()
        EnhancedMusicPlayerManager.clearCurrentTrack()
    }

    fun clearVideo() {
        loads.nextToken()
        loads.cancel()
        recovery.onPlaybackRequested()
        playerManager.stop()
        playerManager.stopBackgroundService()
        playerManager.clearAll()
        GlobalPlayerState.setCurrentVideo(null)
        GlobalPlayerState.setExplicitBackgroundPlaybackActive(false)
        GlobalPlayerState.hideMiniPlayer()

        uiState.update { it.clearedForNoVideo() }

        loads.clearHistory()

        collaborators.comments.clear()
        collaborators.descriptions.clear()
        collaborators.transcripts.clear()
    }

    fun resetSessionAndRetry() =
        scope.launch {
            resetYouTubeSession()
            retryLoadVideo(reuseLoadedStreams = false)
        }

    fun retryLoadVideo(reuseLoadedStreams: Boolean) {
        val videoId = uiState.value.cachedVideo?.id ?: return
        Log.d("VideoPlayerViewModel", "Retrying video load for $videoId")
        if (upcomingPremiere.applyCountdown(uiState.value.cachedVideo ?: return)) {
            return
        }
        val deviceFileUri = LocalMediaIds.videoUri(videoId)
        if (deviceFileUri != null) {
            playLocalVideo(uiState.value.cachedVideo ?: return, deviceFileUri.toString())
            return
        }
        recovery.onPlaybackRequested()
        if (reuseLoadedStreams && recovery.retryInPlace()) return
        playerManager.clearCurrentVideo()
        uiState.update { it.copy(error = null, errorHint = null, errorOffersSessionReset = false, isLoading = true) }
        load(videoId, freshStreams = true)
    }

    fun playPlaylist(
        videos: List<Video>,
        startIndex: Int,
        title: String?,
        shuffle: Boolean?,
        more: QueuePageSource? = null,
    ) {
        if (videos.isEmpty()) return
        val startVideo = videos.getOrNull(startIndex) ?: videos.first()
        PlaybackTarget.current?.let { remote ->
            if (LocalMediaIds.isLocal(
                    startVideo.id,
                )
            ) {
                remote.release()
            } else {
                return remote.play(videos.map(Video::id), videos.indexOf(startVideo), music = false)
            }
        }

        EnhancedMusicPlayerManager.stop()
        EnhancedMusicPlayerManager.clearCurrentTrack()

        playerManager.armStartPaused(null)
        presence.armFullscreenOpen(startVideo.id.takeIf { lifecyclePlayback.settings.openVideosFullscreen })
        playerManager.setQueue(videos, startIndex, title, shuffle, more = more)

        uiState.update { it.resetForVideo(startVideo).copy(queueTitle = title) }
        watchSessions.saveHistoryEntry(startVideo)
        presence.armNotificationFor(startVideo)
        if (upcomingPremiere.applyCountdown(startVideo, preserveQueueTitle = title)) {
            return
        }
        load(startVideo.id)
    }

    fun playNext() {
        val handledByPlayer = playerManager.playNext(loadStreamsInPlayer = false)
        if (!handledByPlayer) {
            uiState.value.relatedVideos.firstOrNull()?.let { nextVideo ->
                playVideo(nextVideo, userOpened = false, startPositionMs = null)
                GlobalPlayerState.setCurrentVideo(nextVideo)
            }
        }
    }

    fun playPrevious() {
        val handledByPlayer = playerManager.playPrevious(loadStreamsInPlayer = false)
        if (!handledByPlayer) {
            loads.previousVideoId()?.let { prevId ->
                val prevVideo = blankVideo(prevId, cached = null)
                playVideo(prevVideo, userOpened = false, startPositionMs = null)
                GlobalPlayerState.setCurrentVideo(prevVideo)
            }
        }
    }

    /** A forced load on the current network, which picks the quality preference. */
    private fun load(
        videoId: String,
        resumePositionOverrideMs: Long? = null,
        freshStreams: Boolean = false,
    ) = loads.load(
        videoId = videoId,
        isWifi = NetworkState.isOnWifi(context),
        forceRefresh = true,
        escalateToSabr = false,
        resumePositionOverrideMs = resumePositionOverrideMs,
        freshStreams = freshStreams,
    )
}
