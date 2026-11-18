package io.github.aedev.flow.player.stream

import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.player.quality.QualityManager
import io.github.aedev.flow.player.stream.model.AudioStream
import io.github.aedev.flow.player.stream.model.VideoStream
import kotlin.math.abs

/**
 * Picks the video and audio stream for playback started from the service layer — the autoplay and
 * preload path, which resolves streams without the player UI being involved.
 *
 */
object ServicePlaybackStreamSelector {
    fun selectStreams(
        videoCandidates: List<VideoStream>,
        audioCandidatesAll: List<AudioStream>,
        preferredQuality: VideoQuality,
        preferredAudioLanguage: String,
        preferredCodecKey: String = "auto",
    ): Pair<VideoStream?, AudioStream?> {
        val audioStream =
            AudioStreamSelector.selectPreferredAudioStream(
                streams = audioCandidatesAll.distinctBy { it.url ?: it.content },
                preferredAudioLanguage = preferredAudioLanguage,
            )

        val videoStreams =
            videoCandidates
                .filter {
                    val mime = it.format?.mimeType
                    mime?.contains("mp4", ignoreCase = true) == true ||
                        mime?.contains("webm", ignoreCase = true) == true
                }

        val selectedVideoStream =
            when (preferredQuality) {
                // AUTO leaves the choice to adaptive track selection at playback time.
                VideoQuality.AUTO -> {
                    null
                }

                else -> {
                    videoStreams
                        .sortedWith(
                            compareBy<VideoStream> {
                                abs(
                                    QualityManager.normalizeQualityHeight(VideoCodecUtils.qualityHeightFromStream(it)) -
                                        preferredQuality.height,
                                )
                            }.thenBy { VideoCodecUtils.codecRankWithPreference(it, preferredCodecKey) }
                                .thenByDescending { it.bitrate },
                        ).firstOrNull()
                }
            }
        val videoStream =
            if (audioStream == null && selectedVideoStream == null) {
                // Nothing to pair a video-only stream with, so prefer a muxed one that carries audio.
                videoStreams
                    .sortedWith(
                        compareBy<VideoStream> { if (it.isVideoOnly) 1 else 0 }
                            .thenByDescending { QualityManager.normalizeQualityHeight(VideoCodecUtils.qualityHeightFromStream(it)) }
                            .thenBy { VideoCodecUtils.codecRankWithPreference(it, preferredCodecKey) }
                            .thenByDescending { it.bitrate },
                    ).firstOrNull()
            } else {
                selectedVideoStream
            }
        return videoStream to audioStream
    }
}
