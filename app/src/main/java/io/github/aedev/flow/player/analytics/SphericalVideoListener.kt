package io.github.aedev.flow.player.analytics

import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * Whether a video stream is a 360 video. The container carries the projection (mp4 `sv3d`, WebM
 * `Projection`), which reaches the decoder's input format even when YouTube's own metadata calls the
 * stream rectangular, as it does for some clients (#1295). Manifest track formats never carry it.
 */
internal fun Format.isSpherical(): Boolean = projectionData != null

/** Reports whether the playing video is a 360 video, from the format its decoder receives. */
@UnstableApi
internal class SphericalVideoListener(
    private val onChanged: (spherical: Boolean) -> Unit,
) : AnalyticsListener {
    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) = onChanged(format.isSpherical())

    override fun onMediaItemTransition(
        eventTime: AnalyticsListener.EventTime,
        mediaItem: MediaItem?,
        reason: Int,
    ) = onChanged(false)
}
