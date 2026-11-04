package io.github.aedev.flow.player

object BackgroundPlaybackPolicy {
    fun shouldEnterAutoPip(
        autoPipEnabled: Boolean,
        isVideoPlaying: Boolean,
        explicitBackgroundPlaybackActive: Boolean,
    ): Boolean =
        autoPipEnabled &&
            isVideoPlaying &&
            !explicitBackgroundPlaybackActive

    fun shouldKeepPlaybackInBackground(
        backgroundPlaybackPreferenceEnabled: Boolean,
        explicitBackgroundPlaybackActive: Boolean,
        hasActiveVideo: Boolean,
    ): Boolean =
        hasActiveVideo &&
            (backgroundPlaybackPreferenceEnabled || explicitBackgroundPlaybackActive)

    /** A video sent to the background on purpose stays when the activity goes, even paused, so the notification can resume it (#1699). */
    fun shouldKeepPausedVideo(
        explicitBackgroundPlaybackActive: Boolean,
        hasLoadedVideo: Boolean,
    ): Boolean = explicitBackgroundPlaybackActive && hasLoadedVideo

    fun shouldReopenCurrentVideo(
        requestedVideoId: String,
        currentVideoId: String?,
        isBackgroundPlaybackMode: Boolean,
        isMiniPlayerCollapsed: Boolean,
        hasReusablePlayback: Boolean,
    ): Boolean =
        (isBackgroundPlaybackMode || isMiniPlayerCollapsed) &&
            hasReusablePlayback &&
            requestedVideoId == currentVideoId
}
