package io.github.aedev.flow.ui.screens.player

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.localmedia.LocalSubtitles
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.recommendation.InteractionType
import io.github.aedev.flow.data.repository.SponsorBlockRepository
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.video.OfflineSubtitleStore
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.audio.LoudnessGain
import io.github.aedev.flow.player.error.VideoErrorMapper
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor
import io.github.aedev.flow.player.stream.PlayabilityVerdict
import io.github.aedev.flow.player.stream.PlaybackFailure
import io.github.aedev.flow.player.stream.ResolvedCaption
import io.github.aedev.flow.player.stream.ResolvedPlayback
import io.github.aedev.flow.player.stream.StoryboardSpec
import io.github.aedev.flow.player.stream.UpcomingDetails
import io.github.aedev.flow.ui.screens.player.state.*
import io.github.aedev.flow.utils.NetworkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The load a step belongs to: the video it resolved for, and the token saying it is still current. */
internal data class LoadContext(
    val videoId: String,
    val token: Long,
)

/**
 * Applies what one load resolved: the screen state each step lands on, the hand-off to the player,
 * and the metadata fetches that follow it — in the order the load produced them.
 *
 * This is the second writer of the player screen's state, alongside [VideoPlayerViewModel]: the
 * ViewModel owns the session entry points and the player-state mirror, this class owns everything a
 * resolved step and its secondary metadata land on. Both write the one [MutableStateFlow] the
 * ViewModel constructs and passes in, and both gate on the same load token, so a superseded load
 * stops writing at exactly the points it used to.
 *
 * Every collaborator here — the player manager included — is the instance the ViewModel already
 * holds; this class creates, looks up and releases none of them.
 */
