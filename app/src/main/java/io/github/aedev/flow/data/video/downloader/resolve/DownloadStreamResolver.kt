package io.github.aedev.flow.data.video.downloader.resolve

import io.github.aedev.flow.data.local.MusicAudioQuality
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.VideoCodec
import io.github.aedev.flow.data.video.DefaultDownloadSelection
import io.github.aedev.flow.data.video.DownloadStreamPolicy
import io.github.aedev.flow.data.video.downloader.request.DownloadRequest
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import io.github.aedev.flow.player.stream.CaptionFormat
import io.github.aedev.flow.player.stream.CaptionTrackResolver
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor.VideoExtractionResult
import io.github.aedev.flow.player.stream.ResolvedCaption
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** The streams one run of a download fetches. */
data class ResolvedStreams(
    val video: Format?,
    val audio: Format,
    val durationMs: Long,
    /** The video's caption tracks as WebVTT, from the same response, for the files kept beside it. */
    val captions: List<ResolvedCaption> = emptyList(),
)

sealed interface ResolveOutcome {
    data class Resolved(
        val streams: ResolvedStreams,
    ) : ResolveOutcome

    /** Nothing downloadable: the video is gone, blocked, or offers no stream this request can use. */
    data object Unavailable : ResolveOutcome

    /** Only Opus audio exists, which the MP4 writer does not take. */
    data object NoCompatibleAudio : ResolveOutcome
}

/**
 * Turns a [DownloadRequest] into streams with fresh URLs, through the same InnerTube client ladder
 * playback uses (coalesced with it, so a download of the video on screen costs no second request).
 */
@Singleton
class DownloadStreamResolver
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
    ) {
        suspend fun resolve(
            request: DownloadRequest,
            avoidItags: Set<Int> = emptySet(),
        ): ResolveOutcome {
            val result = InnerTubeVideoStreamExtractor.extract(request.videoId) ?: return ResolveOutcome.Unavailable
            if (result.isLive) return ResolveOutcome.Unavailable
            val outcome = select(request, result, avoidItags, defaults())
            if (outcome !is ResolveOutcome.Resolved || request.wantsAudioOnly) return outcome
            return ResolveOutcome.Resolved(outcome.streams.copy(captions = captionsOf(request, result)))
        }

        private suspend fun captionsOf(
            request: DownloadRequest,
            result: VideoExtractionResult,
        ): List<ResolvedCaption> {
            val translateTo =
                request.subtitle?.takeIf { it.translated }?.languageTag
                    ?: preferences.preferredSubtitleLanguage.first()
            return CaptionTrackResolver.resolve(result.playerResponse, format = CaptionFormat.VTT, translateTo = translateTo)
        }

        private suspend fun defaults(): SelectionDefaults =
            SelectionDefaults(
                height = preferences.defaultDownloadQuality.first().height,
                codec =
                    preferences.defaultDownloadCodec
                        .first()
                        .takeIf { it != VideoCodec.AUTO }
                        ?.codecKey,
                language = preferences.preferredAudioLanguage.first(),
                musicQuality = preferences.musicDownloadQuality.first(),
            )

        internal data class SelectionDefaults(
            val height: Int,
            val codec: String?,
            val language: String?,
            val musicQuality: MusicAudioQuality = MusicAudioQuality.HIGH,
        )

        internal companion object {
            /** The pure choice, separate from extraction so every rule is unit tested. */
            fun select(
                request: DownloadRequest,
                result: VideoExtractionResult,
                avoidItags: Set<Int>,
                defaults: SelectionDefaults,
            ): ResolveOutcome {
                val audioFormats =
                    DownloadStreamPolicy
                        .buildDownloadAudioFormats(result.audioFormats)
                        .filterNot { it.itag in avoidItags }
                val language = request.audioLanguage ?: defaults.language
                val durationMs = result.durationMs()
                // A song is only its audio, so it is the one download the song quality setting shapes.
                val quality = if (request.wantsAudioOnly) defaults.musicQuality else MusicAudioQuality.HIGH

                // Every dub of a video shares one itag (140 for AAC), so a picked track is found by its
                // track id first, or the itag alone lands on whichever dub sorts first (#1444).
                val trackFormats =
                    request.audioTrackId
                        ?.let { id -> audioFormats.filter { it.audioTrack?.id == id } }
                        .orEmpty()
                val itagPool = if (request.audioTrackId != null) trackFormats else audioFormats
                val audio =
                    request.audioItag
                        ?.let { itag -> itagPool.firstOrNull { it.itag == itag } }
                        ?.takeIf { DownloadStreamPolicy.isAacFormat(it) }
                        ?: DownloadStreamPolicy.pickAacAudio(trackFormats, language, quality)
                        ?: DownloadStreamPolicy.pickAacAudio(audioFormats, language, quality)
                        ?: return if (result.audioFormats.any { !it.url.isNullOrBlank() }) {
                            ResolveOutcome.NoCompatibleAudio
                        } else {
                            ResolveOutcome.Unavailable
                        }

                if (request.wantsAudioOnly) {
                    return ResolveOutcome.Resolved(ResolvedStreams(video = null, audio = audio, durationMs = durationMs))
                }

                val videoFormats =
                    DownloadStreamPolicy
                        .buildDownloadVideoFormats(result.videoFormats)
                        .filterNot { it.itag in avoidItags }
                request.videoItag
                    ?.let { itag -> videoFormats.firstOrNull { it.itag == itag } }
                    ?.let { return ResolveOutcome.Resolved(ResolvedStreams(it, audio, durationMs)) }

                val height =
                    DefaultDownloadSelection.pickHeight(
                        videoFormats.map(DownloadStreamPolicy::videoHeight),
                        request.targetHeight ?: defaults.height,
                    ) ?: return ResolveOutcome.Unavailable
                val atHeight = videoFormats.filter { DownloadStreamPolicy.videoHeight(it) == height }
                val codec =
                    DefaultDownloadSelection
                        .rankCodecs(atHeight.map(DownloadStreamPolicy::videoCodecKey), request.videoCodec ?: defaults.codec)
                        .firstOrNull() ?: return ResolveOutcome.Unavailable
                val video = atHeight.first { DownloadStreamPolicy.videoCodecKey(it) == codec }
                return ResolveOutcome.Resolved(ResolvedStreams(video, audio, durationMs))
            }

            private fun VideoExtractionResult.durationMs(): Long =
                (audioFormats + videoFormats).firstNotNullOfOrNull { it.approxDurationMs?.toLongOrNull() }
                    ?: playerResponse.videoDetails
                        ?.lengthSeconds
                        ?.toLongOrNull()
                        ?.times(1000L) ?: 0L
        }
    }
