package io.github.aedev.flow.player.stream

/**
 * The last few playable extractions, so closing the mini player, going back or replaying a video
 * does not run the client ladder again. YouTube's player keeps its playable responses the same way,
 * for the server's own URL lifetime.
 *
 * An entry is kept for [EXPIRY_FRACTION_PERCENT] of the response's `expiresInSeconds`, at most
 * [MAX_TTL_MS], and is dropped as soon as what it vouches for stops holding: the visitor identity
 * it was extracted under was replaced, or GVS gated the client that minted its URLs. Live and SABR
 * results are never kept, since their manifests and sessions move on while they play. A profile
 * switch restarts the process, so nothing here outlives one.
 */
internal class ReusableExtractions(
    private val identity: () -> Int,
    private val isGated: (clientName: String) -> Boolean,
    private val nowMs: () -> Long,
    private val capacity: Int = CAPACITY,
) {
    private class Entry(
        val result: InnerTubeVideoStreamExtractor.VideoExtractionResult,
        val expiresAtMs: Long,
        val identity: Int,
    )

    private val entries = LinkedHashMap<String, Entry>(capacity, 0.75f, true)

    @Synchronized
    fun store(
        videoId: String,
        result: InnerTubeVideoStreamExtractor.VideoExtractionResult,
    ) {
        val ttlMs = ttlMs(result) ?: return
        entries[videoId] = Entry(result, nowMs() + ttlMs, identity())
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun get(videoId: String): InnerTubeVideoStreamExtractor.VideoExtractionResult? {
        val entry = entries[videoId] ?: return null
        val stale =
            nowMs() >= entry.expiresAtMs ||
                entry.identity != identity() ||
                isGated(entry.result.usedClient.clientName)
        if (stale) entries.remove(videoId)
        return entry.result.takeUnless { stale }
    }

    /** Drops [videoId]'s entry: its URLs were refused, or a recovery is about to replace them. */
    @Synchronized
    fun forget(videoId: String) {
        entries.remove(videoId)
    }

    private fun ttlMs(result: InnerTubeVideoStreamExtractor.VideoExtractionResult): Long? {
        if (result.isLive || result.sabrInfo != null) return null
        if (result.videoFormats.none { !it.url.isNullOrEmpty() } || result.audioFormats.none { !it.url.isNullOrEmpty() }) return null
        val expiresInSeconds =
            result.playerResponse.streamingData
                ?.expiresInSeconds
                ?.takeIf { it > 0 } ?: return null
        return minOf(expiresInSeconds * 1_000L * EXPIRY_FRACTION_PERCENT / 100L, MAX_TTL_MS)
    }

    companion object {
        const val CAPACITY = 4
        const val EXPIRY_FRACTION_PERCENT = 80L
        const val MAX_TTL_MS = 10L * 60L * 1_000L
    }
}
