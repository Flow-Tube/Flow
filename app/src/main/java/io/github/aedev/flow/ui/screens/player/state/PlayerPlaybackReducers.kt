package io.github.aedev.flow.ui.screens.player.state

import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.data.model.RelatedCollection
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.innertube.models.response.VideoChapter
import io.github.aedev.flow.player.PlayerChannelMetadataPolicy
import io.github.aedev.flow.player.error.VideoErrorMapper
import io.github.aedev.flow.player.stream.ResolvedPlayback
import io.github.aedev.flow.player.stream.StoryboardLevel
import io.github.aedev.flow.player.stream.VideoQualityOptions
import io.github.aedev.flow.player.stream.model.AudioStream
import io.github.aedev.flow.player.stream.model.VideoStream
import io.github.aedev.flow.ui.screens.player.SecondaryMetadata

/*
 * Every state transition the player screen makes while a load resolves, as pure functions over
 * VideoPlayerUiState.
 *
 * The ViewModel keeps the ordering: which side effect runs before which write, and which write is
 * skipped because the load that produced it is no longer current. Nothing here reads the clock, the
 * network, the player or the preferences — the values a transition needs are arguments, so the
 * fields each outcome writes can be asserted without a ViewModel.
 */

/**
 * A downloaded copy is about to play: the local path replaces whatever the load had reached, and a
 * screen opened with only the id borrows the identity the download row holds until the watch page
 * answers. Only blank fields are filled, so a card's own title and channel always win.
 */
internal fun VideoPlayerUiState.applyLocalCopyReady(
    videoId: String,
    step: ResolvedPlayback.LocalCopyReady,
): VideoPlayerUiState =
    copy(
        cachedVideo = cachedVideo.withDownloadIdentity(videoId, step.downloadedVideo),
        localFilePath = step.localFilePath,
        localFileVideoId = videoId,
        offlineSponsorBlockSegments = step.offlineSegments,
        error = null,
        errorHint = null,
        errorOffersSessionReset = false,
        isLoading = false,
        isUpcoming = false,
        upcomingReleaseTimeMs = null,
    )

internal fun Video?.withDownloadIdentity(
    videoId: String,
    downloaded: Video?,
): Video? {
    if (downloaded == null || downloaded.id != videoId) return this
    val base = this?.takeIf { it.id == videoId } ?: return this ?: downloaded
    return base.copy(
        title = base.title.ifBlank { downloaded.title },
        channelName = base.channelName.ifBlank { downloaded.channelName },
        channelId = base.channelId.ifBlank { downloaded.channelId },
        thumbnailUrl = base.thumbnailUrl.ifBlank { downloaded.thumbnailUrl },
        duration = base.duration.takeIf { it > 0 } ?: downloaded.duration,
    )
}

/**
 * The streams a load resolved: what plays, at what qualities, and the formats behind them.
 *
 * `chapters`, `offlineSponsorBlockSegments`, `localFilePath` and `localFileVideoId` are deliberately
 * left as the load left them — chapters arrive separately off the watch response, and the local-copy
 * fields belong to the download step.
 */
internal fun VideoPlayerUiState.applyVodStreams(
    cachedVideo: Video,
    isArchivedLivestream: Boolean,
    relatedVideos: List<Video>,
    videoStream: VideoStream?,
    audioStream: AudioStream?,
    availableQualities: List<VideoQuality>,
    savedPositionMs: Long,
    isAdaptiveMode: Boolean,
    autoplayEnabled: Boolean,
    innerTubeVideoFormats: List<PlayerResponse.StreamingData.Format>,
    innerTubeAudioFormats: List<PlayerResponse.StreamingData.Format>,
    streamSizes: Map<String, Long>,
    storyboard: List<StoryboardLevel>,
): VideoPlayerUiState =
    copy(
        cachedVideo = cachedVideo.keepingLateFieldsOf(this.cachedVideo),
        isArchivedLivestream = isArchivedLivestream,
        relatedVideos = relatedVideos,
        videoStream = videoStream,
        audioStream = audioStream,
        availableQualities = availableQualities,
        selectedQuality = VideoQualityOptions.qualityOf(videoStream),
        isLoading = false,
        error = null,
        errorHint = null,
        errorOffersSessionReset = false,
        savedPosition = savedPositionMs,
        isAdaptiveMode = isAdaptiveMode,
        autoplayEnabled = autoplayEnabled,
        isLive = false,
        isUpcoming = false,
        upcomingReleaseTimeMs = null,
        innerTubeVideoFormats = innerTubeVideoFormats,
        innerTubeAudioFormats = innerTubeAudioFormats,
        streamSizes = streamSizes,
        storyboard = storyboard,
    )

/** Chapters read from the watch response, as every chapter surface reads them. */
internal fun VideoPlayerUiState.applyChapters(chapters: List<VideoChapter>): VideoPlayerUiState = copy(chapters = chapters)

/** A live stream whose manifest only InnerTube produced. */
internal fun VideoPlayerUiState.applyLiveStreams(
    relatedVideos: List<Video>,
    hlsUrl: String?,
): VideoPlayerUiState =
    copy(
        relatedVideos = relatedVideos,
        isLoading = false,
        error = null,
        errorHint = null,
        errorOffersSessionReset = false,
        hlsUrl = hlsUrl,
        isLive = true,
        isUpcoming = false,
        upcomingReleaseTimeMs = null,
        innerTubeVideoFormats = emptyList(),
        innerTubeAudioFormats = emptyList(),
    )

