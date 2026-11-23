package io.github.aedev.flow.ui.screens.player

import android.content.Context
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.PlaybackResumePolicy
import io.github.aedev.flow.player.PlayerRelatedVideosPolicy
import io.github.aedev.flow.player.live.LivePegPolicy
import io.github.aedev.flow.player.sabr.SabrRoutingPolicy
import io.github.aedev.flow.player.sabr.integration.SabrStreamInfo
import io.github.aedev.flow.player.stream.ResolvedCaption
import io.github.aedev.flow.player.stream.ResolvedPlayback
import io.github.aedev.flow.player.stream.VideoCodecUtils
import io.github.aedev.flow.player.stream.model.AudioStream
import io.github.aedev.flow.player.stream.model.StreamType
import io.github.aedev.flow.player.stream.model.VideoStream
import io.github.aedev.flow.utils.NetworkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Every hand-off from the player screen to [EnhancedPlayerManager]: arming the session, choosing the
 * position playback resumes from, pushing the streams, restoring the remembered speed and starting
 * playback — in that order, on the main thread.
 *
 * It owns no screen state and writes none: callers pass plain values and a currency check, so the
 * call sequence a load produces can be asserted against one mocked manager.
 *
 * The manager itself is the injected instance the ViewModel already holds; this class never creates,
 * looks up or releases one.
 */
