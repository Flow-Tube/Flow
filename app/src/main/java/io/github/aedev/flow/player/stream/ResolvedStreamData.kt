package io.github.aedev.flow.player.stream

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.stream.model.AudioStream
import io.github.aedev.flow.player.stream.model.StreamType
import io.github.aedev.flow.player.stream.model.VideoStream

/**
 * Everything resolving one video's streams produced: enough to start playback, build a preloaded
 * media source, or fill in the player state, without going back to the network.
 */
internal data class ResolvedStreamData(
    val enrichedVideo: Video,
    val videoStream: VideoStream?,
    val audioStream: AudioStream?,
    val videoStreams: List<VideoStream>,
    val audioStreams: List<AudioStream>,
    val subtitles: List<ResolvedCaption>,
    val durationSeconds: Long,
    val dashManifestUrl: String?,
    val streamType: StreamType?,
    val relatedVideos: List<Video>,
    val preferredCodec: String,
    val itVideoFormats: List<PlayerResponse.StreamingData.Format>,
    val itAudioFormats: List<PlayerResponse.StreamingData.Format>,
    val extraction: InnerTubeVideoStreamExtractor.VideoExtractionResult? = null,
)
