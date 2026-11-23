package io.github.aedev.flow.data.video

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.video.downloader.resolve.RecentExtractions
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.state.SubtitleOption
import io.github.aedev.flow.player.stream.CaptionTrackResolver
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor
import io.github.aedev.flow.player.stream.StreamProcessor
import io.github.aedev.flow.player.stream.StreamSizeEstimator
import io.github.aedev.flow.player.stream.durationMs
import io.github.aedev.flow.player.stream.playableAudioFormats
import io.github.aedev.flow.player.stream.playableVideoFormats
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val EXTRACT_TIMEOUT_MS = 8_000L

/** Everything the download dialogs need to offer one video: its formats and each choice's size. */
data class VideoDownloadOptions(
    val video: Video,
    val videoFormats: List<PlayerResponse.StreamingData.Format>,
    val audioFormats: List<PlayerResponse.StreamingData.Format>,
    val streamSizes: Map<String, Long>,
    val subtitles: List<SubtitleOption> = emptyList(),
)

/**
 * Loads the formats for a video that is not playing, so a card can open the same download dialog
 * the player and Shorts open, with the same quality, codec and style settings applied.
 */
class VideoDownloadOptionsLoader
    @Inject
    constructor(
        private val recent: RecentExtractions,
    ) {
        suspend fun load(video: Video): VideoDownloadOptions? =
            withContext(PerformanceDispatcher.networkIO) {
                val result =
                    withTimeoutOrNull(EXTRACT_TIMEOUT_MS) {
                        runCatching { InnerTubeVideoStreamExtractor.extract(video.id) }.getOrNull()
                    } ?: return@withContext null
                recent.remember(video.id, result)
                val videoFormats = result.playableVideoFormats()
                if (videoFormats.isEmpty()) return@withContext null
                val audioFormats = result.playableAudioFormats()
                VideoDownloadOptions(
                    video = video,
                    videoFormats = videoFormats,
                    audioFormats = audioFormats,
                    streamSizes = StreamSizeEstimator.fromInnerTubeFormats(videoFormats, audioFormats, result.durationMs() ?: 0L),
                    subtitles =
                        StreamProcessor.toSubtitleOptions(
                            StreamProcessor.processCaptions(CaptionTrackResolver.resolve(result.playerResponse)),
                        ),
                )
            }
    }
