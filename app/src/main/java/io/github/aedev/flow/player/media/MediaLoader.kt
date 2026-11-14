package io.github.aedev.flow.player.media

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import io.github.aedev.flow.R
import io.github.aedev.flow.player.cache.PlayerCacheManager
import io.github.aedev.flow.player.config.PlayerConfig
import io.github.aedev.flow.player.datasource.PlatformCaptionTransport
import io.github.aedev.flow.player.renderer.subtitle.Srv3SubtitleParser
import io.github.aedev.flow.player.resolver.VideoPlaybackResolver
import io.github.aedev.flow.player.sabr.integration.SabrMediaSourceFactory
import io.github.aedev.flow.player.sabr.integration.SabrMediaSourceResult
import io.github.aedev.flow.player.sabr.integration.SabrOrchestrator
import io.github.aedev.flow.player.sabr.integration.SabrStreamInfo
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.player.stream.ResolvedCaption
import io.github.aedev.flow.player.stream.StreamProcessor
import io.github.aedev.flow.player.stream.VideoCodecUtils
import io.github.aedev.flow.player.surface.SurfaceManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.util.Locale

/**
 * Handles media loading and resolution.
 */
@UnstableApi
class MediaLoader(
    private val appContext: Context,
    private val stateFlow: MutableStateFlow<EnhancedPlayerState>,
    private val cacheManager: PlayerCacheManager?,
    private val surfaceManager: SurfaceManager?,
) {
    // Android 13 and older have no platform Cronet, and bundling one adds ~6 MB to each APK.
    private val platformCaptions =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) PlatformCaptionTransport(appContext) else null

    companion object {
        private const val TAG = "MediaLoader"

        init {
            // TrackGroup derives its type from MimeTypes.getTrackType(sampleMimeType), so without
            // this an srv3 track group reports TRACK_TYPE_UNKNOWN and every `groups.filter { it.type
            // == C.TRACK_TYPE_TEXT }` lookup - including EnhancedPlayerManager's track-id override -
            // silently stops finding it. Registration is idempotent and keyed by MIME type, and
            // the empty codec prefix is right: srv3 never appears in a Format's codec string.
            MimeTypes.registerCustomMimeType(Srv3SubtitleParser.MIME_TYPE, "", C.TRACK_TYPE_TEXT)
        }

        private const val SUBTITLE_TRACK_ID_PREFIX = "flow-subtitle-"
        private val SubtitleTrackIdPattern = Regex("""(?:^|:)$SUBTITLE_TRACK_ID_PREFIX(\d+)$""")

        internal fun subtitleTrackId(index: Int): String = "$SUBTITLE_TRACK_ID_PREFIX$index"

        /**
         * The caption index a track's format id names, or null for a track Flow did not add.
         * Merging sources prefix every format id with the source's position, so the id is matched
         * at its end.
         */
        internal fun subtitleTrackIndex(formatId: String?): Int? =
            formatId
                ?.let { SubtitleTrackIdPattern.find(it) }
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
    }

    private var activeSabrOrchestrator: SabrOrchestrator? = null
    private var lastSourceWasSabr = false

    /** Whether the media last loaded is a quality ladder that Media3 adapts over by itself. */
    var lastSourceWasAdaptiveLadder = false
        private set
    var onSabrFallbackNeeded: (() -> Unit)? = null

    /** Invoked with a subtitle track's index and display label once its fetch has finally given up. */
    var onSubtitleLoadFailed: ((Int, String) -> Unit)? = null

    /**
     * Load media with video and audio streams.
     *
     * @param player ExoPlayer instance
     * @param context Application context
     * @param videoStream Video stream to load (can be null for audio-only)
     * @param audioStream Audio stream to load
     * @param availableVideoStreams All available video streams for fallback
     * @param currentVideoStream Current video stream reference
     * @param dashManifestUrl Optional DASH manifest URL
     * @param durationSeconds Duration in seconds
     * @param preservePosition Position to seek to after loading
     * @param localFilePath Optional local file path for offline playback
     * @param currentDurationSeconds Fallback duration from stream info
     * @param audioOnly When true, never selects video streams or video manifests.
     * @param playWhenReady Whether playback should start after the source is prepared.
     */
    fun loadMedia(
        player: ExoPlayer?,
        context: Context?,
        videoStream: VideoStream?,
        audioStream: AudioStream?,
        availableVideoStreams: List<VideoStream>,
        currentVideoStream: VideoStream?,
        dashManifestUrl: String?,
        hlsUrl: String?,
        isLiveStream: Boolean = false,
        durationSeconds: Long,
        currentDurationSeconds: Long,
        preservePosition: Long? = null,
        localFilePath: String? = null,
        audioOnly: Boolean = false,
        playWhenReady: Boolean = true,
        captions: List<ResolvedCaption> = emptyList(),
        sabrInfo: SabrStreamInfo? = null,
        sabrVideoId: String? = null,
        sabrPreferred: Boolean = false,
        innerTubeVideoFormats: List<io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format> = emptyList(),
        innerTubeAudioFormats: List<io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format> = emptyList(),
        mediaId: String = "",
        mediaMetadata: MediaMetadata = MediaMetadata.EMPTY,
        adaptiveLadder: List<VideoStream> = emptyList(),
    ): Boolean {
        val finalDuration =
            when {
                durationSeconds > 0 -> durationSeconds
                currentDurationSeconds > 0 -> currentDurationSeconds
                else -> 0L
            }

        player?.let { exoPlayer ->
            try {
                // Reattach surface before loading
                if (!audioOnly) {
                    reattachSurface(exoPlayer)
                }

                Log.d(
                    TAG,
                    "Preparing media: video=${videoStream?.let(
                        VideoCodecUtils::qualityHeightFromStream,
                    ) ?: -1}p audioOnly=$audioOnly surfaceReady=${surfaceManager?.isSurfaceReady}",
                )

                val ctx = context ?: throw IllegalStateException("Context not initialized")
                val dataSourceFactory =
                    cacheManager?.getDataSourceFactory()
                        ?: DefaultDataSource.Factory(ctx)

                if (!audioOnly && surfaceManager?.isSurfaceReady != true && localFilePath == null) {
                    Log.w(TAG, "Surface not ready yet, preparing media and waiting for attach")
                }

                Log.d(TAG, "Resolving media with VideoPlaybackResolver for duration ${finalDuration}s")

                lastSourceWasSabr = false
                lastSourceWasAdaptiveLadder = false
                val mediaSource =
                    createMediaSource(
                        context = ctx,
                        dataSourceFactory = dataSourceFactory,
                        videoStream = videoStream,
                        audioStream = audioStream,
                        availableVideoStreams = availableVideoStreams,
                        currentVideoStream = currentVideoStream,
                        dashManifestUrl = dashManifestUrl,
                        hlsUrl = hlsUrl,
                        isLiveStream = isLiveStream,
                        finalDuration = finalDuration,
                        localFilePath = localFilePath,
                        audioOnly = audioOnly,
                        captions = captions,
                        sabrInfo = sabrInfo,
                        sabrVideoId = sabrVideoId,
                        sabrPreferred = sabrPreferred,
                        startPositionMs = preservePosition ?: 0L,
                        innerTubeVideoFormats = innerTubeVideoFormats,
                        innerTubeAudioFormats = innerTubeAudioFormats,
                        mediaId = mediaId,
                        mediaMetadata = mediaMetadata,
                        adaptiveLadder = adaptiveLadder,
                        onAdaptiveLadder = { lastSourceWasAdaptiveLadder = it },
                    )

                if (mediaSource != null) {
                    exoPlayer.setMediaSource(mediaSource)
                    exoPlayer.prepare()
                    stateFlow.value = stateFlow.value.copy(isPrepared = true)

                    // SABR sessions already start fetching at the position; seeking the
                    // unseekable progressive pipe would restart extraction.
                    if (preservePosition != null && preservePosition > 0 && !lastSourceWasSabr) {
                        exoPlayer.seekTo(preservePosition)
                        Log.d(TAG, "Seeking to preserved position: ${preservePosition}ms")
                    }

                    exoPlayer.playWhenReady = playWhenReady
                    Log.d(TAG, "Media loaded successfully via VideoPlaybackResolver")
                    return true
                } else {
                    Log.e(TAG, "Failed to resolve media source - streams invalid")
                    stateFlow.value =
                        stateFlow.value.copy(error = appContext.getString(R.string.error_failed_to_load_media_invalid_streams))
                    return false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading media", e)
                stateFlow.value =
                    stateFlow.value.copy(error = appContext.getString(R.string.error_failed_to_load_media, e.message.orEmpty()))
                return false
            }
        }
        return false
    }

    fun buildPreloadMediaSource(
        context: Context?,
        videoStream: VideoStream?,
        audioStream: AudioStream?,
        availableVideoStreams: List<VideoStream>,
        dashManifestUrl: String?,
        durationSeconds: Long,
        captions: List<ResolvedCaption> = emptyList(),
        mediaId: String = "",
        mediaMetadata: MediaMetadata = MediaMetadata.EMPTY,
        adaptiveLadder: List<VideoStream> = emptyList(),
    ): MediaSource? {
        val ctx = context ?: return null
        val dataSourceFactory = cacheManager?.getDataSourceFactory() ?: DefaultDataSource.Factory(ctx)
        return try {
            createMediaSource(
                context = ctx,
                dataSourceFactory = dataSourceFactory,
                videoStream = videoStream,
                audioStream = audioStream,
                availableVideoStreams = availableVideoStreams,
                currentVideoStream = videoStream,
                dashManifestUrl = dashManifestUrl,
                hlsUrl = null,
                isLiveStream = false,
                finalDuration = durationSeconds,
                localFilePath = null,
                audioOnly = false,
                captions = captions,
                mediaId = mediaId,
                mediaMetadata = mediaMetadata,
                adaptiveLadder = adaptiveLadder,
            )
        } catch (e: Exception) {
            Log.w(TAG, "buildPreloadMediaSource failed", e)
            null
        }
    }

    private fun reattachSurface(player: ExoPlayer) {
        surfaceManager?.let { sm ->
            val holder = sm.getSurfaceHolder()
            if (holder?.surface?.isValid == true) {
                sm.attachVideoSurface(holder, player, forceAttach = false)
            }
        }
    }

    fun releaseSabr() {
        activeSabrOrchestrator?.release()
        activeSabrOrchestrator = null
    }

    fun getActiveSabrOrchestrator(): SabrOrchestrator? = activeSabrOrchestrator

    private fun createMediaSource(
        context: Context,
        dataSourceFactory: DataSource.Factory,
        videoStream: VideoStream?,
        audioStream: AudioStream?,
        availableVideoStreams: List<VideoStream>,
        currentVideoStream: VideoStream?,
        dashManifestUrl: String?,
        hlsUrl: String?,
        isLiveStream: Boolean,
        finalDuration: Long,
        localFilePath: String?,
        audioOnly: Boolean,
        captions: List<ResolvedCaption>,
        sabrInfo: SabrStreamInfo? = null,
        sabrVideoId: String? = null,
        sabrPreferred: Boolean = false,
        startPositionMs: Long = 0L,
        innerTubeVideoFormats: List<io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format> = emptyList(),
        innerTubeAudioFormats: List<io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format> = emptyList(),
        mediaId: String = "",
        mediaMetadata: MediaMetadata = MediaMetadata.EMPTY,
        adaptiveLadder: List<VideoStream> = emptyList(),
        onAdaptiveLadder: (Boolean) -> Unit = {},
    ): MediaSource? {
        cacheManager?.registerStreams(
            videoId = mediaId,
            urls =
                availableVideoStreams.map { it.content } +
                    listOfNotNull(videoStream?.content, currentVideoStream?.content, audioStream?.content) +
                    innerTubeVideoFormats.map { it.url } +
                    innerTubeAudioFormats.map { it.url },
        )
        val sabrAvailable =
            sabrInfo != null && sabrInfo.streamingUrl.isNotEmpty() &&
                sabrVideoId != null && sabrInfo.audioItag > 0 && sabrInfo.videoItag > 0

        val overridesDefaultAudio = StreamProcessor.overridesDefaultAudioTrack(audioStream)

        if (sabrAvailable && sabrPreferred && !overridesDefaultAudio) {
            createSabrMediaSource(
                sabrInfo!!,
                sabrVideoId!!,
                finalDuration,
                startPositionMs,
                mediaId,
                mediaMetadata,
            )?.let { return mergeSubtitleSourcesIfNeeded(it, captions, dataSourceFactory, context) }
        }

        val mediaSource =
            if (localFilePath != null) {
                val localUri =
                    if (localFilePath.startsWith("content://")) {
                        android.net.Uri.parse(localFilePath)
                    } else {
                        android.net.Uri.fromFile(File(localFilePath))
                    }
                val localItem =
                    MediaItem
                        .Builder()
                        .setUri(localUri)
                        .setMediaId(mediaId)
                        .setMediaMetadata(mediaMetadata)
                        .apply { localFileMimeType(localUri)?.let(::setMimeType) }
                        .build()

                // Tracks inside the file reach the text renderer untranscoded, so they are decoded
                // by the same shifting decoders as sidecar files and follow the subtitle timing.
                @Suppress("DEPRECATION")
                val extractors = DefaultExtractorsFactory().experimentalSetTextTrackTranscodingEnabled(false)
                ProgressiveMediaSource
                    .Factory(DefaultDataSource.Factory(context), extractors)
                    .createMediaSource(localItem)
            } else {
                val resolver =
                    VideoPlaybackResolver(
                        cacheManager?.getDashDataSourceFactory() ?: dataSourceFactory,
                        cacheManager?.getProgressiveDataSourceFactory() ?: dataSourceFactory,
                        cacheManager?.getLiveDashDataSourceFactory()
                            ?: cacheManager?.getDashDataSourceFactory()
                            ?: dataSourceFactory,
                        cacheManager?.getLiveHlsDataSourceFactory()
                            ?: cacheManager?.getHlsDataSourceFactory()
                            ?: dataSourceFactory,
                        mediaId = mediaId,
                        mediaMetadata = mediaMetadata,
                    )

                val useLadder = !audioOnly && adaptiveLadder.size > 1
                val selectedStreams =
                    if (audioOnly) {
                        emptyList()
                    } else if (useLadder) {
                        adaptiveLadder
                    } else if (videoStream != null) {
                        listOf(videoStream)
                    } else if (!dashManifestUrl.isNullOrEmpty() && availableVideoStreams.size > 1) {
                        availableVideoStreams
                    } else {
                        listOfNotNull(currentVideoStream ?: availableVideoStreams.firstOrNull())
                    }
                Log.d(
                    TAG,
                    "Passing ${selectedStreams.size} stream(s) to resolver: ${selectedStreams.map {
                        "${VideoCodecUtils.qualityHeightFromStream(
                            it,
                        )}p"
                    }}",
                )
                resolver
                    .resolve(
                        selectedStreams,
                        audioStream,
                        dashManifestUrl = if (audioOnly) null else dashManifestUrl,
                        hlsUrl = if (audioOnly) null else hlsUrl,
                        durationSeconds = finalDuration,
                        isLiveStream = isLiveStream && !audioOnly,
                        asAdaptiveLadder = useLadder,
                    ).also { onAdaptiveLadder(resolver.resolvedAdaptiveLadder) }
            }

        if (mediaSource == null && sabrAvailable && !sabrPreferred) {
            Log.w(TAG, "No playable extractor streams — falling back to native SABR session")
            createSabrMediaSource(
                sabrInfo!!,
                sabrVideoId!!,
                finalDuration,
                startPositionMs,
                mediaId,
                mediaMetadata,
            )?.let { return mergeSubtitleSourcesIfNeeded(it, captions, dataSourceFactory, context) }
        }

        return mergeSubtitleSourcesIfNeeded(mediaSource, captions, dataSourceFactory, context)
    }

    private fun localFileMimeType(uri: Uri): String? =
        when (
            uri.lastPathSegment?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase(Locale.US)
        ) {
            "mp4", "m4v", "mov" -> MimeTypes.VIDEO_MP4
            "webm" -> MimeTypes.VIDEO_WEBM
            "mkv" -> "video/x-matroska"
            else -> null
        }

    private fun createSabrMediaSource(
        info: SabrStreamInfo,
        videoId: String,
        finalDuration: Long,
        startPositionMs: Long,
        mediaId: String,
        mediaMetadata: MediaMetadata,
    ): MediaSource? =
        try {
            releaseSabr()
            val durationMs = info.durationMs.takeIf { it > 0 } ?: (finalDuration * 1000L)
            val result =
                SabrMediaSourceFactory.create(
                    info = info,
                    videoId = videoId,
                    durationMs = durationMs,
                    startPositionMs = startPositionMs,
                    mediaId = mediaId,
                    mediaMetadata = mediaMetadata,
                )
            activeSabrOrchestrator = result.orchestrator
            result.orchestrator.onError = { _, msg, recoverable ->
                if (!recoverable) {
                    Log.w(TAG, "SABR non-recoverable error: $msg — triggering fallback")
                    onSabrFallbackNeeded?.invoke()
                }
            }
            result.orchestrator.start()
            lastSourceWasSabr = true
            Log.d(TAG, "Using SABR MediaSource for $videoId (startPos=${startPositionMs}ms)")
            result.mediaSource
        } catch (e: Exception) {
            Log.w(TAG, "SABR MediaSource creation failed, falling back to DASH/Progressive", e)
            releaseSabr()
            null
        }

    private fun mergeSubtitleSourcesIfNeeded(
        mediaSource: MediaSource?,
        captions: List<ResolvedCaption>,
        dataSourceFactory: DataSource.Factory,
        context: Context,
    ): MediaSource? {
        if (mediaSource == null || captions.isEmpty()) return mediaSource

        val localDataSourceFactory by lazy { DefaultDataSource.Factory(context) }
        val captionTransport = platformCaptions

        val subtitleSources =
            captions.mapIndexedNotNull { index, caption ->
                val subtitleUrl = caption.url.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                val uri = Uri.parse(subtitleUrl)
                val language = caption.languageTag.takeIf { it.isNotBlank() }
                val label = caption.label ?: language ?: "Unknown"
                val subtitleConfig =
                    MediaItem.SubtitleConfiguration
                        .Builder(uri)
                        .setMimeType(caption.format.mimeType)
                        .setLanguage(language)
                        .setLabel(if (caption.isAutoGenerated) "$label (Auto)" else label)
                        .setSelectionFlags(0)
                        .setRoleFlags(
                            if (caption.isAutoGenerated) {
                                C.ROLE_FLAG_SUBTITLE or C.ROLE_FLAG_TRANSCRIBES_DIALOG
                            } else {
                                C.ROLE_FLAG_SUBTITLE
                            },
                        ).setId(subtitleTrackId(index))
                        .build()

                val factory =
                    when {
                        !uri.scheme.equals("http", true) && !uri.scheme.equals("https", true) -> localDataSourceFactory
                        captionTransport != null && captionTransport.handles(uri) -> captionTransport
                        else -> dataSourceFactory
                    }
                SingleSampleMediaSource
                    .Factory(factory)
                    .setLoadErrorHandlingPolicy(SubtitleLoadErrorHandlingPolicy(caption.isTranslated))
                    // Stays true: a propagated subtitle error would surface as a fatal
                    // ExoPlaybackException and stop the video over a failed sidecar text track.
                    .setTreatLoadErrorsAsEndOfStream(true)
                    .createMediaSource(subtitleConfig, C.TIME_UNSET)
                    .also { source ->
                        source.addEventListener(
                            Handler(Looper.getMainLooper()),
                            subtitleLoadFailureReporter(index, label, subtitleUrl) { failedIndex, failedLabel ->
                                onSubtitleLoadFailed?.invoke(failedIndex, failedLabel)
                            },
                        )
                    }
            }

        if (subtitleSources.isEmpty()) return mediaSource

        Log.d(TAG, "Merged ${subtitleSources.size} subtitle source(s)")
        return MergingMediaSource(
            true,
            true,
            mediaSource,
            *subtitleSources.toTypedArray(),
        )
    }
}

