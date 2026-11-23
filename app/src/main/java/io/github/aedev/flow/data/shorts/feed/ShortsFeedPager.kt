package io.github.aedev.flow.data.shorts.feed

import android.util.Log
import io.github.aedev.flow.data.model.ShortVideo
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.model.toVideo
import io.github.aedev.flow.data.recommendation.ShortsSeedInput
import io.github.aedev.flow.data.recommendation.ShortsSeedSource
import io.github.aedev.flow.innertube.pages.reel.reelPosterUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** The engine calls the pager makes, behind an interface so the offline benchmark can fake them. */
internal interface ShortsFeedEngine {
    suspend fun rank(
        videos: List<Video>,
        subscribedChannelIds: Set<String>,
    ): List<Video>

    suspend fun discoveryQueries(resetDepth: Boolean): List<String>

    suspend fun selectSeeds(
        candidates: List<ShortsSeedInput>,
        maxSeeds: Int,
    ): List<String>

    suspend fun reportQueryNovelty(
        query: String,
        novelRatio: Double,
    )

    suspend fun recentlyShownIds(): Set<String>
}

/** What the pager reads about the user before each page. */
internal interface ShortsFeedContext {
    suspend fun profile(): ShortsFeedProfile

    suspend fun filters(): ShortsFeedFilters

    suspend fun seedInputs(): List<ShortsSeedInput>
}

/**
 * The algorithmic reel feed as an endless sequence of pages: four lane pools that refill in
 * bounded rounds, assembled under quotas, spaced by channel. [open] runs the opening round and
 * returns the first page; [nextPage] never reports the end, because the EXPLORE chain has none.
 */
