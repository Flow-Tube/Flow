package io.github.aedev.flow.player.surface

import android.os.Build
import androidx.media3.common.Player

object VideoSurfacePolicy {
    /**
     * TextureView below Android 14 exists only for phone picture-in-picture, which froze on a
     * SurfaceView there. The TV interface never enters PiP, and a TextureView makes every video
     * frame redraw the whole window: on a TV chip that drops frames while the audio carries on (#2205).
     */
    fun usesSurfaceView(
        sdkInt: Int,
        tvInterface: Boolean = false,
    ): Boolean = tvInterface || sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** A destroyed SurfaceView surface must come back before video is turned on again; a TextureView keeps its own. */
    fun canRestoreVideoOutput(
        outputIsSurfaceView: Boolean,
        isDisplayInteractive: Boolean,
        isSurfaceValid: Boolean,
    ): Boolean = isDisplayInteractive && (!outputIsSurfaceView || isSurfaceValid)

    /**
     * Whether a same-position seek is needed after a destroyed video surface comes back.
     *
     * Devices that cannot switch a codec's output surface in place (Media3's
     * setOutputSurface workaround list, e.g. many Xiaomi models) re-create the video
     * codec on every surface swap. A re-created codec resumes from the sample-stream
     * read position, not the playhead — while paused that read position can be several
     * seconds ahead, so resuming playback shows a frozen frame until the clock catches
     * up. A same-position seek flushes the codec and realigns video with the playhead.
     * Live streams are excluded: seeking there shifts the live-edge window.
     */
    fun shouldResyncOnSurfaceReattach(
        playWhenReady: Boolean,
        isLive: Boolean,
        playbackState: Int,
    ): Boolean =
        !playWhenReady && !isLive &&
            (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)

    /**
     * Where the resync seek has to land to actually flush anything.
     *
     * ExoPlayerImplInternal.seekToInternal returns before it disables the renderers when the
     * adjusted target rounds to the millisecond the player already reports and the state is READY
     * or BUFFERING — which is exactly the state [shouldResyncOnSurfaceReattach] asks for. A
     * same-position seek was therefore bookkeeping and nothing else, and the codec kept its stale
     * read-ahead. One millisecond is the smallest offset that takes the real path; the caller pairs
     * it with exact seek parameters so it is not snapped to a sync frame seconds away.
     */
    fun resyncSeekTargetMs(positionMs: Long): Long = if (positionMs > 0L) positionMs - 1L else 1L

    /**
     * Whether a surface that came back is watched for a first frame that never draws (#1064).
     *
     * Not when this attach is what turned video back on after audio-only playback: the renderer is
     * then new and decodes forward from the last keyframe, which can take seconds, while Media3
     * keeps the audio going as it catches up. Flushing it there stops the audio to rebuffer (#1298).
     */
    fun shouldWatchFirstFrame(
        wasSurfaceValid: Boolean,
        reenabledVideo: Boolean,
        isAudioOnly: Boolean,
        isLive: Boolean,
    ): Boolean = !wasSurfaceValid && !reenabledVideo && !isAudioOnly && !isLive
}
