package io.github.aedev.flow.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackgroundPlaybackPolicyTest {
    @Test
    fun `auto pip enters for regular video playback`() {
        val shouldEnter =
            BackgroundPlaybackPolicy.shouldEnterAutoPip(
                autoPipEnabled = true,
                isVideoPlaying = true,
                explicitBackgroundPlaybackActive = false,
                soundOnlyBackground = false,
            )

        assertThat(shouldEnter).isTrue()
    }

    @Test
    fun `auto pip does not enter during explicit background playback`() {
        val shouldEnter =
            BackgroundPlaybackPolicy.shouldEnterAutoPip(
                autoPipEnabled = true,
                isVideoPlaying = true,
                explicitBackgroundPlaybackActive = true,
                soundOnlyBackground = false,
            )

        assertThat(shouldEnter).isFalse()
    }

    @Test
    fun `disabled auto pip does not enter for regular playback`() {
        val shouldEnter =
            BackgroundPlaybackPolicy.shouldEnterAutoPip(
                autoPipEnabled = false,
                isVideoPlaying = true,
                explicitBackgroundPlaybackActive = false,
                soundOnlyBackground = false,
            )

        assertThat(shouldEnter).isFalse()
    }

    @Test
    fun `manual background playback survives activity destruction`() {
        val shouldKeepPlayback =
            BackgroundPlaybackPolicy.shouldKeepPlaybackInBackground(
                backgroundPlaybackPreferenceEnabled = false,
                explicitBackgroundPlaybackActive = true,
                hasActiveVideo = true,
            )

        assertThat(shouldKeepPlayback).isTrue()
    }

    @Test
    fun `ordinary foreground playback stops when activity is destroyed`() {
        val shouldKeepPlayback =
            BackgroundPlaybackPolicy.shouldKeepPlaybackInBackground(
                backgroundPlaybackPreferenceEnabled = false,
                explicitBackgroundPlaybackActive = false,
                hasActiveVideo = true,
            )

        assertThat(shouldKeepPlayback).isFalse()
    }

    @Test
    fun `background preference does not retain inactive playback`() {
        val shouldKeepPlayback =
            BackgroundPlaybackPolicy.shouldKeepPlaybackInBackground(
                backgroundPlaybackPreferenceEnabled = true,
                explicitBackgroundPlaybackActive = false,
                hasActiveVideo = false,
            )

        assertThat(shouldKeepPlayback).isFalse()
    }

    @Test
    fun `selecting current background video reopens it`() {
        val shouldReopen =
            BackgroundPlaybackPolicy.shouldReopenCurrentVideo(
                requestedVideoId = "video-id",
                currentVideoId = "video-id",
                isBackgroundPlaybackMode = true,
                isMiniPlayerCollapsed = false,
                hasReusablePlayback = true,
            )

        assertThat(shouldReopen).isTrue()
    }

    @Test
    fun `selecting current video in mini player expands existing playback`() {
        val shouldReopen =
            BackgroundPlaybackPolicy.shouldReopenCurrentVideo(
                requestedVideoId = "video-id",
                currentVideoId = "video-id",
                isBackgroundPlaybackMode = false,
                isMiniPlayerCollapsed = true,
                hasReusablePlayback = true,
            )

        assertThat(shouldReopen).isTrue()
    }

    @Test
    fun `selecting a different background video starts a new playback`() {
        val shouldReopen =
            BackgroundPlaybackPolicy.shouldReopenCurrentVideo(
                requestedVideoId = "new-video-id",
                currentVideoId = "current-video-id",
                isBackgroundPlaybackMode = true,
                isMiniPlayerCollapsed = false,
                hasReusablePlayback = true,
            )

        assertThat(shouldReopen).isFalse()
    }

    @Test
    fun `selecting same video while full player is open keeps normal selection behavior`() {
        val shouldReopen =
            BackgroundPlaybackPolicy.shouldReopenCurrentVideo(
                requestedVideoId = "video-id",
                currentVideoId = "video-id",
                isBackgroundPlaybackMode = false,
                isMiniPlayerCollapsed = false,
                hasReusablePlayback = true,
            )

        assertThat(shouldReopen).isFalse()
    }

    @Test
    fun `minimized placeholder without loaded playback still loads video`() {
        val shouldReopen =
            BackgroundPlaybackPolicy.shouldReopenCurrentVideo(
                requestedVideoId = "video-id",
                currentVideoId = "video-id",
                isBackgroundPlaybackMode = false,
                isMiniPlayerCollapsed = true,
                hasReusablePlayback = false,
            )

        assertThat(shouldReopen).isFalse()
    }

    @Test
    fun `a paused video stays only after listening in background was asked for`() {
        assertThat(BackgroundPlaybackPolicy.shouldKeepPausedVideo(explicitBackgroundPlaybackActive = true, hasLoadedVideo = true)).isTrue()
        assertThat(
            BackgroundPlaybackPolicy.shouldKeepPausedVideo(explicitBackgroundPlaybackActive = false, hasLoadedVideo = true),
        ).isFalse()
        assertThat(
            BackgroundPlaybackPolicy.shouldKeepPausedVideo(explicitBackgroundPlaybackActive = true, hasLoadedVideo = false),
        ).isFalse()
    }

    @Test
    fun `auto pip does not enter from the sound only bar`() {
        val shouldEnter =
            BackgroundPlaybackPolicy.shouldEnterAutoPip(
                autoPipEnabled = true,
                isVideoPlaying = true,
                explicitBackgroundPlaybackActive = false,
                soundOnlyBackground = true,
            )

        assertThat(shouldEnter).isFalse()
    }

    @Test
    fun `a collapse goes to the audio bar only with the setting on`() {
        assertThat(collapseToBar(minimizeToAudioBar = true)).isTrue()
        assertThat(collapseToBar(minimizeToAudioBar = false)).isFalse()
    }

    @Test
    fun `a restored session keeps its video mini player`() {
        assertThat(collapseToBar(isRestoredSession = true)).isFalse()
    }

    @Test
    fun `a video already in the background is not sent there again`() {
        assertThat(collapseToBar(isBackgroundPlaybackMode = true)).isFalse()
    }

    @Test
    fun `nothing goes to the audio bar without a video`() {
        assertThat(collapseToBar(hasVideo = false)).isFalse()
    }

    @Test
    fun `the audio bar alone does not keep a video playing after the app is left`() {
        val shouldKeepPlayback =
            BackgroundPlaybackPolicy.shouldKeepPlaybackInBackground(
                backgroundPlaybackPreferenceEnabled = false,
                explicitBackgroundPlaybackActive = false,
                hasActiveVideo = true,
            )

        assertThat(shouldKeepPlayback).isFalse()
        assertThat(BackgroundPlaybackPolicy.shouldKeepPausedVideo(explicitBackgroundPlaybackActive = false, hasLoadedVideo = true))
            .isFalse()
    }

    private fun collapseToBar(
        minimizeToAudioBar: Boolean = true,
        isBackgroundPlaybackMode: Boolean = false,
        isRestoredSession: Boolean = false,
        hasVideo: Boolean = true,
    ) = BackgroundPlaybackPolicy.shouldCollapseToAudioBar(minimizeToAudioBar, isBackgroundPlaybackMode, isRestoredSession, hasVideo)
}