internal class PlaybackSessionApplier(
    private val context: Context,
    private val uiState: MutableStateFlow<VideoPlayerUiState>,
    private val isLoadCurrent: (Long) -> Boolean,
    private val playbackPreparer: PlaybackPreparer,
    private val streamPreparer: PlaybackStreamPreparer,
    private val secondaryMetadata: PlayerSecondaryMetadataLoader,
    private val liveChat: LiveChatController,
    private val repository: YouTubeRepository,
    private val viewHistory: ViewHistory,
    private val playerPreferences: PlayerPreferences,
    private val sponsorBlockRepository: SponsorBlockRepository,
    private val videoDownloadManager: VideoDownloadManager,
    private val offlineSubtitleStore: OfflineSubtitleStore,
    private val localSubtitles: LocalSubtitles,
    private val playerManager: EnhancedPlayerManager,
    private val scope: CoroutineScope,
    private val networkDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
    private val enterUpcoming: (
        videoId: String,
        releaseMs: Long?,
        relatedVideos: List<Video>,
        loadToken: Long,
        details: UpcomingDetails?,
    ) -> Boolean,
    private val tryEnterUpcoming: suspend (videoId: String, relatedVideos: List<Video>, loadToken: Long) -> Boolean,
) {
    suspend fun apply(
        step: ResolvedPlayback,
        load: LoadContext,
    ) {
        when (step) {
            is ResolvedPlayback.LocalCopyReady -> {
                if (!isLoadCurrent(load.token)) return
                uiState.update { it.applyLocalCopyReady(load.videoId, step) }
                announceLocalCopy(load)
                if (step.needsSponsorBlockBackfill) backfillSponsorBlockSegments(load.videoId)
                armLocalCopyMetadata(load)
                prepareLocalMedia(load, step.localFilePath, step.offlineSegments)
            }

            is ResolvedPlayback.Live -> {
                prepareLiveStreamFromInnerTube(load, step.result, step.relatedVideos)
            }

            is ResolvedPlayback.VodFromInnerTube -> {
                applyVodFromInnerTube(load, step)
            }

            is ResolvedPlayback.Upcoming -> {
                enterUpcoming(load.videoId, step.releaseTimeMs, step.relatedVideos, load.token, step.details)
                armCountdownMetadata(load, step.relatedVideos, step.details?.channelId)
            }

            is ResolvedPlayback.Failed -> {
                applyPlaybackFailure(load, step)
            }
        }
    }

    fun applySecondary(result: SecondaryMetadata) {
        when (result) {
            is SecondaryMetadata.Channel -> applyChannelMetadata(result)
            is SecondaryMetadata.Related -> publishRelatedVideos(result.videoId, result.videos, result.loadToken)
            is SecondaryMetadata.LiveWatch -> applyLiveWatchMetadata(result)
            is SecondaryMetadata.Category -> applyCategory(result)
            is SecondaryMetadata.Heatmap -> applyHeatmap(result)
            is SecondaryMetadata.Chapters -> applyChapters(result)
            is SecondaryMetadata.WatchInfo -> applyWatchInfo(result)
        }
    }

    private val dislikeCounts = DislikeCountLoader(uiState, isLoadCurrent, repository, playerPreferences, scope, networkDispatcher)

    fun startDislikeLoad(load: LoadContext) = dislikeCounts.start(load)

    suspend fun prepareLocalMedia(
        load: LoadContext,
        localFilePath: String,
        offlineSegments: List<SponsorBlockSegment>?,
        savedPosition: Long? = null,
    ) {
        playbackPreparer.prepareLocalMedia(
            videoId = load.videoId,
            localFilePath = localFilePath,
            offlineSegments = offlineSegments,
            savedPosition = savedPosition ?: viewHistory.getPlaybackPosition(load.videoId).first(),
            durationMs =
                uiState.value.cachedVideo
                    ?.takeIf { it.id == load.videoId }
                    ?.duration
                    ?.times(1000L) ?: 0L,
            subtitles = subtitlesFor(load.videoId, localFilePath),
            isCurrent = { isLoadCurrent(load.token) },
            subtitleOffsetMs = localSubtitles.offsetMs(load.videoId),
            speedContext = speedContextFor(load.videoId),
        )
    }

    private fun speedContextFor(
        videoId: String,
        musicVideoType: String? = null,
        channelId: String? = null,
    ): PlaybackSpeedContext {
        val cached = uiState.value.cachedVideo?.takeIf { it.id == videoId }
        return PlaybackSpeedContext(
            videoId = videoId,
            channelId = channelId ?: cached?.channelId,
            musicVideoType = musicVideoType,
            knownMusic = cached?.isMusic == true,
        )
    }

    /** Re-pushes what the screen already holds when the player turns out to own no media item. */
    suspend fun armLatePrepare(
        load: LoadContext,
        latest: VideoPlayerUiState,
    ) {
        val videoId = load.videoId
        when (val prepare = latest.latePrepare(videoId)) {
            null -> {
                Unit
            }

            is LatePrepare.LocalFile -> {
                Log.w(TAG, "Late prepare: arming local playback for $videoId")
                prepareLocalMedia(
                    load = load,
                    localFilePath = prepare.localFilePath,
                    offlineSegments = prepare.offlineSegments,
                    savedPosition = prepare.savedPosition ?: viewHistory.getPlaybackPosition(videoId).first(),
                )
            }
        }
    }

    /**
     * The engine's click signal.
     *
     * It used to hang off the NewPipe metadata step, which meant it only ever fired on a load that
     * leg won; a load InnerTube resolved by itself taught the engine nothing. Off the startup path
     * because it takes the brain mutex and updates vectors, none of which first frame needs.
     */
    private fun recordWatchClick(video: Video) {
        scope.launch(ioDispatcher) {
            try {
                FlowNeuroEngine.onVideoInteraction(context, video, InteractionType.CLICK)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record interaction", e)
            }
        }
    }

    private suspend fun applyVodFromInnerTube(
        load: LoadContext,
        step: ResolvedPlayback.VodFromInnerTube,
    ) {
        try {
            prepareVodStreamFromInnerTube(load, step)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "InnerTube VOD fallback failed for ${load.videoId}", e)
            if (tryEnterUpcoming(load.videoId, step.relatedVideos, load.token)) {
                armCountdownMetadata(load, step.relatedVideos, channelId = null)
            } else {
                val videoError = VideoErrorMapper.from(context, e, load.videoId)
                if (isLoadCurrent(load.token)) {
                    uiState.update { it.applyVodFailure(step.relatedVideos, videoError) }
                }
            }
        }
    }

    private suspend fun applyPlaybackFailure(
        load: LoadContext,
        step: ResolvedPlayback.Failed,
    ) {
        if (!isLoadCurrent(load.token)) return
        val videoError =
            when (step.failure) {
                PlaybackFailure.TIMEOUT -> VideoErrorMapper.fromTimeout(context)
                else -> VideoErrorMapper.from(context, step.cause, load.videoId)
            }
        val lastingRefusal =
            step.failure == PlaybackFailure.EXTRACTION &&
                PlayabilityVerdict.isLasting(
                    InnerTubeVideoStreamExtractor.isGone(load.videoId),
                    InnerTubeVideoStreamExtractor.blockOf(load.videoId),
                )
        if ((step.failure == PlaybackFailure.UNEXPECTED && !videoError.isRetryable) || lastingRefusal) {
            playerPreferences.markVideoUnplayable(load.videoId)
        }
        // A removed or unplayable video stays that way, so a playlist carries on instead of stopping on it.
        if (step.failure == PlaybackFailure.EXTRACTION && InnerTubeVideoStreamExtractor.isGone(load.videoId) && playerManager.hasNext()) {
            withContext(Dispatchers.Main) { playerManager.playNext(loadStreamsInPlayer = false) }
            return
        }
        uiState.update { it.applyPlaybackFailure(step.relatedVideos, videoError) }
    }

    /** The notification and the mini player show the download's own title until the watch page lands. */
    private suspend fun announceLocalCopy(load: LoadContext) {
        val video = uiState.value.cachedVideo?.takeIf { it.id == load.videoId } ?: return
        GlobalPlayerState.setCurrentVideo(video)
        if (video.title.isNotBlank()) {
            playbackPreparer.beginSession(load.videoId, video.title, video.channelName, video.thumbnailUrl)
        }
    }

    /**
     * The watch page a streamed load reads after first frame, for a video that plays from its file.
     * One cached `/next` answers the title, counts, lane, chapters and heatmap together, and no
     * `/player` request is made because nothing here needs streams.
     */
    private fun armLocalCopyMetadata(load: LoadContext) {
        if (!shouldLoadOnlineMetadataForLocalCopy(load.videoId, NetworkState.isOnline(context))) return
        val video = blankVideo(load.videoId, uiState.value.cachedVideo?.takeIf { it.id == load.videoId })
        secondaryMetadata.loadRelatedVideos(load.videoId, emptyList(), load.token)
        secondaryMetadata.loadHeatmap(load.videoId, load.token)
        secondaryMetadata.loadChapters(load.videoId, load.token)
        secondaryMetadata.loadWatchInfo(load.videoId, video, load.token)
    }

    private fun backfillSponsorBlockSegments(videoId: String) {
        scope.launch(networkDispatcher) {
            try {
                val segments = sponsorBlockRepository.getSegments(videoId)
                // An empty list is stored too, so a video with no segments isn't asked about again.
                videoDownloadManager.saveSponsorBlockData(
                    videoId,
                    sponsorBlockRepository.serializeSegments(segments),
                )
                Log.d(TAG, "Backfilled ${segments.size} SB segments for $videoId")
                if (segments.isNotEmpty()) uiState.update { it.copy(offlineSponsorBlockSegments = segments) }
            } catch (e: Exception) {
                Log.w(TAG, "SB backfill failed for $videoId", e)
            }
        }
    }

    private suspend fun prepareLiveStreamFromInnerTube(
        load: LoadContext,
        result: InnerTubeVideoStreamExtractor.VideoExtractionResult,
        relatedVideos: List<Video>,
    ) = withContext(Dispatchers.Main) {
        if (!isLoadCurrent(load.token)) return@withContext

        val videoId = load.videoId
        val streams = streamPreparer.assembleLive(videoId, uiState.value.cachedVideo, result)
        val identity = streams.identity
        GlobalPlayerState.setCurrentVideo(identity.enrichedVideo)
        recordWatchClick(identity.enrichedVideo)

        playbackPreparer.beginSession(videoId, identity.title, identity.channel, identity.thumbnail)
        playbackPreparer.applyAutoplayCandidates(videoId = videoId, videos = relatedVideos)

        uiState.update { it.applyLiveStreams(relatedVideos, streams.hlsUrl) }

        val liveStarted =
            playbackPreparer.prepareLiveStreams(
                videoId = videoId,
                hlsUrl = streams.hlsUrl,
                dashManifestUrl = streams.dashManifestUrl,
                subtitles = streams.subtitles,
                isCurrent = { isLoadCurrent(load.token) },
            )
        if (!liveStarted) return@withContext

        secondaryMetadata.loadChannelMetadata(
            videoId = videoId,
            uploaderUrl = null,
            channelId = identity.channelId,
            embeddedAvatarUrls = identity.embeddedAvatarUrls,
            loadToken = load.token,
        )

        liveChat.start(videoId)

        secondaryMetadata.refreshLiveWatchMetadata(videoId, identity.enrichedVideo, load.token)
    }

    private suspend fun prepareVodStreamFromInnerTube(
        load: LoadContext,
        step: ResolvedPlayback.VodFromInnerTube,
    ) = withContext(Dispatchers.Main) {
        if (!isLoadCurrent(load.token)) return@withContext

        val videoId = load.videoId
        val result = step.result
        val relatedVideos = step.relatedVideos
        val streams = streamPreparer.assembleVod(videoId, uiState.value.cachedVideo, step)
        val identity = streams.identity
        GlobalPlayerState.setCurrentVideo(identity.enrichedVideo)
        recordWatchClick(identity.enrichedVideo)

        playbackPreparer.beginSession(videoId, identity.title, identity.channel, identity.thumbnail)

        val autoplay = playbackPreparer.applyAutoplayCandidates(videoId = videoId, videos = relatedVideos)

        val savedPositionMs =
            step.resumePositionOverrideMs
                ?.takeIf { it > 0L }
                ?: viewHistory.getPlaybackPosition(videoId).first()

        Log.w(
            TAG,
            "VOD fallback playing $videoId via InnerTube ${result.usedClient.clientName} " +
                "(sabr=${result.sabrInfo != null}, video=${streams.videoStreams.size}, " +
                "audio=${streams.audioStreams.size})",
        )

        uiState.update {
            it.applyVodStreams(
                cachedVideo = identity.enrichedVideo,
                // A stream that was live but is not now. The extractor reported this as a
                // POST_LIVE_STREAM type; the player response splits it across two flags.
                isArchivedLivestream =
                    result.playerResponse.videoDetails?.isLiveContent == true &&
                        result.playerResponse.videoDetails?.isLive != true,
                relatedVideos = relatedVideos,
                videoStream = streams.videoStream,
                audioStream = streams.audioStream,
                availableQualities = streams.availableQualities,
                savedPositionMs = savedPositionMs,
                isAdaptiveMode = streams.isAdaptiveMode,
                autoplayEnabled = autoplay,
                innerTubeVideoFormats = result.videoFormats,
                innerTubeAudioFormats = result.audioFormats,
                streamSizes = streams.streamSizes,
                storyboard =
                    StoryboardSpec.parse(
                        spec =
                            result.playerResponse.storyboards
                                ?.playerStoryboardSpecRenderer
                                ?.spec,
                        durationMs = streams.durationSeconds * 1000L,
                    ),
            )
        }

        // Queue and preloaded playback may already own this media item. Arm secondary metadata
        // before the prepared-player return so those transitions still populate the screen.
        secondaryMetadata.loadRelatedVideos(videoId, relatedVideos, load.token)
        // A SABR/web client already returned the category, so spend nothing fetching it again.
        result.playerResponse.microformat
            ?.playerMicroformatRenderer
            ?.category
            ?.let { repository.rememberVideoCategory(videoId, it) }
        secondaryMetadata.loadCategory(videoId, load.token)
        secondaryMetadata.loadHeatmap(videoId, load.token)
        secondaryMetadata.loadChapters(videoId, load.token)
        secondaryMetadata.loadWatchInfo(videoId, identity.enrichedVideo, load.token)
        secondaryMetadata.loadChannelMetadata(
            videoId = videoId,
            uploaderUrl = null,
            channelId = identity.channelId,
            embeddedAvatarUrls = identity.embeddedAvatarUrls,
            loadToken = load.token,
        )

        playerManager.setLoudness(
            videoId,
            LoudnessGain.gainDb(result.playerResponse.playerConfig?.audioConfig, result.audioFormats.firstOrNull()?.loudnessDb),
        )
        playbackPreparer.prepareVodStreams(
            videoId = videoId,
            streams = streams,
            step = step,
            savedPositionMs = savedPositionMs,
            speedContext =
                speedContextFor(
                    videoId = videoId,
                    musicVideoType = result.playerResponse.videoDetails?.musicVideoType,
                    channelId = identity.channelId,
                ),
            isCurrent = { isLoadCurrent(load.token) },
        )
    }

    /**
     * A countdown never starts playback, so the channel row and the related lane cannot wait for
     * the first frame the way a playing video's do.
     */
    fun armCountdownMetadata(
        load: LoadContext,
        relatedVideos: List<Video>,
        channelId: String?,
    ) {
        if (!isLoadCurrent(load.token)) return
        secondaryMetadata.loadChannelMetadata(
            videoId = load.videoId,
            uploaderUrl = null,
            channelId = channelId?.takeIf { it.isNotBlank() } ?: uiState.value.cachedVideo?.channelId,
            embeddedAvatarUrls = emptyList(),
            loadToken = load.token,
            awaitPlayback = false,
        )
        secondaryMetadata.loadRelatedVideos(load.videoId, relatedVideos, load.token, awaitPlayback = false)
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
                uploaderUrl = null,
                channelId = result.video.channelId,
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
            scope.launch { playbackPreparer.applyLateMusicSignal(speedContextFor(result.videoId)) }
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
            uiState.update { it.applyRelatedVideos(videoId, videos) }
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

    /**
     * Adds the subtitle file at [uri] to the device file or download that is playing and remembers
     * it for that video; false when it is not a subtitle file Flow can read.
     */
    suspend fun addSubtitleFile(uri: Uri): Boolean {
        val videoId = playerManager.playerState.value.currentVideoId ?: return false
        val caption = localSubtitles.pick(videoId, uri) ?: return false
        return withContext(Dispatchers.Main) { playerManager.addLocalCaption(caption) }
    }

    /** The folder of the device file or download that is playing, for the subtitle file picker. */
    suspend fun subtitleFolder(): Uri? = uiState.value.localFilePath?.let { localSubtitles.folderDocument(it) }

    /**
     * Shifts the captions of what is playing; a device file or download remembers it for next time.
     */
    suspend fun setSubtitleOffset(offsetMs: Long) {
        val videoId = playerManager.playerState.value.currentVideoId ?: return
        withContext(Dispatchers.Main) { playerManager.setSubtitleOffset(offsetMs) }
        if (uiState.value.localFilePath != null) localSubtitles.setOffsetMs(videoId, offsetMs)
    }

    /**
     * A device video's subtitle files beside it, or a download's stored caption tracks, and in
     * both cases the files picked for it.
     */
    private suspend fun subtitlesFor(
        videoId: String,
        localFilePath: String,
    ): List<ResolvedCaption> {
        val picked = localSubtitles.picked(videoId)
        if (LocalMediaIds.isLocal(videoId)) return localSubtitles.beside(localFilePath) + picked
        return offlineSubtitlesFor(videoId) + picked
    }

    private suspend fun offlineSubtitlesFor(videoId: String): List<ResolvedCaption> {
        val stored = offlineSubtitleStore.load(videoId)
        if (stored.isEmpty() && !offlineSubtitleStore.isResolved(videoId) && NetworkState.isOnline(context)) {
            scope.launch(networkDispatcher) {
                offlineSubtitleStore.saveForVideo(videoId)
            }
        }
        return stored
    }

    private fun cachedDurationSeconds(): Long =
        uiState.value.cachedVideo
            ?.duration
            ?.toLong() ?: 0L

    private companion object {
        const val TAG = "PlaybackSessionApplier"
    }
}
