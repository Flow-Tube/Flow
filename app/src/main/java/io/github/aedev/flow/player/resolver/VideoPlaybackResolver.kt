package io.github.aedev.flow.player.resolver

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import io.github.aedev.flow.player.config.PlayerConfig
import io.github.aedev.flow.player.live.LiveSegmentMediaSource
import io.github.aedev.flow.player.live.LiveSegmentSessions
import io.github.aedev.flow.player.stream.StreamProcessor
import io.github.aedev.flow.player.stream.VideoCodecUtils
import io.github.aedev.flow.player.stream.model.AudioStream
import io.github.aedev.flow.player.stream.model.VideoStream

/**
 * Specialized resolver for video playback that handles different stream types
 * including generating local DASH manifests to avoid YouTube throttling.
 *
 * YouTube throttles progressive (direct URL) streams to ~50-100 KB/s, which causes
 * buffering even on fast connections. The solution is to convert progressive URLs
 * into DASH manifests, which are not throttled and allow adaptive bitrate switching.
 *
 * Priority order:
 * 1. YouTube DASH manifest URL (streamInfo.dashMpdUrl) - Best for adaptive playback
 * 2. Generated DASH manifest from progressive streams - Avoids throttling
 * 3. Direct progressive stream - Last resort, may buffer
 */