/**
 * Retry policy for sidecar subtitle fetches.
 *
 * YouTube throttles `timedtext` requests that carry `&tlang=` far more aggressively than plain
 * caption fetches — a 429 on a translated track while the untranslated one loads fine from the same
 * IP seconds later is routine. The default three quick attempts frequently fall inside one throttle
 * window, so translated captions get one shot and then look permanently broken; backing off further
 * usually rides it out. Statuses that retrying cannot fix are given up on immediately.
 */
@UnstableApi
private class SubtitleLoadErrorHandlingPolicy(
    private val isTranslated: Boolean,
) : DefaultLoadErrorHandlingPolicy() {
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = if (isTranslated) TRANSLATED_MAX_ATTEMPTS else MAX_ATTEMPTS

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val status =
            (loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                ?: return super.getRetryDelayMsFor(loadErrorInfo)
        val isTransient = status == HTTP_TOO_MANY_REQUESTS || status >= HTTP_SERVER_ERROR
        if (!isTransient) return C.TIME_UNSET
        val exponent = (loadErrorInfo.errorCount - 1).coerceIn(0, MAX_BACKOFF_EXPONENT)
        val initial = if (isTranslated) TRANSLATED_INITIAL_BACKOFF_MS else INITIAL_BACKOFF_MS
        val ceiling = if (isTranslated) TRANSLATED_MAX_BACKOFF_MS else MAX_BACKOFF_MS
        return (initial shl exponent).coerceAtMost(ceiling)
    }

    private companion object {
        const val MAX_ATTEMPTS = 6
        const val INITIAL_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 8_000L

        // A tlang fetch is not rate-limited, it is refused by Google's abuse interstitial: no
        // Retry-After, and it hardens against the IP as attempts continue. Fewer, slower tries
        // give the block time to lapse while the caller falls back to the source track.
        const val TRANSLATED_MAX_ATTEMPTS = 3
        const val TRANSLATED_INITIAL_BACKOFF_MS = 2_000L
        const val TRANSLATED_MAX_BACKOFF_MS = 20_000L
        const val MAX_BACKOFF_EXPONENT = 4
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}
