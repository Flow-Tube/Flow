package io.github.aedev.flow.data.shorts.queue

import io.github.aedev.flow.data.model.ShortVideo
import io.github.aedev.flow.data.shorts.deferChannelRuns
import io.github.aedev.flow.data.shorts.mergeDiscoveryCandidates
import io.github.aedev.flow.innertube.pages.reel.reelPosterUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a mutation did, so the caller can run the follow-up it owns.
 */
enum class ShortsQueueChange {
    /** Items or order changed, but the short at the current position did not. */
    ListOnly,

    /** A different short now occupies the current position — the pool has to be re-pointed. */
    CurrentItemChanged,

    /** Nothing changed. */
    None,
}

/**
 * Owns a Shorts queue: its order, the current position, and paging.
 *
 * The behaviour every entry point shares lives here exactly once — opening on the short the user
 * tapped, de-duplicating appends, and handing over to the next of [continuations] when [primary]
 * runs dry so a shelf of twenty never dead-ends at twenty.
 */
class ShortsQueueController(
    private val primary: ShortsQueueLoader,
    /**
     * Sources to fall through to, in order, as each one before them runs out.
     *
     * A list rather than a single loader because the subscriptions queue has two fall-backs of very
     * different character: the subscribed channels' own older reels first, and only then anything
     * algorithmic.
     */
    private val continuations: List<ShortsQueueLoader> = emptyList(),
    /**
     * Whether late-arriving algorithmic discovery may be interleaved into this queue.
     *
     * Only true when [primary] *is* the algorithmic feed. Saved Shorts is a deliberate collection
     * and a channel tab is that channel's work — injecting recommendations into either
     * misrepresents it, and background discovery finishes on its own schedule, so without this a
     * discovery pass started on the Shorts tab lands in whatever queue happens to be open.
     */
    private val acceptsDiscovery: Boolean = false,
) {
    private val _items = MutableStateFlow<List<ShortVideo>>(emptyList())
    val items: StateFlow<List<ShortVideo>> = _items.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    /** Every id ever admitted, so an append can never re-add one — including a rejected short. */
    private val seenIds = mutableSetOf<String>()

    /** [primary] first, then each continuation; the queue walks this in order and never back. */
    private val legs: List<ShortsQueueLoader> = listOf(primary) + continuations

    private var cursor: String? = null
    private var legIndex = 0
    private var legStarted = false

    /** The short the user opened the queue on. */
    private var anchorId: String? = null

    /** False only once every loader is done, which is what stops the pager asking for more. */
    val hasMore: Boolean
        get() = legIndex < legs.size

    val currentItem: ShortVideo?
        get() = _items.value.getOrNull(_currentIndex.value)

    /**
     * Puts [startVideoId] on screen before [loadInitial] has its page, when [primary] will lead
     * with it anyway, so the short the user opened plays while the rest of the queue is fetched.
     * The page then lands behind it at the same position. Returns whether it opened.
     */
    fun openOnStartEarly(startVideoId: String?): Boolean {
        val anchor = startVideoId?.takeIf { it.isNotBlank() } ?: return false
        if (!primary.leadsWithStart || _items.value.isNotEmpty()) return false
        anchorId = anchor
        _items.value = listOf(ShortVideo(id = anchor, thumbnailUrl = reelPosterUrl(anchor)))
        _currentIndex.value = 0
        return true
    }

    /**
     * Loads the first page and opens on [startVideoId] when the source names one.
     *
     * The list keeps the order the user was just looking at and the position moves instead — so
     * tapping the third item of a shelf can still be swiped *backwards* through the first two.
     */
    suspend fun loadInitial(startVideoId: String?) {
        legStarted = true
        val page = primary.initial()
        consume(page)

        // `/player` may already have named the short opened early; a bare copy must not undo that.
        val early = _items.value.singleOrNull()
        val items = page.items.distinctById().map { if (early != null && it.id == early.id && it.title.isBlank()) early else it }
        seenIds += items.map { it.id }
        _items.value = items

        val anchor = startVideoId?.takeIf { it.isNotBlank() }
        anchorId = anchor
        _currentIndex.value = anchor?.let(::indexOf) ?: 0
        if (anchor != null && indexOf(anchor) == null && !pageToAnchor(anchor)) openOnPlaceholder(anchor)
    }

    /**
     * Pages forward looking for a start anchor the first page did not contain.
     *
     * A paginated source can hold the tapped short well past page one — a channel's Shorts grid
     * pages as the user scrolls, so a tap forty items down resolves to nothing on the first page.
     *
     * Bounded, and only ever through [primary]: the tap came from that source, so fetching a
     * continuation to look for it only delays the open. [openOnPlaceholder] covers the miss.
     */
    private suspend fun pageToAnchor(anchor: String): Boolean {
        repeat(MAX_ANCHOR_PAGES) {
            if (legIndex > 0 || !hasMore) return false
            val before = _items.value.size
            loadMore()
            if (_items.value.size == before) return false
            indexOf(anchor)?.let { found ->
                _currentIndex.value = found
                return true
            }
        }
        return false
    }

    /**
     * The tapped short opens the queue even when the source never returned it (#1123): an id is
     * all playback needs, and `/player` names the reel as it resolves.
     */
    private fun openOnPlaceholder(anchor: String) {
        seenIds += anchor
        _items.value = listOf(ShortVideo(id = anchor, thumbnailUrl = reelPosterUrl(anchor))) + _items.value
        _currentIndex.value = 0
    }

    private fun indexOf(id: String): Int? = _items.value.indexOfFirst { it.id == id }.takeIf { it >= 0 }

    /**
     * Appends the next page, moving on to the next of [continuations] each time the leg it is
     * walking reports itself exhausted.
     *
     * Re-entrant calls are dropped: the pager asks as the user approaches the end, from more than
     * one place.
     */
    suspend fun loadMore() {
        if (!hasMore || _isLoadingMore.value) return
        _isLoadingMore.value = true
        try {
            repeat(MAX_DEDUPE_ATTEMPTS) {
                val page = fetchNext() ?: return
                val fresh = page.items.filter { it.id !in seenIds }.distinctById()
                if (fresh.isNotEmpty()) {
                    seenIds += fresh.map { it.id }
                    _items.value = _items.value + fresh
                    return
                }
                if (!hasMore) return
            }
        } finally {
            _isLoadingMore.value = false
        }
    }

    fun setCurrentIndex(index: Int) {
        if (index < 0 || index >= _items.value.size) return
        _currentIndex.value = index
    }

    /**
     * Drops a short — "Not interested".
     *
     * Reports [ShortsQueueChange.CurrentItemChanged] when the removed short was the one on screen:
     * the index stays put while a different short slides into it, and the player pool has to be told
     * or it keeps playing the one that was just rejected.
     */
    fun remove(id: String): ShortsQueueChange = removeAll { it.id == id }

    /**
     * Drops a reel an automatic filter rejected once it resolved, except the one the queue opened
     * on: the user picked that one, and removing it would slide a different short under the tap.
     */
    fun dropFiltered(id: String): ShortsQueueChange = if (id == anchorId) ShortsQueueChange.None else remove(id)

    /** Drops every short of a channel — "Don't show this channel". */
    fun removeChannel(channelId: String): ShortsQueueChange {
        if (channelId.isBlank()) return ShortsQueueChange.None
        return removeAll { it.channelId == channelId }
    }

    private fun removeAll(rejected: (ShortVideo) -> Boolean): ShortsQueueChange {
        val current = _items.value
        val updated = current.filterNot(rejected)
        if (updated.size == current.size) return ShortsQueueChange.None

        // Deliberately kept in seenIds so a rejected short cannot come back on the next append.
        _items.value = updated

        if (updated.isEmpty()) {
            _currentIndex.value = 0
            return ShortsQueueChange.CurrentItemChanged
        }

        val position = _currentIndex.value
        val wasCurrent = current.getOrNull(position)?.let(rejected) == true
        // Removing something above the cursor shifts the whole list under it; the index has to move
        // with it or the user is silently pushed onto the next short.
        val removedAbove = current.take(position).count(rejected)
        _currentIndex.value = (position - removedAbove).coerceIn(0, updated.lastIndex)
        return if (wasCurrent) ShortsQueueChange.CurrentItemChanged else ShortsQueueChange.ListOnly
    }

    /**
     * Replaces items in place with enriched copies. Order and position are untouched, with one
     * exception: a reel whose channel has just become known, and that would follow a reel of that
     * same channel, swaps places with the next queued reel of another channel. Sequence reels carry
     * no channel until they resolve, so this is the only point the run is visible at all.
     */
    fun applyEnrichment(enriched: List<ShortVideo>): ShortsQueueChange {
        if (enriched.isEmpty()) return ShortsQueueChange.None
        val current = _items.value
        if (current.isEmpty()) return ShortsQueueChange.None

        val byId = enriched.associateBy { it.id }
        val updated =
            deferChannelRuns(
                items = current.map { existing -> byId[existing.id] ?: existing },
                changedIds = byId.keys,
                currentIndex = _currentIndex.value,
                id = ShortVideo::id,
                channelId = ShortVideo::channelId,
            )
        if (updated == current) return ShortsQueueChange.None
        _items.value = updated
        return ShortsQueueChange.ListOnly
    }

    /**
     * Interleaves late-arriving discovery items after the current position, reusing the existing
     * ordering helper so this behaves exactly as the pre-queue feed did.
     */
    fun mergeDiscovery(discovery: List<ShortVideo>): ShortsQueueChange {
        if (!acceptsDiscovery || discovery.isEmpty()) return ShortsQueueChange.None
        val current = _items.value
        val merged =
            mergeDiscoveryCandidates(
                current = current,
                discovery = discovery.filter { it.id !in seenIds },
                currentIndex = _currentIndex.value,
                id = { it.id },
            )
        if (merged === current) return ShortsQueueChange.None
        seenIds += merged.map { it.id }
        _items.value = merged
        return ShortsQueueChange.ListOnly
    }

    private suspend fun fetchNext(): ShortsQueuePage? {
        val leg = legs.getOrNull(legIndex) ?: return null
        val page =
            if (legStarted) {
                leg.more(cursor)
            } else {
                legStarted = true
                leg.initial()
            }
        consume(page)
        return page
    }

    private fun consume(page: ShortsQueuePage) {
        cursor = page.cursor
        if (!page.exhausted) return
        legIndex++
        legStarted = false
        cursor = null
    }

    private companion object {
        const val MAX_DEDUPE_ATTEMPTS = 3
        const val MAX_ANCHOR_PAGES = 3
    }
}

private fun List<ShortVideo>.distinctById(): List<ShortVideo> = distinctBy { it.id }
