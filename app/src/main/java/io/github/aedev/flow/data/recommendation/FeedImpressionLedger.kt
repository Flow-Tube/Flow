package io.github.aedev.flow.data.recommendation

/**
 * Which of the cards just seen count as a new sighting. A card counts again once [RECOUNT_WINDOW_MS] has
 * passed since its last counted sighting, so scrolling past it twice in one sitting is one sighting,
 * while passing it again later in the day reaches the seen-gate's longer hide (#1565). The window is
 * read from the persisted feed history, so it holds across process restarts.
 */
internal object FeedImpressionLedger {
    const val RECOUNT_WINDOW_MS = 30 * 60 * 1000L

    fun countable(
        ids: List<String>,
        feedHistory: Map<String, FeedEntry>,
        now: Long,
    ): List<String> =
        ids.distinct().filter { id ->
            val last = feedHistory[id]?.lastShown ?: return@filter true
            now - last >= RECOUNT_WINDOW_MS
        }
}
