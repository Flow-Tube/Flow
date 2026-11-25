package io.github.aedev.flow.player.stream

import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.data.model.Video

/** Everything [PlaybackLoadResolver] needs that the player screen owns. */
data class PlaybackResolutionRequest(
    val videoId: String,
    val isWifi: Boolean,
    val escalateToSabr: Boolean,
    val resumePositionOverrideMs: Long?,
    val allowShorts: Boolean,
    /** Creators the viewer has blocked; their videos never enter the related list. */
    val blockedChannelIds: Set<String> = emptySet(),
    /** A retry: an extraction kept from an earlier open is dropped instead of played again. */
    val freshStreams: Boolean = false,
)

/** Why a resolution produced nothing to play, and therefore which error string the screen shows. */
enum class PlaybackFailure {
    /** Both extraction stacks came back empty. */
    EXTRACTION,

    /** The whole resolution ran past its budget. */
    TIMEOUT,

    /** An exception nobody in the pipeline expected. */
    UNEXPECTED,
}

/**
 * One thing the player screen can act on, handed over in the order the pipeline produces it.
 *
 * A single resolution emits one step. The screen owns every `_uiState` write and every hand-off to
 * the player manager; this type carries only the values those need.
 */
sealed interface ResolvedPlayback {
    /** A downloaded copy of the video exists and should start playing now. */
    data class LocalCopyReady(
        val localFilePath: String,
        val offlineSegments: List<SponsorBlockSegment>?,
        /** The download was saved before SponsorBlock data was, so it is worth fetching once. */
        val needsSponsorBlockBackfill: Boolean = false,
        /** What the download row knows about the video, for a screen opened with only its id. */
        val downloadedVideo: Video? = null,
    ) : ResolvedPlayback

    /** A live stream, from the manifest InnerTube produced. */
    data class Live(
        val result: InnerTubeVideoStreamExtractor.VideoExtractionResult,
        val relatedVideos: List<Video>,
    ) : ResolvedPlayback

    /** A VOD, from the streams InnerTube produced. */
    data class VodFromInnerTube(
        val result: InnerTubeVideoStreamExtractor.VideoExtractionResult,
        val relatedVideos: List<Video>,
        val preferredQuality: VideoQuality,
        val preferredAudioLanguage: String,
        val preferredCodecKey: String,
        val preferredSubtitleLanguage: String,
        val resumePositionOverrideMs: Long?,
    ) : ResolvedPlayback

    /** The video has not premiered yet, so the screen shows a countdown rather than an error. */
    data class Upcoming(
        val relatedVideos: List<Video>,
        val releaseTimeMs: Long?,
        val details: UpcomingDetails? = null,
    ) : ResolvedPlayback

    /** Nothing playable, and not a premiere. A null [relatedVideos] leaves the current list alone. */
    data class Failed(
        val failure: PlaybackFailure,
        val cause: Throwable?,
        val relatedVideos: List<Video>?,
    ) : ResolvedPlayback
}
