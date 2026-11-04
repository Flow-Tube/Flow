package io.github.aedev.flow.ui.screens.player

import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.PlayerChannelMetadataPolicy
import io.github.aedev.flow.player.stream.CaptionTrackResolver
import io.github.aedev.flow.player.stream.InnerTubeStreamBridge
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor
import io.github.aedev.flow.player.stream.ResolvedCaption
import io.github.aedev.flow.player.stream.ResolvedPlayback
import io.github.aedev.flow.player.stream.ServicePlaybackStreamSelector
import io.github.aedev.flow.player.stream.StreamProcessor
import io.github.aedev.flow.player.stream.StreamSizeEstimator
import io.github.aedev.flow.player.stream.VideoQualityOptions
import io.github.aedev.flow.ui.screens.player.state.blankVideo
import io.github.aedev.flow.utils.OriginalTextPolicy
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Turns one InnerTube extraction into the values the player screen plays from: the identity the
 * session and the media notification are armed with, the Media3-shaped streams and captions, and the
 * quality and audio track the load's preferences select.
 *
 * Pure — no network, no player and no screen state, and its one preference, [originalText], is handed
 * in. The ViewModel owns what happens around the result: it applies the state reducers, reads the
 * resume position, and hands the streams to [PlaybackPreparer] in the order playback needs them.
 */
internal class PlaybackStreamPreparer(
    private val originalText: () -> Boolean = { false },
) {
    /** The identity every InnerTube result carries, merged over what the screen already knew. */
    data class StreamIdentity(
        val enrichedVideo: Video,
        val title: String,
        val channel: String,
        val thumbnail: String,
        val channelId: String,
        val embeddedAvatarUrls: List<String>,
    )

    data class VodStreams(
        val identity: StreamIdentity,
        val durationSeconds: Long,
        val videoStreams: List<VideoStream>,
        val audioStreams: List<AudioStream>,
        val availableQualities: List<VideoQuality>,
        val videoStream: VideoStream?,
        val audioStream: AudioStream?,
        val subtitles: List<ResolvedCaption>,
        val isAdaptiveMode: Boolean,
        val streamSizes: Map<String, Long>,
    )

    data class LiveStreams(
        val identity: StreamIdentity,
        val hlsUrl: String?,
        val dashManifestUrl: String?,
        val subtitles: List<ResolvedCaption>,
    )

    fun assembleVod(
        videoId: String,
        cached: Video?,
        step: ResolvedPlayback.VodFromInnerTube,
    ): VodStreams {
        val result = step.result
        val details = result.playerResponse.videoDetails
        val durationSeconds =
            details?.lengthSeconds?.toLongOrNull()?.takeIf { it > 0 }
                ?: cached?.duration?.toLong()?.takeIf { it > 0 }
                ?: 0L
        val videoStreams = InnerTubeStreamBridge.convertVideoFormats(result.videoFormats)
        val audioStreams = InnerTubeStreamBridge.convertAudioFormats(result.audioFormats)
        val selected =
            ServicePlaybackStreamSelector.selectStreams(
                videoCandidates = videoStreams,
                audioCandidatesAll = audioStreams,
                preferredQuality = step.preferredQuality,
                preferredAudioLanguage = step.preferredAudioLanguage,
                preferredCodecKey = step.preferredCodecKey,
            )
        return VodStreams(
            identity = identity(videoId, cached, result, fallbackTitle = "", durationSeconds = durationSeconds),
            durationSeconds = durationSeconds,
            videoStreams = videoStreams,
            audioStreams = audioStreams,
            availableQualities = VideoQualityOptions.availableQualities(videoStreams),
            videoStream = selected.first,
            audioStream = selected.second,
            subtitles = captionStreams(result, step.preferredSubtitleLanguage),
            isAdaptiveMode = step.preferredQuality == VideoQuality.AUTO,
            streamSizes =
                StreamSizeEstimator.fromInnerTubeFormats(
                    result.videoFormats,
                    result.audioFormats,
                    durationSeconds * 1000L,
                ),
        )
    }

    fun assembleLive(
        videoId: String,
        cached: Video?,
        result: InnerTubeVideoStreamExtractor.VideoExtractionResult,
    ): LiveStreams =
        LiveStreams(
            identity = identity(videoId, cached, result, fallbackTitle = "Live", durationSeconds = 0L),
            hlsUrl = result.liveHlsUrl,
            dashManifestUrl = result.liveDashUrl,
            subtitles = captionStreams(result, CaptionTrackResolver.NO_PREFERRED_LANGUAGE),
        )

    private fun identity(
        videoId: String,
        cached: Video?,
        result: InnerTubeVideoStreamExtractor.VideoExtractionResult,
        fallbackTitle: String,
        durationSeconds: Long,
    ): StreamIdentity {
        val details = result.playerResponse.videoDetails
        val preferOriginal = originalText()
        val title = OriginalTextPolicy.streamTitle(details?.title, cached?.title, preferOriginal) ?: fallbackTitle
        val (channelId, channel) =
            PlayerChannelMetadataPolicy.streamChannel(
                cardId = cached?.channelId,
                cardName = cached?.channelName,
                playerId = details?.channelId,
                playerName = details?.author,
            )
        val thumbnail =
            details
                ?.thumbnail
                ?.thumbnails
                ?.maxByOrNull { it.height ?: 0 }
                ?.url
                ?: cached?.thumbnailUrl ?: ThumbnailUrlResolver.normalizeVideoThumbnail(videoId, null)
        val known = blankVideo(videoId, cached)
        return StreamIdentity(
            enrichedVideo =
                known.copy(
                    title = title,
                    description = OriginalTextPolicy.streamDescription(details?.shortDescription, known.description, preferOriginal),
                    channelName = channel,
                    channelId = channelId,
                    thumbnailUrl = thumbnail,
                    duration = durationSeconds.toInt(),
                    tags = keywordTags(result, cached),
                    // Only when the winning client returned a microformat: WEB/MWEB do, VISIONOS does not.
                    category = microformatCategory(result) ?: cached?.category,
                ),
            title = title,
            channel = channel,
            thumbnail = thumbnail,
            channelId = channelId,
            embeddedAvatarUrls = listOfNotNull(cached?.channelThumbnailUrl) + cached?.channelThumbnailUrls.orEmpty(),
        )
    }

    private fun keywordTags(
        result: InnerTubeVideoStreamExtractor.VideoExtractionResult,
        cached: Video?,
    ): List<String> =
        result.playerResponse.videoDetails
            ?.keywords
            .orEmpty()
            .distinct()
            .ifEmpty { cached?.tags.orEmpty() }

    private fun microformatCategory(result: InnerTubeVideoStreamExtractor.VideoExtractionResult): String? =
        result.playerResponse.microformat
            ?.playerMicroformatRenderer
            ?.category
            ?.takeIf { it.isNotBlank() }

    private fun captionStreams(
        result: InnerTubeVideoStreamExtractor.VideoExtractionResult,
        translateTo: String,
    ): List<ResolvedCaption> =
        StreamProcessor.processCaptions(
            CaptionTrackResolver.resolve(result.playerResponse, translateTo = translateTo),
        )
}
