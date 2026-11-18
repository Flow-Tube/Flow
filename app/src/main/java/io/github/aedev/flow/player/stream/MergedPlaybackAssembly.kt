package io.github.aedev.flow.player.stream

import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.stream.model.AudioStream
import io.github.aedev.flow.player.stream.model.VideoStream

object MergedPlaybackAssembly {
    /**
     * Re-picks the streams for [quality] from a result that is already on screen, over the InnerTube
     * formats the screen kept from the load.
     */
    fun selectQualityStreams(
        innerTubeVideoFormats: List<PlayerResponse.StreamingData.Format>,
        innerTubeAudioFormats: List<PlayerResponse.StreamingData.Format>,
        quality: VideoQuality,
        preferredAudioLanguage: String,
        preferredCodecKey: String,
    ): Pair<VideoStream?, AudioStream?> {
        val innerTubeVideoStreams = InnerTubeStreamBridge.convertVideoFormats(innerTubeVideoFormats)
        val innerTubeAudioStreams = InnerTubeStreamBridge.convertAudioFormats(innerTubeAudioFormats)
        return ServicePlaybackStreamSelector.selectStreams(
            videoCandidates = innerTubeVideoStreams,
            audioCandidatesAll = innerTubeAudioStreams,
            preferredQuality = quality,
            preferredAudioLanguage = preferredAudioLanguage,
            preferredCodecKey = preferredCodecKey,
        )
    }
}