/** The InnerTube VOD path threw: the related lane it had already gathered survives the error. */
internal fun VideoPlayerUiState.applyVodFailure(
    relatedVideos: List<Video>,
    videoError: VideoErrorMapper.VideoError,
): VideoPlayerUiState =
    copy(
        isLoading = false,
        relatedVideos = relatedVideos,
        error = videoError.message,
        errorHint = videoError.hint,
        errorOffersSessionReset = videoError.offersSessionReset,
    )

/** Nothing resolved. A null [relatedVideos] leaves the lane the screen is already showing alone. */
internal fun VideoPlayerUiState.applyPlaybackFailure(
    relatedVideos: List<Video>?,
    videoError: VideoErrorMapper.VideoError,
): VideoPlayerUiState =
    copy(
        isLoading = false,
        relatedVideos = relatedVideos ?: this.relatedVideos,
        error = videoError.message,
        errorHint = videoError.hint,
        errorOffersSessionReset = videoError.offersSessionReset,
    )

/**
 * The channel avatar and subscriber count.
 *
 * The avatar is chosen twice: once between what the fetch returned and what the load embedded, and
 * again between that winner and the avatar the cached video itself carries, so a cached avatar is
 * never replaced by a worse one. A result for a video the screen has moved on from changes nothing.
 */
internal fun VideoPlayerUiState.applyChannelMetadata(result: SecondaryMetadata.Channel): VideoPlayerUiState {
    val fetchedOrEmbedded =
        PlayerChannelMetadataPolicy.selectAvatarUrl(
            fetchedAvatarUrl = result.fetchedAvatarUrl,
            embeddedAvatarUrl = result.embeddedAvatarUrl,
            currentAvatarUrl = channelAvatarUrl,
        )
    val cached = cachedVideo
    if (cached?.id != result.videoId) return this

    val selectedAvatar =
        PlayerChannelMetadataPolicy.selectAvatarUrl(
            fetchedAvatarUrl = fetchedOrEmbedded,
            embeddedAvatarUrl = cached.channelThumbnailUrl,
            currentAvatarUrl = channelAvatarUrl,
        )
    val updatedCached =
        if (selectedAvatar != null) {
            cached.copy(
                channelThumbnailUrl = selectedAvatar,
                channelThumbnailUrls =
                    (listOf(selectedAvatar) + cached.channelThumbnailUrls)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .take(2),
            )
        } else {
            cached
        }

    return copy(
        cachedVideo = updatedCached,
        channelAvatarUrl = selectedAvatar,
        channelSubscriberCount = result.subscriberCount ?: channelSubscriberCount,
    )
}

/** The related lane, dropped when the screen has already moved to another video. */
internal fun VideoPlayerUiState.applyRelatedVideos(
    videoId: String,
    videos: List<Video>,
    upNextVideoId: String? = null,
    continuation: String? = null,
    collections: List<RelatedCollection> = emptyList(),
): VideoPlayerUiState =
    if (cachedVideo?.id != videoId) {
        this
    } else {
        copy(
            relatedVideos = videos,
            upNextVideoId = upNextVideoId,
            relatedContinuation = continuation,
            relatedAppend = RelatedAppend.IDLE,
            relatedCollections = collections,
        )
    }

/**
 * The richest [Video] the screen holds for [videoId], or null when it holds none.
 *
 * Engine signals are fed from this rather than from the title-only stub a card hands over, so a
 * like or a watch recorded here carries the tags, description and duration the load resolved.
 */
internal fun VideoPlayerUiState.richVideoFor(videoId: String): Video? = cachedVideo?.takeIf { it.id == videoId }

/** The live watch refresh: title, channel, counts and the avatar it resolved. */
internal fun VideoPlayerUiState.applyLiveWatchMetadata(result: SecondaryMetadata.LiveWatch): VideoPlayerUiState =
    copy(
        cachedVideo = result.video,
        channelAvatarUrl = result.channelAvatarUrl ?: channelAvatarUrl,
        channelSubscriberCount = result.subscriberCount ?: channelSubscriberCount,
    )

/** Counts, date and description from the watch page, folded over what the load resolved. */
internal fun VideoPlayerUiState.applyWatchInfo(video: Video): VideoPlayerUiState =
    copy(cachedVideo = video.keepingLateFieldsOf(cachedVideo))

/**
 * Both writers above replace the video with a copy taken before Return YouTube Dislike or the
 * category request answered, so a like count or category already known survives a copy of the
 * same video that has none.
 */
private fun Video.keepingLateFieldsOf(current: Video?): Video {
    if (current?.id != id) return this
    val keptLikes = likeCount <= 0L && current.likeCount > 0L
    val keptCategory = category == null && current.category != null
    if (!keptLikes && !keptCategory) return this
    return copy(
        likeCount = if (keptLikes) current.likeCount else likeCount,
        category = category ?: current.category,
    )
}

/** The quality the user picked, and the streams that choice resolved to. */
internal fun VideoPlayerUiState.applySelectedQuality(
    quality: VideoQuality,
    videoStream: VideoStream?,
    audioStream: AudioStream?,
): VideoPlayerUiState =
    copy(
        videoStream = videoStream,
        audioStream = audioStream,
        selectedQuality = VideoQualityOptions.qualityOf(videoStream),
        isAdaptiveMode = quality == VideoQuality.AUTO,
    )

/** The identity a load enriches when the screen holds nothing for the video yet. */
internal fun blankVideo(
    videoId: String,
    cached: Video?,
): Video =
    cached ?: Video(
        id = videoId,
        title = "",
        channelName = "",
        channelId = "",
        thumbnailUrl = "",
        duration = 0,
        viewCount = 0L,
        uploadDate = "",
    )
