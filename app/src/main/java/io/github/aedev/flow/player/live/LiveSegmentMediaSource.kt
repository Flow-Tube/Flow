package io.github.aedev.flow.player.live

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException

/**
 * A Media3 DASH source over a [LiveSegmentSession]. Media3 refreshes the manifest every segment as it
 * does for any dynamic DASH stream; each refresh is written locally from the session's clock, so it
 * costs no request.
 *
 * Playback speed is held at 1.0: YouTube's own live player never adjusts it, and on a template whose
 * time can be off by seconds Media3's live-offset reading is not reliable enough to steer by.
 */
@UnstableApi
internal object LiveSegmentMediaSource {
    private const val SEGMENT_RETRY_COUNT = 6

    /** Long enough for one player request to return fresh URLs. */
    private const val REFUSED_RETRY_DELAY_MS = 3_000L

    fun create(
        session: LiveSegmentSession,
        segmentDataSourceFactory: DataSource.Factory,
        mediaId: String,
        mediaMetadata: MediaMetadata,
    ): MediaSource {
        val item =
            MediaItem
                .Builder()
                .setUri(session.manifestUri)
                .setMimeType(MimeTypes.APPLICATION_MPD)
                .setMediaId(mediaId)
                .setMediaMetadata(mediaMetadata)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration
                        .Builder()
                        .setTargetOffsetMs(session.readaheadMs)
                        .setMinPlaybackSpeed(1f)
                        .setMaxPlaybackSpeed(1f)
                        .build(),
                ).build()
        return DashMediaSource
            .Factory(
                DefaultDashChunkSource.Factory(segmentDataSourceFactory),
                DataSource.Factory { ByteArrayDataSource(::manifestBytes) },
            ).setLoadErrorHandlingPolicy(SegmentErrorPolicy(session.clock.segmentDurationUs / 2_000))
            .createMediaSource(item)
    }

    private fun manifestBytes(uri: Uri): ByteArray =
        LiveSegmentSessions.forUri(uri)?.manifest()?.toByteArray() ?: throw IOException("Live session closed: $uri")

    /** Waits before asking again for a segment the CDN does not have yet, or refused while its URLs are replaced. */
    private class SegmentErrorPolicy(
        private val notReadyDelayMs: Long,
    ) : DefaultLoadErrorHandlingPolicy(SEGMENT_RETRY_COUNT) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
            when (loadErrorInfo.exception) {
                is LiveSegmentNotReadyException -> notReadyDelayMs
                is LiveSegmentRefusedException -> REFUSED_RETRY_DELAY_MS
                else -> super.getRetryDelayMsFor(loadErrorInfo)
            }
    }
}
