package io.github.aedev.flow.data.shorts.queue

import io.github.aedev.flow.data.model.ShortVideo

/**
 * One page of a Shorts queue.
 *
 * [exhausted] is the loader's own verdict rather than something inferred from [items] being short or
 * [cursor] being null. Inferring it is what made the old feed claim more pages existed whenever it
 * had five or more items, and then fall through to a cache-clearing refresh when the continuation
 * turned out to be absent.
 */
data class ShortsQueuePage(
    val items: List<ShortVideo>,
    val cursor: String? = null,
    val exhausted: Boolean = cursor == null,
)

/**
 * A source of Shorts, page by page.
 *
 * Implementations own *only* fetching. Ordering, the start anchor, de-duplication and handing over
 * to the feed all live in [ShortsQueueController], so every entry point gets that behaviour without
 * restating it.
 */
interface ShortsQueueLoader {
    /** True when [initial] always puts the start short first, so the queue can open on it before the page arrives. */
    val leadsWithStart: Boolean
        get() = false

    suspend fun initial(): ShortsQueuePage

    /** Returns an exhausted, empty page when there is nothing more — never throws for that case. */
    suspend fun more(cursor: String?): ShortsQueuePage
}
