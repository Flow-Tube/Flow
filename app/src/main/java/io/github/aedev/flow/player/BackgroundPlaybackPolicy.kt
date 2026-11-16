package io.github.aedev.flow.player

object BackgroundPlaybackPolicy {
    fun shouldEnterAutoPip(
        autoPipEnabled: Boolean,
        isVideoPlaying: Boolean,
        explicitBackgroundPlaybackActive: Boolean,
        soundOnlyBackground: Boolean,
    ): Boolean =
        autoPipEnabled &&
            isVideoPlaying &&
            !explicitBackgroundPlaybackActive &&
            !soundOnlyBackground

    /** A restored session keeps its video mini player, since nothing is playing yet (#1513). */
    fun shouldCollapseToAudioBar(
        minimizeToAudioBar: Boolean,
        isBackgroundPlaybackMode: Boolean,
        isRestoredSession: Boolean,
        hasVideo: Boolean,
    ): Boolean = minimizeToAudioBar && hasVideo && !isBackgroundPlaybackMode && !isRestoredSession

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