internal class ShortsFeedPager(
    private val sources: ShortsFeedSources,
    private val engine: ShortsFeedEngine,
    private val context: ShortsFeedContext,
    private val pageSize: Int = PAGE_SIZE,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** How long [open] waits for the opening round before building the page from the lanes that answered. */
    private val openingWaitMs: Long = OPENING_WAIT_MS,
    /** Runs the opening round, which outlives [open] when a lane is slower than [openingWaitMs]; null runs it on the caller's dispatcher. */
    private val roundScope: CoroutineScope? = null,
) {
    private class SeedChain(
        val seedId: String,
        var continuation: String? = null,
        var opened: Boolean = false,
        var pagesServed: Int = 0,
        var failed: Boolean = false,
    ) {
        val hasMore: Boolean
            get() = !failed && (!opened || continuation != null) && pagesServed < PAGES_PER_CHAIN
    }

    private val lock = Mutex()

    /** Guards [pools] and [usedIds]: a lane still out from the opening round offers while pages assemble. */
    private val poolLock = Any()
    private val pools = ShortsFeedLane.entries.associateWith { ArrayDeque<ShortsLaneItem>() }
    private val usedIds = LinkedHashSet<String>()
    private var openingRound: Deferred<Unit>? = null
    private var recentChannels: List<String> = emptyList()
    private var profile = ShortsFeedProfile(emptySet(), isColdStart = true)

    private var exploreContinuation: String? = null
    private var exploreOpened = false

    private val chains = ArrayDeque<SeedChain>()
    private val sessionSeeds = LinkedHashMap<String, ShortsSeedInput>()

    private var queries: List<String> = emptyList()
    private var queryIndex = 0
    private var discoveryDepth = 0
    private var discoveryExhausted = false

    private val pendingChannels = ArrayDeque<String>()
    private val parkedChannels = ArrayDeque<String>()
    private var subscriptionsOpened = false

    var pagesServed = 0
        private set
    private val requestCounter = AtomicInteger()

    val requestsForLastPage: Int
        get() = requestCounter.get()

    /** Pages that follow [open]; the first page is returned by [open] itself. */
    suspend fun open(startVideoId: String?): List<ShortVideo> =
        lock.withLock {
            profile = context.profile()
            val seedInputs = context.seedInputs() + sessionSeeds.values
            requestCounter.set(0)
            val scope = roundScope ?: CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
            val round =
                scope.async {
                    coroutineScope {
                        listOf(
                            async { refillExplore() },
                            async { openChains(startVideoId, seedInputs) },
                            async { refillDiscovery(resetDepth = true, queriesToRun = OPENING_QUERIES) },
                            async { refillSubscriptions(channelsToVisit = OPENING_CHANNELS) },
                        ).awaitAll()
                    }
                    Unit
                }
            openingRound = round
            // The first page is what the screen waits on: on a slow connection one lane timing out
            // held it for twenty seconds. A lane still out at the deadline fills the next page.
            if (withTimeoutOrNull(openingWaitMs) { round.await() } == null && poolsEmpty()) round.await()
            val page = assemble()
            if (startVideoId == null) return@withLock page
            // The tapped reel opens the queue even when no lane returned it.
            val tapped =
                page.firstOrNull { it.id == startVideoId } ?: ShortVideo(id = startVideoId, thumbnailUrl = reelPosterUrl(startVideoId))
            synchronized(poolLock) { usedIds += startVideoId }
            listOf(tapped) + page.filterNot { it.id == startVideoId }
        }

    suspend fun nextPage(): List<ShortVideo> =
        lock.withLock {
            openingRound?.join()
            requestCounter.set(0)
            refill()
            val page = assemble()
            if (page.isNotEmpty()) return@withLock page
            // Never dry: the seedless chain has no end, so one more explore page always exists.
            refillExplore()
            assemble()
        }

    /** Opens a chain from [short] now and returns its first page, for the caller to interleave. */
    suspend fun chainFrom(short: ShortVideo): List<ShortVideo> =
        lock.withLock {
            openingRound?.join()
            sessionSeeds[short.id] = ShortsSeedInput(short.id, short.channelId, ShortsSeedSource.WANT_MORE, nowMillis())
            val chain = SeedChain(short.id)
            chains.addFirst(chain)
            val batch = advance(chain) ?: return@withLock emptyList()
            val filters = context.filters()
            synchronized(poolLock) {
                batch
                    .filter { it.id !in usedIds && it.id !in filters.watchedIds && it.id !in filters.suppressedIds }
                    .take(pageSize)
                    .also { page -> usedIds += page.map { it.id } }
            }
        }

    /** A reel the user dwelt on: a weak seed for later chains this session. */
    fun noteDwell(short: ShortVideo) {
        if (short.id.isBlank() || sessionSeeds.containsKey(short.id)) return
        sessionSeeds[short.id] = ShortsSeedInput(short.id, short.channelId, ShortsSeedSource.FEED, nowMillis())
        while (sessionSeeds.size > SESSION_SEEDS_MAX) sessionSeeds.remove(sessionSeeds.keys.first())
    }

    /** The unserved lane heads, for a persistent reserve that opens the next session instantly. */
    fun reserveTail(max: Int): List<ShortsLaneItem> =
        synchronized(poolLock) {
            ShortsFeedLane.entries
                .flatMap { lane -> pools.getValue(lane).take(max / ShortsFeedLane.entries.size) }
                .take(max)
        }

    fun restoreReserve(items: List<ShortsLaneItem>) {
        synchronized(poolLock) {
            items.forEach { item -> if (item.short.id !in usedIds) pools.getValue(item.lane).addLast(item) }
        }
    }

    fun poolSizes(): Map<ShortsFeedLane, Int> = synchronized(poolLock) { pools.mapValues { (_, pool) -> pool.size } }

    fun evictChannel(channelId: String) {
        if (channelId.isBlank()) return
        synchronized(poolLock) { pools.values.forEach { pool -> pool.removeAll { it.short.channelId == channelId } } }
    }

    private fun poolsEmpty(): Boolean = synchronized(poolLock) { pools.values.all { it.isEmpty() } }

    private suspend fun refill() {
        profile = context.profile()
        val quotas = shortsFeedQuotas(pageSize, profile)

        fun short(lane: ShortsFeedLane) = pools.getValue(lane).size < maxOf(MIN_BUFFER, (quotas[lane] ?: 0) * BUFFER_PAGES)
        coroutineScope {
            listOfNotNull(
                if (short(ShortsFeedLane.RELATED)) async { refillRelated() } else null,
                if (short(ShortsFeedLane.EXPLORE)) async { refillExplore() } else null,
                if (short(
                        ShortsFeedLane.DISCOVERY,
                    )
                ) {
                    async { refillDiscovery(resetDepth = false, queriesToRun = QUERIES_PER_PAGE) }
                } else {
                    null
                },
                if (short(ShortsFeedLane.SUBSCRIPTIONS)) async { refillSubscriptions(channelsToVisit = CHANNELS_PER_PAGE) } else null,
            ).awaitAll()
        }
    }

    private suspend fun assemble(): List<ShortVideo> {
        val filters = context.filters()
        val assembly =
            synchronized(poolLock) {
                assembleShortsPage(
                    pools = pools.mapValues { (_, pool) -> pool.toList() },
                    quotas = shortsFeedQuotas(pageSize, profile),
                    targetSize = pageSize,
                    filters = filters,
                    usedIds = usedIds.toSet(),
                    recentChannels = recentChannels,
                ).also { assembly ->
                    pools.values.forEach { pool -> pool.removeAll { it.short.id in assembly.consumed } }
                    usedIds += assembly.consumed
                }
            }
        val page = assembly.page.map { it.short }
        if (page.isNotEmpty()) {
            pagesServed++
            recentChannels = (recentChannels + page.map { it.channelId }.filter { it.isNotBlank() }).takeLast(CHANNEL_GAP)
            Log.d(TAG, "Page $pagesServed: ${assembly.laneCounts} in $requestsForLastPage request(s)")
        }
        return page
    }

    private suspend fun refillExplore() {
        if (exploreOpened && exploreContinuation == null) return
        requestCounter.incrementAndGet()
        val batch = sources.explore(if (exploreOpened) exploreContinuation else null) ?: return
        exploreOpened = true
        exploreContinuation = batch.continuation
        offer(ShortsFeedLane.EXPLORE, batch.items)
    }

    private suspend fun openChains(
        startVideoId: String?,
        seedInputs: List<ShortsSeedInput>,
    ) {
        val seeds = mutableListOf<String>()
        if (startVideoId != null) seeds += startVideoId
        val wanted = OPENING_SEEDS - seeds.size
        if (wanted > 0) seeds += engine.selectSeeds(seedInputs, wanted).filterNot { it in seeds }
        coroutineScope {
            seeds
                .map { seed ->
                    SeedChain(seed).also(chains::addLast)
                }.map { chain ->
                    async { advance(chain)?.let { offer(ShortsFeedLane.RELATED, it, chain.seedId) } }
                }.awaitAll()
        }
    }

    private suspend fun refillRelated() {
        chains.removeAll { !it.hasMore }
        if (chains.isEmpty()) {
            val seedInputs = context.seedInputs() + sessionSeeds.values
            engine.selectSeeds(seedInputs, 1).forEach { chains.addLast(SeedChain(it)) }
        }
        val chain = chains.firstOrNull() ?: return
        advance(chain)?.let { offer(ShortsFeedLane.RELATED, it, chain.seedId) }
    }

    /** One page of [chain]; null when the request failed, in which case the chain is dropped. */
    private suspend fun advance(chain: SeedChain): List<ShortVideo>? {
        requestCounter.incrementAndGet()
        val batch = sources.related(chain.seedId, if (chain.opened) chain.continuation else null)
        if (batch == null) {
            chain.failed = true
            return null
        }
        chain.opened = true
        chain.continuation = batch.continuation
        chain.pagesServed++
        return batch.items
    }

    private suspend fun refillDiscovery(
        resetDepth: Boolean,
        queriesToRun: Int,
    ) {
        if (discoveryExhausted && !resetDepth) return
        if (resetDepth || queryIndex >= queries.size) {
            if (!resetDepth && discoveryDepth >= MAX_DISCOVERY_DEPTH) {
                discoveryExhausted = true
                return
            }
            queries = engine.discoveryQueries(resetDepth)
            queryIndex = 0
            discoveryDepth = if (resetDepth) 0 else discoveryDepth + 1
            if (queries.isEmpty()) {
                discoveryExhausted = true
                return
            }
        }
        val batch = queries.drop(queryIndex).take(queriesToRun)
        queryIndex += batch.size
        if (batch.isEmpty()) return
        val shown = engine.recentlyShownIds()
        val results =
            coroutineScope {
                batch
                    .map { query ->
                        async {
                            requestCounter.incrementAndGet()
                            sources.discovery(query).also { reels ->
                                if (reels.size >= NOVELTY_MIN_RESULTS) {
                                    engine.reportQueryNovelty(query, reels.count { it.id !in shown } / reels.size.toDouble())
                                }
                            }
                        }
                    }.awaitAll()
            }.flatten()
        offer(ShortsFeedLane.DISCOVERY, rankTitled(results))
    }

    private suspend fun refillSubscriptions(channelsToVisit: Int) {
        if (!profile.hasSubscriptions) return
        if (!subscriptionsOpened) {
            subscriptionsOpened = true
            offer(ShortsFeedLane.SUBSCRIPTIONS, rankTitled(sources.subscriptionReels()))
            pendingChannels.addAll(sources.subscribedChannels())
        }
        val visit = mutableListOf<String>()
        repeat(channelsToVisit) {
            (pendingChannels.removeFirstOrNull() ?: parkedChannels.removeFirstOrNull())?.let(visit::add)
        }
        if (visit.isEmpty()) return
        val reels =
            coroutineScope {
                visit
                    .map { channelId ->
                        async {
                            requestCounter.incrementAndGet()
                            sources
                                .channelReels(
                                    channelId,
                                ).also { if (it.isNotEmpty()) synchronized(parkedChannels) { parkedChannels.addLast(channelId) } }
                        }
                    }.awaitAll()
            }.flatten()
        offer(ShortsFeedLane.SUBSCRIPTIONS, rankTitled(reels))
    }

    private suspend fun rankTitled(reels: List<ShortVideo>): List<ShortVideo> {
        if (reels.size < 2) return reels
        val byId = reels.associateBy { it.id }
        return engine.rank(reels.map { it.toVideo() }, profile.subscribedChannelIds).mapNotNull { byId[it.id] }
    }

    private fun offer(
        lane: ShortsFeedLane,
        reels: List<ShortVideo>,
        seedId: String? = null,
    ) {
        val pool = pools.getValue(lane)
        synchronized(poolLock) {
            val queued = pool.mapTo(HashSet()) { it.short.id }
            reels.forEach { reel ->
                if (reel.id.isNotBlank() && reel.id !in usedIds && queued.add(reel.id)) pool.addLast(ShortsLaneItem(reel, lane, seedId))
            }
        }
    }

    private companion object {
        const val TAG = "ShortsFeedPager"
        const val PAGE_SIZE = 16
        const val BUFFER_PAGES = 2
        const val MIN_BUFFER = 4
        const val CHANNEL_GAP = 2
        const val OPENING_SEEDS = 2
        const val OPENING_QUERIES = 3
        const val OPENING_CHANNELS = 4
        const val OPENING_WAIT_MS = 2_500L
        const val QUERIES_PER_PAGE = 2
        const val CHANNELS_PER_PAGE = 2
        const val PAGES_PER_CHAIN = 3
        const val MAX_DISCOVERY_DEPTH = 6
        const val NOVELTY_MIN_RESULTS = 5
        const val SESSION_SEEDS_MAX = 30
    }
}
