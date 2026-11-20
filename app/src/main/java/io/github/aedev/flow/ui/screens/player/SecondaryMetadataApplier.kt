package io.github.aedev.flow.ui.screens.player

import android.content.Context
import android.util.Log
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.ui.screens.player.state.applyChannelMetadata
import io.github.aedev.flow.ui.screens.player.state.applyChapters
import io.github.aedev.flow.ui.screens.player.state.applyLiveWatchMetadata
import io.github.aedev.flow.ui.screens.player.state.applyRelatedVideos
import io.github.aedev.flow.ui.screens.player.state.applyWatchInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Lands what [PlayerSecondaryMetadataLoader] fetched after first frame on the screen state, and
 * arms the loads a countdown needs. Part of [PlaybackSessionApplier]: same state flow, same load
 * token gate, same collaborator instances.
 */
internal class SecondaryMetadataApplier(
    private val context: Context,
    private val uiState: MutableStateFlow<VideoPlayerUiState>,
    private val isLoadCurrent: (Long) -> Boolean,
    private val playbackPreparer: PlaybackPreparer,
    private val secondaryMetadata: PlayerSecondaryMetadataLoader,
    private val playerPreferences: PlayerPreferences,
    private val playerManager: EnhancedPlayerManager,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    fun apply(result: SecondaryMetadata) {
        when (result) {
            is SecondaryMetadata.Channel -> {
                applyChannelMetadata(result)
            }

            is SecondaryMetadata.PaidPromotion -> {
                applyPaidPromotion(result)
            }

            is SecondaryMetadata.Related -> {
                publishRelatedVideos(result.videoId, result.videos, result.loadToken, lane = result)
            }

            is SecondaryMetadata.LiveWatch -> {
                applyLiveWatchMetadata(result)
            }

            is SecondaryMetadata.Category -> {
                applyCategory(result)
            }

            is SecondaryMetadata.Heatmap -> {
                applyHeatmap(result)
            }

            is SecondaryMetadata.Chapters -> {
                applyChapters(result)
            }

            is SecondaryMetadata.WatchInfo -> {
                applyWatchInfo(result)
            }
        }
    }

    /**
     * A countdown never starts playback, so the channel row and the related lane cannot wait for
     * the first frame the way a playing video's do.
     */
    fun armCountdownMetadata(
        load: LoadContext,
        relatedVideos: List<Video>,
    ) {
        if (!isLoadCurrent(load.token)) return
        secondaryMetadata.loadChannelMetadata(
            videoId = load.videoId,
            embeddedAvatarUrls = emptyList(),
            loadToken = load.token,
            awaitPlayback = false,
        )
        secondaryMetadata.loadRelatedVideos(load.videoId, relatedVideos, load.token, awaitPlayback = false)
    }

    private fun applyPaidPromotion(result: SecondaryMetadata.PaidPromotion) {
        if (!isLoadCurrent(result.loadToken)) return
        uiState.update { it.copy(paidPromotion = result.label) }
    }

    private fun applyHeatmap(result: SecondaryMetadata.Heatmap) {
        if (!isLoadCurrent(result.loadToken)) return
        uiState.update { it.copy(heatmap = result.heatmap) }
    }

    private fun applyChapters(result: SecondaryMetadata.Chapters) {
        if (!isLoadCurrent(result.loadToken)) return
        uiState.update { it.applyChapters(result.chapters) }
    }

    private fun applyWatchInfo(result: SecondaryMetadata.WatchInfo) {
        if (!isLoadCurrent(result.loadToken) || uiState.value.cachedVideo?.id != result.videoId) return
        GlobalPlayerState.setCurrentVideo(result.video)
        uiState.update { it.applyWatchInfo(result.video) }
        // A downloaded copy learns its channel only here, so the channel row follows the watch page.
        if (uiState.value.localFileVideoId == result.videoId) {
            secondaryMetadata.loadChannelMetadata(
                videoId = result.videoId,
                embeddedAvatarUrls = listOf(result.video.channelThumbnailUrl).filter { it.isNotBlank() },
                loadToken = result.loadToken,
                awaitPlayback = false,
            )
        }
    }

    /** Kept on the video, so the watch signal carries it. */
    private fun applyCategory(result: SecondaryMetadata.Category) {
        if (!isLoadCurrent(result.loadToken)) return
        if (PlaybackSpeedPolicy.isMusic(musicVideoType = null, category = result.category, openedAsMusic = false)) {
            scope.launch { playbackPreparer.applyLateMusicSignal(uiState.value.speedContextFor(result.videoId)) }
        }

        uiState.update { state ->
            val cached = state.cachedVideo?.takeIf { it.id == result.videoId } ?: return@update state
            if (cached.category == result.category) return@update state
            state.copy(cachedVideo = cached.copy(category = result.category))
        }

        uiState.value.cachedVideo
            ?.takeIf { it.id == result.videoId }
            ?.let(GlobalPlayerState::setCurrentVideo)
    }

    private fun applyChannelMetadata(result: SecondaryMetadata.Channel) {
        if (!isLoadCurrent(result.loadToken)) return

        uiState.update { it.applyChannelMetadata(result) }

        uiState.value.cachedVideo
            ?.takeIf { it.id == result.videoId }
            ?.let(GlobalPlayerState::setCurrentVideo)
    }

    /** The one place related items reach the player: the autoplay queue and the lane together. */
    private fun publishRelatedVideos(
        videoId: String,
        videos: List<Video>,
        loadToken: Long,
        lane: SecondaryMetadata.Related? = null,
    ) {
        if (!isLoadCurrent(loadToken) || videos.isEmpty()) return
        val state = uiState.value
        if (state.cachedVideo?.id != videoId) return

        scope.launch {
            if (!isLoadCurrent(loadToken)) return@launch
            val autoplay = playerPreferences.autoplayEnabled.first()
            if (!isLoadCurrent(loadToken)) return@launch
            playerManager.setAutoplayCandidates(
                sourceVideoId = videoId,
                videos = videos,
                enabled = autoplay,
            )
            uiState.update { it.applyRelatedVideos(videoId, videos, lane?.upNextVideoId, lane?.continuation, lane?.collections.orEmpty()) }
        }
        noteRelatedForLearning(videoId, videos)
    }

    /** Hands the related list to the engine, which keeps it only if this video is then watched or liked. */
    private fun noteRelatedForLearning(
        videoId: String,
        videos: List<Video>,
    ) {
        scope.launch(ioDispatcher) {
            try {
                FlowNeuroEngine.noteRelated(context, videoId, videos)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to note related videos", e)
            }
        }
    }

    private fun applyLiveWatchMetadata(result: SecondaryMetadata.LiveWatch) {
        if (!isLoadCurrent(result.loadToken)) return

        GlobalPlayerState.setCurrentVideo(result.video)
        uiState.update { it.applyLiveWatchMetadata(result) }
        publishRelatedVideos(result.videoId, result.relatedVideos, result.loadToken)
    }

    private companion object {
        const val TAG = "PlaybackSessionApplier"
    }
}
