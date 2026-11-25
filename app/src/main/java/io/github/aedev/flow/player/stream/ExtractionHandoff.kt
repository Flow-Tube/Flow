package io.github.aedev.flow.player.stream

/**
 * Hands one finished extraction to the next load of the same video.
 *
 * When playback rolls into a preloaded window, the player already plays streams it extracted
 * earlier, yet the watch page still runs its normal load for the new id; without this that load
 * ran the client ladder a second time. The offer is single use and short lived, so a reload after
 * a 403 still extracts fresh URLs, and an offer made under another visitor [identity] is not used.
 */
internal class ExtractionHandoff(
    private val maxAgeMs: Long = MAX_AGE_MS,
    private val identity: () -> Int = { 0 },
    private val nowMs: () -> Long,
) {
    private class Offer(
        val videoId: String,
        val result: InnerTubeVideoStreamExtractor.VideoExtractionResult,
        val atMs: Long,
        val identity: Int,
    )

    private var offer: Offer? = null

    @Synchronized
    fun offer(
        videoId: String,
        result: InnerTubeVideoStreamExtractor.VideoExtractionResult,
    ) {
        offer = Offer(videoId, result, nowMs(), identity())
    }

    /** Whether a usable offer for [videoId] is waiting; asking does not spend it. */
    @Synchronized
    fun holds(videoId: String): Boolean = offer?.let { it.videoId == videoId && it.isUsable() } == true

    /** The offered result for [videoId]; any offer is spent by the first load that asks. */
    @Synchronized
    fun take(videoId: String): InnerTubeVideoStreamExtractor.VideoExtractionResult? {
        val held = offer ?: return null
        offer = null
        return held.result.takeIf { held.videoId == videoId && held.isUsable() }
    }

    private fun Offer.isUsable(): Boolean = nowMs() - atMs <= maxAgeMs && identity == identity()

    companion object {
        const val MAX_AGE_MS = 30_000L
    }
}
