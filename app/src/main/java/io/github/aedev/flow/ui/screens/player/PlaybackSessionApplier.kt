package io.github.aedev.flow.ui.screens.player

import android.content.Context
import android.util.Log
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.ViewHistory
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

/** The load a step belongs to: the video it resolved for, and the token saying it is still current. */
internal data class LoadContext(
    val videoId: String,
    val token: Long,
)

/**
 * Applies what one load resolved: the screen state each step lands on, the hand-off to the player,
 * and the metadata fetches that follow it — in the order the load produced them.
 *
 * This is the second writer of the player screen's state, alongside [PlaybackRequestController]:
 * that one owns the session entry points and the player-state mirror, this class owns everything a
 * resolved step and its secondary metadata ([SecondaryMetadataApplier]) land on. Both write the one [MutableStateFlow] the
 * ViewModel constructs and passes in, and both gate on the same load token, so a superseded load
 * stops writing at exactly the points it used to.
 *
 * Every collaborator here — the player manager included — is the instance the ViewModel already
 * holds; this class creates, looks up and releases none of them, only its own parts.
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
                armCountdownMetadata(load, step.relatedVideos)
            }

            is ResolvedPlayback.Failed -> {
                applyPlaybackFailure(load, step)
            }
        }
    }

    private val secondaryApplier =
        SecondaryMetadataApplier(
            context = context,
            uiState = uiState,
            isLoadCurrent = isLoadCurrent,
            playbackPreparer = playbackPreparer,
            secondaryMetadata = secondaryMetadata,
            playerPreferences = playerPreferences,
            playerManager = playerManager,
            scope = scope,
            ioDispatcher = ioDispatcher,
        )

    fun applySecondary(result: SecondaryMetadata) = secondaryApplier.apply(result)

    fun armCountdownMetadata(
        load: LoadContext,
        relatedVideos: List<Video>,
    ) = secondaryApplier.armCountdownMetadata(load, relatedVideos)

    val subtitleFiles = PlayerSubtitleFiles(context, uiState, playerManager, localSubtitles, offlineSubtitleStore, scope, networkDispatcher)

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
            subtitles = subtitleFiles.forFile(load.videoId, localFilePath),
            isCurrent = { isLoadCurrent(load.token) },
            subtitleOffsetMs = localSubtitles.offsetMs(load.videoId),
            speedContext = uiState.value.speedContextFor(load.videoId),
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
                armCountdownMetadata(load, step.relatedVideos)
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
        secondaryMetadata.loadWatchHeader(load.videoId, load.token)
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
                isDvrEnabled = streams.isDvrEnabled,
                pegRule = streams.pegRule,
            )
        if (!liveStarted) return@withContext

        secondaryMetadata.loadChannelMetadata(
            videoId = videoId,
            embeddedAvatarUrls = identity.embeddedAvatarUrls,
            loadToken = load.token,
        )

        liveChat.start(videoId)

        secondaryMetadata.refreshLiveWatchMetadata(videoId, identity.enrichedVideo, load.token)
        secondaryMetadata.loadWatchHeader(videoId, load.token)
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
            it
                .applyVodStreams(
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
                ).copy(streamClient = result.usedClient.clientName)
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
        secondaryMetadata.loadWatchHeader(videoId, load.token)
        secondaryMetadata.loadPaidPromotion(videoId, load.token)
        secondaryMetadata.loadWatchInfo(videoId, identity.enrichedVideo, load.token)
        secondaryMetadata.loadChannelMetadata(
            videoId = videoId,
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
                uiState.value.speedContextFor(
                    videoId = videoId,
                    musicVideoType = result.playerResponse.videoDetails?.musicVideoType,
                    channelId = identity.channelId,
                ),
            isCurrent = { isLoadCurrent(load.token) },
        )
    }

    private companion object {
        const val TAG = "PlaybackSessionApplier"
    }
}
