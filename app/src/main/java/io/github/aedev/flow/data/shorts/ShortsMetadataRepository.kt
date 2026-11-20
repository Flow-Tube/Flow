package io.github.aedev.flow.data.shorts

import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.ReelCommentsPanel
import io.github.aedev.flow.innertube.pages.VideoDescriptionPage
import io.github.aedev.flow.innertube.pages.reel.ReelItem
import io.github.aedev.flow.innertube.pages.reel.ReelOverlay
import io.github.aedev.flow.player.stream.InFlightRequestCoalescer
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The counts, channel, sound, comment panel and description of a reel, from one `reel_item_watch`
 * per reel per half hour.
 *
 * The sequence never carries these, so the pager asks here for the reel on screen only; a swipe
 * back is a cache hit and two pages asking at once share one request.
 */
@Singleton
class ShortsMetadataRepository internal constructor(
    private val fetch: suspend (String) -> ReelItem?,
) {
    @Inject
    constructor() : this({ videoId -> YouTube.reelItem(videoId).getOrNull() })

    private class Entry(
        val item: ReelItem,
        val fetchedAtMillis: Long,
    )

    private val cache =
        object : LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > MAX_ENTRIES
        }
    private val coalescer =
        InFlightRequestCoalescer<String, ReelItem?>(
            CoroutineScope(SupervisorJob() + PerformanceDispatcher.networkIO),
        )

    suspend fun overlayFor(
        videoId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): ReelOverlay? = itemFor(videoId, nowMillis)?.overlay

    suspend fun commentsPanelFor(
        videoId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): ReelCommentsPanel? = itemFor(videoId, nowMillis)?.comments

    suspend fun descriptionFor(
        videoId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): VideoDescriptionPage? = itemFor(videoId, nowMillis)?.description

    private suspend fun itemFor(
        videoId: String,
        nowMillis: Long,
    ): ReelItem? {
        synchronized(cache) { cache[videoId]?.takeIf { nowMillis - it.fetchedAtMillis < TTL_MS } }
            ?.let { return it.item }
        val item = coalescer.run(videoId) { fetch(videoId) } ?: return null
        synchronized(cache) { cache[videoId] = Entry(item, nowMillis) }
        return item
    }

    private companion object {
        const val MAX_ENTRIES = 100
        const val TTL_MS = 30 * 60 * 1000L
    }
}
