package io.github.aedev.flow.player.config

import android.app.ActivityManager
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * The largest frame the track selector may pick: never above 4K, and 1080p on a low-RAM device. The
 * screen viewport, applied beside it, is what keeps automatic quality near the display's own size.
 */
data class VideoSizeCap(
    val maxWidth: Int,
    val maxHeight: Int,
) {
    companion object {
        val UHD = VideoSizeCap(PlayerConfig.MAX_VIDEO_WIDTH, PlayerConfig.MAX_VIDEO_HEIGHT)

        // Not keyed to memoryClass: most phones report 256 MB, and frame size does not decide playback
        // memory, the buffer byte targets do. A 256 MB bucket held Auto at 1080p everywhere (#1617).
        fun forRam(isLowRamDevice: Boolean): VideoSizeCap = if (isLowRamDevice) VideoSizeCap(1920, 1080) else UHD

        fun forDevice(context: Context): VideoSizeCap {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            return forRam(isLowRamDevice = activityManager?.isLowRamDevice == true)
        }
    }
}

/**
 * Drops every per-quality size constraint and puts the device limits back. Clearing alone would also
 * lift the low-RAM cap, and a local file clears the screen viewport, so an automatic pick could climb to
 * a size the device can neither decode comfortably nor show.
 */
@UnstableApi
fun DefaultTrackSelector.Parameters.Builder.resetVideoSizeTo(cap: VideoSizeCap): DefaultTrackSelector.Parameters.Builder =
    apply {
        clearVideoSizeConstraints()
        setMinVideoSize(0, 0)
        setMaxVideoSize(cap.maxWidth, cap.maxHeight)
        setViewportSizeToPhysicalDisplaySize(true)
    }

/** Automatic quality: any size up to the device cap, no forced bitrate, the default codec order. */
@UnstableApi
internal fun adaptiveTrackSelectorDefaults(
    builder: DefaultTrackSelector.Parameters.Builder,
    cap: VideoSizeCap,
): DefaultTrackSelector.Parameters.Builder =
    builder
        .setPreferredVideoMimeTypes(*PlayerConfig.PREFERRED_VIDEO_MIME_TYPES)
        .setAllowVideoMixedMimeTypeAdaptiveness(false)
        .setAllowMultipleAdaptiveSelections(true)
        .resetVideoSizeTo(cap)
        .setForceHighestSupportedBitrate(false)