internal class PlaybackPreparer(
    private val context: Context,
    private val playerManager: EnhancedPlayerManager,
    private val playerPreferences: PlayerPreferences,
    private val cachedCategory: (videoId: String) -> String? = { null },
) {
    /** Arms the player and the media notification for [videoId] before any streams are handed over. */
    suspend fun beginSession(
        videoId: String,
        title: String,
        channel: String,
        thumbnail: String,
    ) = withContext(Dispatchers.Main) {
        playerManager.initialize(context)
        playerManager.startBackgroundService(videoId = videoId, title = title, channel = channel, thumbnail = thumbnail)
    }

    /** Publishes what plays next when the current video ends, and reports whether autoplay is on. */
    suspend fun applyAutoplayCandidates(
        videoId: String,
        videos: List<Video>,
    ): Boolean {
        val unwatched = PlayerRelatedVideosPolicy.withoutWatched(videos, playerManager.watchedSuggestionsSource)
        return withContext(Dispatchers.Main) {
            val autoplay = playerPreferences.autoplayEnabled.first()
            playerManager.setAutoplayCandidates(sourceVideoId = videoId, videos = unwatched, enabled = autoplay)
            autoplay
        }
    }

    /** The InnerTube-only VOD assembly, unpacked onto the full hand-off below. */
    suspend fun prepareVodStreams(
        videoId: String,
        streams: PlaybackStreamPreparer.VodStreams,
        step: ResolvedPlayback.VodFromInnerTube,
        savedPositionMs: Long,
        speedContext: PlaybackSpeedContext,
        isCurrent: () -> Boolean,
    ) = prepareVodStreams(
        videoId = videoId,
        videoStream = streams.videoStream,
        audioStream = streams.audioStream,
        videoStreams = streams.videoStreams,
        audioStreams = streams.audioStreams,
        subtitles = streams.subtitles,
        durationSeconds = streams.durationSeconds,
        savedPositionMs = savedPositionMs,
        resumeOverrideRequested = step.resumePositionOverrideMs != null,
        isAdaptiveMode = streams.isAdaptiveMode,
        sabrInfo = step.result.sabrInfo,
        itVideoFormats = step.result.videoFormats,
        itAudioFormats = step.result.audioFormats,
        preferredVideoCodec = step.preferredCodecKey,
        preferredLiveQualityHeight = step.preferredQuality.height,
        speedContext = speedContext,
        isCurrent = isCurrent,
    )

    /**
     * Returns false when playback was not started — the load is no longer current, or the player
     * already owns this media item — so the caller can skip the work that follows a real start.
     */
    suspend fun prepareLiveStreams(
        videoId: String,
        hlsUrl: String?,
        dashManifestUrl: String?,
        subtitles: List<ResolvedCaption>,
        isCurrent: () -> Boolean,
        isDvrEnabled: Boolean = true,
        pegRule: LivePegPolicy.Rule? = null,
    ): Boolean =
        withContext(Dispatchers.Main) {
            if (!isCurrent()) return@withContext false
            if (playerManager.isPreparedForPlayback(videoId)) return@withContext false

            playerManager.setStreams(
                videoId = videoId,
                videoStream = null,
                audioStream = null,
                videoStreams = emptyList(),
                audioStreams = emptyList(),
                subtitles = subtitles,
                durationSeconds = 0L,
                dashManifestUrl = dashManifestUrl,
                hlsUrl = hlsUrl,
                streamType = StreamType.LIVE_STREAM,
                startPosition = 0L,
                preferredVideoCodec = playerPreferences.videoCodecPriority.first(),
                preferredLiveQualityHeight = preferredDefaultQualityHeight(),
                liveDvrEnabled = isDvrEnabled,
                livePegRule = pegRule,
            )
            applyRememberedPlaybackSpeed(isLive = true)

            if (!isCurrent()) return@withContext false
            if (!playerManager.isStartPausedArmed(videoId)) playerManager.play()
            true
        }

    suspend fun prepareVodStreams(
        videoId: String,
        videoStream: VideoStream?,
        audioStream: AudioStream?,
        videoStreams: List<VideoStream>,
        audioStreams: List<AudioStream>,
        subtitles: List<ResolvedCaption>,
        durationSeconds: Long,
        savedPositionMs: Long,
        resumeOverrideRequested: Boolean,
        isAdaptiveMode: Boolean,
        sabrInfo: SabrStreamInfo?,
        itVideoFormats: List<PlayerResponse.StreamingData.Format>,
        itAudioFormats: List<PlayerResponse.StreamingData.Format>,
        preferredVideoCodec: String,
        preferredLiveQualityHeight: Int,
        isCurrent: () -> Boolean,
        speedContext: PlaybackSpeedContext = PlaybackSpeedContext.Unknown,
    ) = withContext(Dispatchers.Main) {
        if (!isCurrent()) return@withContext
        if (playerManager.isPreparedForPlayback(videoId)) return@withContext

        val resumePosition =
            PlaybackResumePolicy.resolveStartPosition(
                savedPosition = savedPositionMs,
                durationMs = durationSeconds * 1000L,
                resumeAllowed = resumeOverrideRequested || !playerManager.isReachedByQueueAdvance(videoId),
            )
        val directMaxHeight = videoStreams.maxOfOrNull { VideoCodecUtils.qualityHeightFromStream(it) } ?: 0
        // An escalated reload must not force SABR: measured 2026-09-21, a session never receives an
        // init segment, so ExoPlayer cannot sniff it and every attempt dies. Recovery is the
        // re-minted attested direct ladder instead. Revisit when an init segment is observed.
        val preferSabr =
            sabrInfo != null &&
                SabrRoutingPolicy.shouldPreferSabr(false, sabrInfo.videoHeight, directMaxHeight)

        playerManager.setStreams(
            videoId = videoId,
            videoStream = if (isAdaptiveMode) null else videoStream,
            audioStream = audioStream,
            videoStreams = videoStreams,
            audioStreams = audioStreams,
            subtitles = subtitles,
            durationSeconds = durationSeconds,
            dashManifestUrl = null,
            hlsUrl = null,
            streamType = StreamType.VIDEO_STREAM,
            startPosition = resumePosition,
            sabrInfo = sabrInfo,
            itVideoFormats = itVideoFormats,
            itAudioFormats = itAudioFormats,
            preferredVideoCodec = preferredVideoCodec,
            preferSabr = preferSabr,
            preferredLiveQualityHeight = preferredLiveQualityHeight,
        )
        applyRememberedPlaybackSpeed(isLive = false, speedContext = speedContext)

        if (!isCurrent()) return@withContext
        if (!playerManager.isStartPausedArmed(videoId)) playerManager.play()
    }

    suspend fun prepareLocalMedia(
        videoId: String,
        localFilePath: String,
        offlineSegments: List<SponsorBlockSegment>?,
        savedPosition: Long,
        durationMs: Long,
        subtitles: List<ResolvedCaption>,
        isCurrent: () -> Boolean,
        subtitleOffsetMs: Long = 0L,
        speedContext: PlaybackSpeedContext = PlaybackSpeedContext.Unknown,
    ) = withContext(Dispatchers.Main) {
        if (!isCurrent()) return@withContext
        if (playerManager.isPreparedForPlayback(videoId)) return@withContext

        playerManager.initialize(context)
        val startPosition =
            PlaybackResumePolicy.resolveStartPosition(
                savedPosition = savedPosition,
                durationMs = durationMs,
                resumeAllowed = !playerManager.isReachedByQueueAdvance(videoId),
            )
        playerManager.playLocalFile(
            videoId = videoId,
            filePath = localFilePath,
            savedSegments = offlineSegments,
            preservePosition = startPosition.takeIf { it > 0L },
            subtitles = subtitles,
        )
        if (subtitleOffsetMs != 0L) playerManager.setSubtitleOffset(subtitleOffsetMs)
        applyRememberedPlaybackSpeed(isLive = false, speedContext = speedContext)

        if (!isCurrent()) return@withContext
        if (!playerManager.isStartPausedArmed(videoId)) playerManager.play()
    }

    private var speedBeforeOverride: Float? = null

    private suspend fun applyRememberedPlaybackSpeed(
        isLive: Boolean,
        speedContext: PlaybackSpeedContext = PlaybackSpeedContext.Unknown,
    ) {
        if (isLive) {
            playerManager.setPlaybackSpeed(1.0f)
            return
        }
        when (val decision = decideSpeed(speedContext)) {
            is SpeedDecision.Override -> {
                applySpeedOverride(decision.speed)
            }

            is SpeedDecision.Remembered -> {
                speedBeforeOverride = null
                playerManager.setPlaybackSpeed(decision.speed)
            }

            SpeedDecision.Keep -> {
                speedBeforeOverride?.let(playerManager::setPlaybackSpeed)
                speedBeforeOverride = null
            }
        }
    }

    /** The category can arrive after playback started; only a new override is worth a speed change then. */
    suspend fun applyLateMusicSignal(speedContext: PlaybackSpeedContext) =
        withContext(Dispatchers.Main) {
            val decision = decideSpeed(speedContext.copy(knownMusic = true))
            if (decision is SpeedDecision.Override) applySpeedOverride(decision.speed)
        }

    private fun applySpeedOverride(speed: Float) {
        if (speedBeforeOverride == null) speedBeforeOverride = playerManager.playerState.value.playbackSpeed
        playerManager.setPlaybackSpeed(speed)
    }

    private suspend fun decideSpeed(speedContext: PlaybackSpeedContext): SpeedDecision {
        val channelSpeed =
            speedContext.channelId
                ?.takeIf { it.isNotBlank() && playerPreferences.speedPerChannel.first() }
                ?.let { playerPreferences.channelPlaybackSpeed(it) }
        val musicAtNormalSpeed = playerPreferences.musicAtNormalSpeed.first()
        val isMusic =
            musicAtNormalSpeed &&
                PlaybackSpeedPolicy.isMusic(
                    musicVideoType = speedContext.musicVideoType,
                    category = speedContext.videoId?.let(cachedCategory),
                    openedAsMusic = speedContext.knownMusic,
                )
        return PlaybackSpeedPolicy.decide(
            isMusic = isMusic,
            channelSpeed = channelSpeed,
            musicAtNormalSpeed = musicAtNormalSpeed,
            rememberSpeed = playerPreferences.rememberPlaybackSpeed.first(),
            rememberedSpeed = playerPreferences.playbackSpeed.first(),
        )
    }

    private suspend fun preferredDefaultQualityHeight(): Int {
        val quality =
            if (NetworkState.isOnWifi(context)) {
                playerPreferences.defaultQualityWifi.first()
            } else {
                playerPreferences.defaultQualityCellular.first()
            }
        return quality.height
    }

    private companion object {
        const val TAG = "PlaybackPreparer"
    }
}
