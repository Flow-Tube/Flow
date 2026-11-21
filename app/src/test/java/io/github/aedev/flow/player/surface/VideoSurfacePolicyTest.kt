package io.github.aedev.flow.player.surface

import android.os.Build
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VideoSurfacePolicyTest {
    @Test
    fun `surface view restore waits for a valid holder`() {
        assertThat(
            VideoSurfacePolicy.canRestoreVideoOutput(
                outputIsSurfaceView = true,
                isDisplayInteractive = true,
                isSurfaceValid = false,
            ),
        ).isFalse()
    }

    @Test
    fun `surface view restores once its holder is valid`() {
        assertThat(
            VideoSurfacePolicy.canRestoreVideoOutput(
                outputIsSurfaceView = true,
                isDisplayInteractive = true,
                isSurfaceValid = true,
            ),
        ).isTrue()
    }

    @Test
    fun `texture view restore does not require a surface holder`() {
        assertThat(
            VideoSurfacePolicy.canRestoreVideoOutput(
                outputIsSurfaceView = false,
                isDisplayInteractive = true,
                isSurfaceValid = false,
            ),
        ).isTrue()
    }

    @Test
    fun `an Android 9 TV surface view waits for a valid holder too`() {
        val surfaceView = VideoSurfacePolicy.usesSurfaceView(Build.VERSION_CODES.P, tvInterface = true)

        assertThat(surfaceView).isTrue()
        assertThat(
            VideoSurfacePolicy.canRestoreVideoOutput(
                outputIsSurfaceView = surfaceView,
                isDisplayInteractive = true,
                isSurfaceValid = false,
            ),
        ).isFalse()
    }

    @Test
    fun `phones keep a texture view below Android 14 and a surface view from it`() {
        assertThat(VideoSurfacePolicy.usesSurfaceView(Build.VERSION_CODES.TIRAMISU)).isFalse()
        assertThat(VideoSurfacePolicy.usesSurfaceView(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)).isTrue()
    }

    @Test
    fun `video output stays disabled while display is not interactive`() {
        assertThat(
            VideoSurfacePolicy.canRestoreVideoOutput(
                outputIsSurfaceView = true,
                isDisplayInteractive = false,
                isSurfaceValid = true,
            ),
        ).isFalse()
    }

    @Test
    fun `paused ready playback resyncs after a surface reattach`() {
        assertThat(
            VideoSurfacePolicy.shouldResyncOnSurfaceReattach(
                playWhenReady = false,
                isLive = false,
                playbackState = Player.STATE_READY,
            ),
        ).isTrue()
    }

    @Test
    fun `paused buffering playback resyncs after a surface reattach`() {
        assertThat(
            VideoSurfacePolicy.shouldResyncOnSurfaceReattach(
                playWhenReady = false,
                isLive = false,
                playbackState = Player.STATE_BUFFERING,
            ),
        ).isTrue()
    }

    @Test
    fun `active playback is not interrupted by a resync seek`() {
        assertThat(
            VideoSurfacePolicy.shouldResyncOnSurfaceReattach(
                playWhenReady = true,
                isLive = false,
                playbackState = Player.STATE_READY,
            ),
        ).isFalse()
    }

    @Test
    fun `live streams never resync via seek`() {
        assertThat(
            VideoSurfacePolicy.shouldResyncOnSurfaceReattach(
                playWhenReady = false,
                isLive = true,
                playbackState = Player.STATE_READY,
            ),
        ).isFalse()
    }

    @Test
    fun `idle and ended players are not resynced`() {
        assertThat(
            VideoSurfacePolicy.shouldResyncOnSurfaceReattach(
                playWhenReady = false,
                isLive = false,
                playbackState = Player.STATE_IDLE,
            ),
        ).isFalse()
        assertThat(
            VideoSurfacePolicy.shouldResyncOnSurfaceReattach(
                playWhenReady = false,
                isLive = false,
                playbackState = Player.STATE_ENDED,
            ),
        ).isFalse()
    }

    @Test
    fun `the resync target never lands on the position the player already reports`() {
        // A seek resolving to the current millisecond returns before the renderers are disabled,
        // so the target has to differ by at least one.
        assertThat(VideoSurfacePolicy.resyncSeekTargetMs(315066L)).isEqualTo(315065L)
        assertThat(VideoSurfacePolicy.resyncSeekTargetMs(1L)).isEqualTo(0L)
    }

    @Test
    fun `a player at the very start nudges forward instead of below zero`() {
        assertThat(VideoSurfacePolicy.resyncSeekTargetMs(0L)).isEqualTo(1L)
    }

    @Test
    fun `a surface that came back on a running video is watched for a stuck first frame`() {
        assertThat(
            VideoSurfacePolicy.shouldWatchFirstFrame(
                wasSurfaceValid = false,
                reenabledVideo = false,
                isAudioOnly = false,
                isLive = false,
            ),
        ).isTrue()
    }

    @Test
    fun `turning video back on after audio only is left to join without a flush`() {
        assertThat(
            VideoSurfacePolicy.shouldWatchFirstFrame(
                wasSurfaceValid = false,
                reenabledVideo = true,
                isAudioOnly = false,
                isLive = false,
            ),
        ).isFalse()
    }

    @Test
    fun `audio only, live and an unchanged surface are never watched`() {
        assertThat(VideoSurfacePolicy.shouldWatchFirstFrame(false, false, isAudioOnly = true, isLive = false)).isFalse()
        assertThat(VideoSurfacePolicy.shouldWatchFirstFrame(false, false, isAudioOnly = false, isLive = true)).isFalse()
        assertThat(VideoSurfacePolicy.shouldWatchFirstFrame(true, false, isAudioOnly = false, isLive = false)).isFalse()
    }
}
