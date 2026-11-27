package io.github.aedev.flow.player.stream

import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor.VideoExtractionResult

/** The video's length from its `/player` response, or null when the response does not say. */
internal fun VideoExtractionResult.durationMs(): Long? =
    playerResponse.videoDetails
        ?.lengthSeconds
        ?.toLongOrNull()
        ?.takeIf { it > 0 }
        ?.times(1_000L)

/** Formats that carry a direct URL; the rest need a cipher step nothing downstream performs. */
internal fun VideoExtractionResult.playableVideoFormats() = videoFormats.filter { !it.url.isNullOrBlank() }

internal fun VideoExtractionResult.playableAudioFormats() = audioFormats.filter { !it.url.isNullOrBlank() }
