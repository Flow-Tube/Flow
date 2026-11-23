package io.github.aedev.flow.player.live

import io.github.aedev.flow.innertube.models.response.PlayerResponse

/**
 * YouTube's rule for a live stream that cannot be rewound: when a stall leaves playback further
 * behind live than the readahead plus a margin for the stream's latency class, playback jumps back to
 * live instead of crawling behind it. A stream with DVR never jumps; being behind is allowed there.
 *
 * Without it, Media3's catch-up at its 1.03x ceiling closes a 30 second gap in about 17 minutes.
 */
object LivePegPolicy {
    private const val NORMAL_MARGIN_MS = 15_000L
    private const val LOW_MARGIN_MS = 6_000L
    private const val ULTRA_LOW_MARGIN_MS = 4_000L

    data class Rule(
        val maxBehindLiveMs: Long,
    )

    /** Null for a stream that is never pegged: one with DVR, or one that is not live. */
    fun ruleFor(response: PlayerResponse): Rule? {
        val details = response.videoDetails ?: return null
        if (details.isLive != true || details.isLiveDvrEnabled != false) return null
        val readaheadMs =
            response.playerConfig
                ?.livePlayerConfig
                ?.liveReadaheadSeconds
                ?.let { (it * 1_000).toLong() }
                ?.takeIf { it > 0 } ?: LiveWindow.DEFAULT_READAHEAD_MS
        return Rule(readaheadMs + marginFor(details.latencyClass))
    }

    fun shouldPeg(
        rule: Rule?,
        behindLiveMs: Long,
    ): Boolean = rule != null && behindLiveMs > rule.maxBehindLiveMs

    private fun marginFor(latencyClass: String?): Long =
        when {
            latencyClass == null -> NORMAL_MARGIN_MS
            latencyClass.endsWith("_ULTRA_LOW") -> ULTRA_LOW_MARGIN_MS
            latencyClass.endsWith("_LOW") -> LOW_MARGIN_MS
            else -> NORMAL_MARGIN_MS
        }
}
