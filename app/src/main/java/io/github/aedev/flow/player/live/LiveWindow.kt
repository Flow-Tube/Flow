package io.github.aedev.flow.player.live

import io.github.aedev.flow.innertube.models.response.PlayerResponse

/**
 * What a live player response says about the stream's window: how long a segment is, how far back
 * the stream can be played, and how far behind the newest segment playback should sit.
 */
data class LiveWindow(
    val segmentDurationMs: Long,
    val maxDvrDurationMs: Long,
    val readaheadMs: Long,
    val isDvrEnabled: Boolean,
    val isLowLatency: Boolean,
) {
    companion object {
        /** YouTube's own fallback when a response carries no live player config. */
        const val DEFAULT_READAHEAD_MS = 12_000L

        /** Null when the response lists no live formats: a VOD, or a client that returns manifests only. */
        fun of(response: PlayerResponse): LiveWindow? {
            val formats = response.streamingData?.adaptiveFormats.orEmpty()
            val segmentDurationMs =
                formats
                    .firstNotNullOfOrNull { it.targetDurationSec }
                    ?.let { (it * 1_000).toLong() }
                    ?.takeIf { it > 0 } ?: return null
            val latency = response.videoDetails?.latencyClass.orEmpty()
            return LiveWindow(
                segmentDurationMs = segmentDurationMs,
                maxDvrDurationMs = formats.firstNotNullOfOrNull { it.maxDvrDurationSec }?.let { it * 1_000 } ?: 0L,
                readaheadMs =
                    response.playerConfig
                        ?.livePlayerConfig
                        ?.liveReadaheadSeconds
                        ?.let { (it * 1_000).toLong() }
                        ?.takeIf { it > 0 } ?: DEFAULT_READAHEAD_MS,
                isDvrEnabled = response.videoDetails?.isLiveDvrEnabled != false,
                isLowLatency = latency.endsWith("_LOW") || latency.endsWith("_ULTRA_LOW"),
            )
        }
    }
}