class VideoPlaybackResolver(
    private val dashDataSourceFactory: DataSource.Factory,
    private val progressiveDataSourceFactory: DataSource.Factory,
    private val liveDashDataSourceFactory: DataSource.Factory = dashDataSourceFactory,
    private val liveHlsDataSourceFactory: DataSource.Factory = progressiveDataSourceFactory,
    private val liveSegmentDataSourceFactory: DataSource.Factory? = null,
    private val mediaId: String = "",
    private val mediaMetadata: MediaMetadata = MediaMetadata.EMPTY,
) {
    companion object {
        private const val TAG = "VideoPlaybackResolver"
        private const val PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT = 15.0
    }

    /** Whether the last [resolve] handed Media3 a quality ladder to adapt over. */
    var resolvedAdaptiveLadder = false
        private set

    fun resolve(
        videoStreams: List<VideoStream>,
        audioStream: AudioStream?,
        dashManifestUrl: String?,
        hlsUrl: String?,
        durationSeconds: Long,
        isLiveStream: Boolean = false,
        asAdaptiveLadder: Boolean = false,
    ): MediaSource? {
        resolvedAdaptiveLadder = false
        Log.d(
            TAG,
            "Resolving playback: ${videoStreams.size} video streams, audio=${audioStream != null}, " +
                "dash=${!dashManifestUrl.isNullOrEmpty()}, hls=${!hlsUrl.isNullOrEmpty()}, duration=${durationSeconds}s",
        )

        val liveSession = dashManifestUrl?.takeIf { isLiveStream }?.let { LiveSegmentSessions.forUri(Uri.parse(it)) }
        if (liveSession != null && liveSegmentDataSourceFactory != null) {
            Log.d(TAG, "Using live segments for ${liveSession.videoId}: ${liveSession.formats.size} formats")
            return LiveSegmentMediaSource.create(liveSession, liveSegmentDataSourceFactory, mediaId, mediaMetadata)
        }

        if (isLiveStream && !hlsUrl.isNullOrEmpty()) {
            try {
                Log.d(TAG, "Using YouTube HLS manifest for live playback: ${hlsUrl.take(80)}...")

                val liveItem =
                    playbackItem(hlsUrl)
                        .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                        .setLiveConfiguration(
                            androidx.media3.common.MediaItem.LiveConfiguration
                                .Builder()
                                .setTargetOffsetMs(PlayerConfig.LIVE_EDGE_GAP_MS)
                                .build(),
                        ).build()

                return androidx.media3.exoplayer.hls.HlsMediaSource
                    .Factory(liveHlsDataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .setPlaylistTrackerFactory {
                        dataSourceFactory,
                        loadErrorHandlingPolicy,
                        playlistParserFactory,
                        cmcdConfiguration,
                        downloadExecutorSupplier,
                        ->
                        DefaultHlsPlaylistTracker(
                            dataSourceFactory,
                            loadErrorHandlingPolicy,
                            playlistParserFactory,
                            cmcdConfiguration,
                            PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT,
                            downloadExecutorSupplier,
                        )
                    }.createMediaSource(liveItem)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to build live HLS media source, falling back to DASH", e)
            }
        }

        if (isLiveStream && !dashManifestUrl.isNullOrEmpty() && !LiveSegmentSessions.isSessionUri(dashManifestUrl)) {
            try {
                Log.d(TAG, "Using YouTube DASH manifest for live DVR playback: ${dashManifestUrl.take(80)}...")

                val liveDashItem =
                    playbackItem(dashManifestUrl)
                        .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
                        .setLiveConfiguration(
                            androidx.media3.common.MediaItem.LiveConfiguration
                                .Builder()
                                .setTargetOffsetMs(PlayerConfig.LIVE_EDGE_GAP_MS)
                                .build(),
                        ).build()

                return androidx.media3.exoplayer.dash.DashMediaSource
                    .Factory(liveDashDataSourceFactory)
                    .createMediaSource(liveDashItem)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to build live DASH media source", e)
            }
        }

        // If only 1 video stream is passed, the user selected a specific quality
        // In this case, DON'T use YouTube's DASH URL (which has all qualities) - use the specific stream
        val useSpecificStream = videoStreams.size == 1

        // YouTube's manifest carries only the default audio track, so honouring it would silently
        // ignore a dub the user picked. Fall through to the generated manifest, which is built from
        // the selected stream.
        val overridesDefaultAudio = StreamProcessor.overridesDefaultAudioTrack(audioStream)

        // 2. Priority: YouTube's native DASH manifest (only for adaptive/auto mode with multiple streams)
        if (!dashManifestUrl.isNullOrEmpty() && !useSpecificStream && !overridesDefaultAudio) {
            try {
                Log.d(TAG, "Using YouTube DASH manifest for adaptive playback: ${dashManifestUrl.take(80)}...")
                val dashItem =
                    playbackItem(dashManifestUrl)
                        .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
                        .build()
                return androidx.media3.exoplayer.dash.DashMediaSource
                    .Factory(dashDataSourceFactory)
                    .createMediaSource(dashItem)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to use YouTube DASH manifest, trying generated manifests", e)
            }
        } else if (overridesDefaultAudio) {
            Log.d(TAG, "Non-default audio track selected (${audioStream?.audioTrackName}) - bypassing YouTube DASH URL")
        } else if (useSpecificStream) {
            Log.d(
                TAG,
                "User selected specific quality (${videoStreams.firstOrNull()?.let(
                    VideoCodecUtils::qualityHeightFromStream,
                )}p) - bypassing YouTube DASH URL",
            )
        }

        // 3. Generate DASH manifests from progressive streams (NewPipe approach)
        // This avoids YouTube's progressive throttling (~50-100 KB/s limit)
        val ladderSource = if (asAdaptiveLadder) createLadderSource(videoStreams, durationSeconds) else null
        resolvedAdaptiveLadder = ladderSource != null
        // A ladder that cannot be built starts on its lowest rung; Auto's own checks step up from there.
        val videoSource =
            ladderSource ?: createVideoSource(
                videoStreams = if (asAdaptiveLadder) videoStreams.take(1) else videoStreams,
                durationSeconds = durationSeconds,
                preferMuxed = audioStream == null,
            )
        val audioSource = createAudioSource(audioStream, durationSeconds)

        return when {
            videoSource != null && audioSource != null -> {
                Log.d(TAG, "Created MergingMediaSource with video + audio (sync adjusted)")
                MergingMediaSource(true, true, videoSource, audioSource)
            }

            videoSource != null -> {
                Log.d(TAG, "Using video source only (no separate audio)")
                videoSource
            }

            audioSource != null -> {
                Log.d(TAG, "Using audio source only")
                audioSource
            }

            else -> {
                Log.e(TAG, "Failed to create any media source!")
                null
            }
        }
    }

    private fun createLadderSource(
        videoStreams: List<VideoStream>,
        durationSeconds: Long,
    ): MediaSource? {
        val manifest =
            try {
                AdaptiveDashManifest.build(videoStreams, durationSeconds)
            } catch (e: Exception) {
                Log.w(TAG, "Adaptive ladder manifest failed, using a single stream", e)
                null
            } ?: return null
        Log.d(TAG, "Adaptive ladder: ${videoStreams.map { "${VideoCodecUtils.qualityHeightFromStream(it)}p" }}")
        return DashMediaSource
            .Factory(dashDataSourceFactory)
            .createMediaSource(manifest, playbackItem(videoStreams.first().content).build())
    }

    /**
     * Create video source, preferring generated DASH manifests over raw progressive
     */
    private fun createVideoSource(
        videoStreams: List<VideoStream>,
        durationSeconds: Long,
        preferMuxed: Boolean,
    ): MediaSource? {
        if (videoStreams.isEmpty()) return null

        // Sort by quality and prefer video-only streams (they can use DASH manifests)
        val sortedStreams =
            videoStreams.sortedWith(
                compareByDescending<VideoStream> { VideoCodecUtils.qualityHeightFromStream(it) }
                    .thenBy { VideoCodecUtils.playbackCodecRank(it) }
                    .thenByDescending { it.bitrate },
            )

        val bestStream =
            if (preferMuxed) {
                sortedStreams.firstOrNull { !it.isVideoOnly } ?: sortedStreams.firstOrNull()
            } else {
                sortedStreams.firstOrNull { it.isVideoOnly } ?: sortedStreams.firstOrNull()
            }

        if (bestStream == null) return null

        return createOptimalVideoSource(bestStream, durationSeconds)
    }

    /**
     * Create the optimal video source based on stream type and delivery method.
     * Prioritizes DASH manifest generation to avoid YouTube throttling.
     */
    private fun createOptimalVideoSource(
        stream: VideoStream,
        durationSeconds: Long,
    ): MediaSource? {
        val url = stream.content
        if (url.isNullOrEmpty()) {
            Log.w(TAG, "Video stream has null/empty URL")
            return null
        }

        Log.d(TAG, "Creating video source: ${VideoCodecUtils.qualityHeightFromStream(stream)}p, videoOnly=${stream.isVideoOnly}")

        // Every stream is a progressive googlevideo URL; a generated DASH manifest over it avoids throttling.
        return try {
            if (stream.isVideoOnly && durationSeconds > 0) {
                createProgressiveDashSource(stream, durationSeconds)
            } else {
                createProgressiveSource(stream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating video source, falling back to progressive", e)
            createProgressiveSource(stream)
        }
    }

    /**
     * Create audio source, preferring DASH manifests to avoid throttling
     */
    private fun createAudioSource(
        audioStream: AudioStream?,
        durationSeconds: Long,
    ): MediaSource? {
        if (audioStream == null) return null

        val url = audioStream.content
        if (url.isNullOrEmpty()) {
            Log.w(TAG, "Audio stream has null/empty URL")
            return null
        }

        Log.d(TAG, "Creating audio source: ${audioStream.averageBitrate}kbps")

        return try {
            if (durationSeconds > 0 && audioStream.itagItem != null) {
                createProgressiveDashSourceForAudio(audioStream, durationSeconds)
            } else {
                createProgressiveSourceForAudio(audioStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating audio source, falling back to progressive", e)
            createProgressiveSourceForAudio(audioStream)
        }
    }

    /**
     * Create DASH source from progressive stream URL (avoids YouTube throttling!)
     */
    private fun createProgressiveDashSource(
        stream: VideoStream,
        durationSeconds: Long,
    ): MediaSource {
        val itagItem = stream.itagItem

        if (itagItem != null && durationSeconds > 0) {
            Log.d(TAG, "Generating progressive DASH manifest for ${VideoCodecUtils.qualityHeightFromStream(stream)}p to avoid throttling")
            val manifestString = ManifestGenerator.generateProgressiveManifest(stream, itagItem, durationSeconds)

            if (manifestString != null) {
                return MediaSourceBuilder.buildDashSource(
                    dashDataSourceFactory,
                    manifestString,
                    Uri.parse(stream.content),
                    playbackItem(stream.content).build(),
                )
            }
        }

        Log.w(TAG, "Progressive DASH manifest generation failed, using raw progressive (may throttle)")
        return createProgressiveSource(stream)
    }

    /**
     * Create DASH source from progressive audio URL
     */
    private fun createProgressiveDashSourceForAudio(
        stream: AudioStream,
        durationSeconds: Long,
    ): MediaSource {
        val itagItem = stream.itagItem

        if (itagItem != null && durationSeconds > 0) {
            Log.d(TAG, "Generating progressive DASH manifest for audio to avoid throttling")
            val manifestString = ManifestGenerator.generateProgressiveManifest(stream, itagItem, durationSeconds)

            if (manifestString != null) {
                return MediaSourceBuilder.buildDashSource(
                    dashDataSourceFactory,
                    manifestString,
                    Uri.parse(stream.content),
                    playbackItem(stream.content).build(),
                )
            }
        }

        return createProgressiveSourceForAudio(stream)
    }

    /**
     * Fallback: Create standard progressive video source (may be throttled!)
     */
    private fun createProgressiveSource(stream: VideoStream): MediaSource {
        Log.d(TAG, "Creating progressive video source for ${VideoCodecUtils.qualityHeightFromStream(stream)}p (WARNING: may be throttled)")
        val item =
            playbackItem(stream.content)
                .setMimeType(stream.format?.mimeType ?: androidx.media3.common.MimeTypes.VIDEO_MP4)
                .build()
        return androidx.media3.exoplayer.source.ProgressiveMediaSource
            .Factory(progressiveDataSourceFactory)
            .createMediaSource(item)
    }

    /**
     * Fallback: Create standard progressive audio source
     */
    private fun createProgressiveSourceForAudio(stream: AudioStream): MediaSource {
        Log.d(TAG, "Creating progressive audio source (WARNING: may be throttled)")
        val item =
            playbackItem(stream.content)
                .setMimeType(stream.format?.mimeType ?: androidx.media3.common.MimeTypes.AUDIO_AAC)
                .build()
        return androidx.media3.exoplayer.source.ProgressiveMediaSource
            .Factory(progressiveDataSourceFactory)
            .createMediaSource(item)
    }

    private fun playbackItem(uri: String): MediaItem.Builder =
        MediaItem
            .Builder()
            .setUri(uri)
            .setMediaId(mediaId)
            .setMediaMetadata(mediaMetadata)
}
