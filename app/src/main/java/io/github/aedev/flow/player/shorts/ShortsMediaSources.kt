package io.github.aedev.flow.player.shorts

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.aedev.flow.player.resolver.MediaSourceBuilder

/** Loads a reel into a pooled player: separate video and audio merged, or one muxed stream. */
@OptIn(UnstableApi::class)
internal object ShortsMediaSources {
    private const val TAG = "ShortsPlayerPool"

    fun load(
        player: ExoPlayer,
        factory: DataSource.Factory,
        streams: ShortsSlotStreams,
    ) {
        val videoUrl = streams.videoUrl
        val audioUrl = streams.audioUrl
        if (audioUrl != null && audioUrl != videoUrl) {
            val videoSource =
                streams.adaptiveManifest?.let { DashMediaSource.Factory(factory).createMediaSource(it, MediaItem.fromUri(videoUrl)) }
                    ?: sourceFor(factory, videoUrl, streams.videoManifest)
            val audioSource = sourceFor(factory, audioUrl, streams.audioManifest)
            player.setMediaSource(MergingMediaSource(true, true, videoSource, audioSource))
        } else {
            player.setMediaSource(sourceFor(factory, videoUrl, streams.videoManifest))
        }
        player.prepare()
    }

    private fun sourceFor(
        factory: DataSource.Factory,
        url: String,
        dashManifest: String?,
    ): MediaSource {
        if (dashManifest != null) {
            runCatching {
                return MediaSourceBuilder.buildDashSource(factory, dashManifest, Uri.parse(url))
            }.onFailure { Log.w(TAG, "Generated DASH manifest rejected, falling back to progressive: ${it.message}") }
        }
        return ProgressiveMediaSource
            .Factory(factory)
            .createMediaSource(MediaItem.fromUri(url))
    }
}
