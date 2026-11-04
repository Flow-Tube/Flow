package io.github.aedev.flow.ui.screens.player.state

import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.BackgroundPlaybackPolicy
import io.github.aedev.flow.player.state.EnhancedPlayerState

/*
 * The decisions a load makes before it does anything, and the states it starts from.
 *
 * Every function here answers one question from the screen state alone — should this load run at
 * all, what does the screen look like while it does, and what is there to arm when the player
 * needs streams it does not have. The ViewModel keeps the effects each answer leads to.
 */

/** Why a `loadVideoInfo` call stops before it starts, or null when it has work to do. */
internal enum class LoadSkip {
    /** The same video is already loaded, with streams and no error. */
    ALREADY_LOADED,

    /** The same video is already loading; a second request would duplicate the fetch. */
    ALREADY_LOADING,
}

/**
 * Whether a load for [videoId] is redundant.
 *
 * Both cases are keyed on the video the screen is already on: a forced refresh (a retry, an expiry
 * reload, a new play) always runs.
 */
internal fun VideoPlayerUiState.loadSkipReason(
    videoId: String,
    forceRefresh: Boolean,
): LoadSkip? =
    when {
        forceRefresh -> null
        cachedVideo?.id == videoId && !isLoading && error == null -> LoadSkip.ALREADY_LOADED
        isLoading && cachedVideo?.id == videoId -> LoadSkip.ALREADY_LOADING
        else -> null
    }

/**
 * The state a load runs behind: the previous video's streams, lane, engagement and live chat are
 * gone, and the only thing carried over is the channel avatar this video already came with. A
 * reload of the video on screen keeps its engagement, which nothing would re-emit (#1160).
 */
internal fun VideoPlayerUiState.beginLoadFor(videoId: String): VideoPlayerUiState =
    copy(
        isLoading = true,
        error = null,
        errorHint = null,
        errorOffersSessionReset = false,
        videoStream = null,
        audioStream = null,
        streamSizes = emptyMap(),
        savedPosition = null,
        relatedVideos = emptyList(),
        channelAvatarUrl =
            cachedVideo
                ?.takeIf { it.id == videoId }
                ?.channelThumbnailUrl
                ?.takeIf { it.isNotBlank() },
        channelSubscriberCount = null,
        streamClient = null,
        paidPromotion = null,
        dislikeCount = null,
        isSubscribed = isSubscribed && cachedVideo?.id == videoId,
        likeState = likeState.takeIf { cachedVideo?.id == videoId },
        hlsUrl = null,
        localFilePath = null,
        localFileVideoId = null,
        isUpcoming = false,
        upcomingReleaseTimeMs = null,
        isLive = false,
        isLiveChatAvailable = false,
        liveChatMessages = emptyList(),
        isLiveChatLoading = false,
    )

/** The countdown a load short-circuits to when the cached metadata already dated the premiere. */
internal fun VideoPlayerUiState.applyCachedUpcoming(releaseTimeMs: Long): VideoPlayerUiState =
    copy(
        isLoading = false,
        error = null,
        errorHint = null,
        errorOffersSessionReset = false,
        videoStream = null,
        audioStream = null,
        localFilePath = null,
        localFileVideoId = null,
        isUpcoming = true,
        upcomingReleaseTimeMs = releaseTimeMs,
    )

/** The state a fresh play starts from: the video's own metadata, over a player-sheet that is open. */
internal fun VideoPlayerUiState.startPlaybackOf(video: Video): VideoPlayerUiState =
    resetForVideo(video).copy(
        isBackgroundPlaybackMode = false,
        shouldDismissPlayer = false,
        channelAvatarUrl = video.channelThumbnailUrl.takeIf { it.isNotBlank() },
        channelSubscriberCount = null,
        streamClient = null,
        paidPromotion = null,
    )

