package io.github.aedev.flow.data.shorts

import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.resolver.ManifestGenerator
import io.github.aedev.flow.player.stream.InnerTubeStreamBridge
import io.github.aedev.flow.player.stream.model.Stream

/**
 * Wraps a resolved Shorts stream URL in a single-representation DASH manifest.
 *
 * This is what stops YouTube throttling the stream. A plain progressive GET against a googlevideo
 * URL is capped at roughly 50-100 KB/s once the opening burst is spent — enough for VP9 or AV1 at
 * Shorts resolutions, but not for H.264, which needs about twice the bitrate for the same picture.
 * That is why a reel plays for a few seconds and then buffers forever on H.264 while the same reel
 * is fine on AV1 (#917). A manifest carrying the format's init/index byte ranges makes ExoPlayer
 * fetch the stream in ranged chunks instead, which is not throttled.
 *
 * The main player has always done this ([ManifestGenerator], via `VideoPlaybackResolver`); the
 * Shorts pool went straight to `ProgressiveMediaSource` and so never got the benefit.
 *
 * Returns null when a manifest cannot be built — no byte ranges, no duration, an OTF stream. The
 * caller then falls back to a progressive source, which is exactly what Shorts did before.
 */
internal object ShortsDashManifest {
    fun forVideo(
        format: PlayerResponse.StreamingData.Format,
        resolvedUrl: String,
        durationMs: Long?,
    ): String? =
        InnerTubeStreamBridge
            .convertVideoFormats(listOf(format.copy(url = resolvedUrl)))
            .firstOrNull()
            ?.let { forStream(it, durationMs) }

    fun forAudio(
        format: PlayerResponse.StreamingData.Format,
        resolvedUrl: String,
        durationMs: Long?,
    ): String? =
        InnerTubeStreamBridge
            .convertAudioFormats(listOf(format.copy(url = resolvedUrl)))
            .firstOrNull()
            ?.let { forStream(it, durationMs) }

    /**
     * The NewPipe leg already hands us streams carrying an [io.github.aedev.flow.player.stream.model.ItagInfo],
     * so they need no conversion.
     */
    fun forStream(
        stream: Stream,
        durationMs: Long?,
    ): String? {
        val itag = stream.itagItem ?: return null
        val durationSeconds = (durationMs ?: return null) / 1000L
        if (durationSeconds <= 0L) return null
        if (stream.content.isNullOrBlank()) return null
        if (itag.initEnd <= 0 || itag.indexStart < 0 || itag.indexEnd <= 0) return null
        return ManifestGenerator.generateProgressiveManifest(stream, itag, durationSeconds)
    }
}
