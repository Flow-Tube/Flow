package io.github.aedev.flow.player.stream

/**
 * Hands one finished extraction from the player to the next load of the same video.
 *
 * When playback rolls into a preloaded window, the player already plays streams it extracted
 * earlier, yet the watch page still runs its normal load for the new id; without this that load
 * ran the client ladder a second time. The offer is single use and short lived, so a reload after
 * a 403 still extracts fresh URLs.
 */
internal class ExtractionHandoff(
    private val nowMs: () -> Long,
) {
    private class Offer(
        val videoId: String,
        val result: InnerTubeVideoStreamExtractor.VideoExtractionResult,
        val atMs: Long,
    )

    private var offer: Offer? = null

    @Synchronized
    fun offer(
        videoId: String,
        result: InnerTubeVideoStreamExtractor.VideoExtractionResult,
    ) {
        offer = Offer(videoId, result, nowMs())
    }

    /** The offered result for [videoId]; any offer is spent by the first load that asks. */
    @Synchronized
    fun take(videoId: String): InnerTubeVideoStreamExtractor.VideoExtractionResult? {
        val held = offer ?: return null
        offer = null
        return held.result.takeIf { held.videoId == videoId && nowMs() - held.atMs <= MAX_AGE_MS }
    }

    companion object {
        const val MAX_AGE_MS = 30_000L
    }
}