/** The same, for a file on the device: nothing is loading because there is nothing to fetch. */
internal fun VideoPlayerUiState.startLocalPlaybackOf(
    video: Video,
    contentUri: String,
): VideoPlayerUiState =
    startPlaybackOf(video).copy(
        isLoading = false,
        localFilePath = contentUri,
        localFileVideoId = video.id,
        offlineSponsorBlockSegments = null,
    )

/** The device file's own channel and description, if it is still the one on screen. */
internal fun VideoPlayerUiState.withDeviceFileDetails(detailed: Video): VideoPlayerUiState =
    if (cachedVideo?.id == detailed.id) copy(cachedVideo = detailed) else this

/** Everything the screen keeps once the player is cleared: the two settings that are not a video. */
internal fun VideoPlayerUiState.clearedForNoVideo(): VideoPlayerUiState =
    VideoPlayerUiState(
        autoplayEnabled = autoplayEnabled,
        isAdaptiveMode = isAdaptiveMode,
    )

/**
 * Whether a play request for a video already playing in the background should reopen the sheet
 * rather than start over.
 */
internal fun VideoPlayerUiState.shouldReopenInsteadOfPlaying(
    videoId: String,
    playerState: EnhancedPlayerState,
    isMiniPlayerCollapsed: Boolean,
): Boolean =
    BackgroundPlaybackPolicy.shouldReopenCurrentVideo(
        requestedVideoId = videoId,
        currentVideoId = playerState.currentVideoId,
        isBackgroundPlaybackMode = isBackgroundPlaybackMode,
        isMiniPlayerCollapsed = isMiniPlayerCollapsed,
        hasReusablePlayback =
            playerState.isPrepared ||
                playerState.isPlaying ||
                playerState.playWhenReady ||
                playerState.isBuffering,
    )

/**
 * Whether a link to [videoId] should only reopen the sheet. A failed, restored or upcoming screen
 * for the same video still needs `playVideo`, or re-sharing it after a failure does nothing.
 */
internal fun VideoPlayerUiState.shouldExpandInsteadOfPlaying(videoId: String): Boolean =
    cachedVideo?.id == videoId && !isRestoredSession && error == null && !isUpcoming

/** What a late prepare has to arm from the screen state alone, when the player owns no media item. */
internal sealed interface LatePrepare {
    data class LocalFile(
        val localFilePath: String,
        val offlineSegments: List<SponsorBlockSegment>?,
        val savedPosition: Long?,
    ) : LatePrepare
}

/**
 * What the screen can arm for [videoId] right now, or null when it holds nothing playable.
 *
 * A downloaded or local copy wins while no stream metadata has landed; otherwise the merged streams
 * are re-pushed. Null covers both "no metadata at all" and "metadata with no playable source", which
 * the caller distinguishes only for its log line.
 */
internal fun VideoPlayerUiState.latePrepare(videoId: String): LatePrepare? {
    val localFilePath = localFilePath?.takeIf { localFileVideoId == null || localFileVideoId == videoId }
    if (localFilePath != null) {
        return LatePrepare.LocalFile(localFilePath, offlineSponsorBlockSegments, savedPosition)
    }

    return null
}

/** A screen that is loading, showing an error, or waiting on a restored session arms nothing. */
internal fun VideoPlayerUiState.blocksLatePrepare(): Boolean = isLoading || error != null || isRestoredSession

/** Whether the screen holds [videoId] at all, under any of the three identities it can be under. */
internal fun VideoPlayerUiState.holdsVideo(videoId: String): Boolean = cachedVideo?.id == videoId || localFileVideoId == videoId

/**
 * Whether a downloaded copy of [videoId] should still load its watch page: the title, counts,
 * related lane, chapters and channel all come from there, and the file carries none of them.
 */
internal fun shouldLoadOnlineMetadataForLocalCopy(
    videoId: String,
    isOnline: Boolean,
): Boolean = isOnline && !LocalMediaIds.isLocal(videoId)
