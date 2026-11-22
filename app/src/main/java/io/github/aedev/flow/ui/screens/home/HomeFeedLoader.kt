package io.github.aedev.flow.ui.screens.home

import android.content.Context
import android.util.Log
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.HomeFeedCacheRepository
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.ChannelMemoryRepository
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.recommendation.NeighbourChannelsRepository
import io.github.aedev.flow.data.recommendation.NeuroScoring
import io.github.aedev.flow.data.recommendation.RankTrace
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.subscriptions.SubscriptionFeedRepository
import io.github.aedev.flow.ui.screens.home.chips.HomeChipFeeds
import io.github.aedev.flow.utils.PerformanceDispatcher
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import io.github.aedev.flow.utils.traced
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

private data class Wave1FeedResults(
    val discovery: List<Pair<String, List<Video>>>,
    val related: RelatedGraphFetchResult,
)

internal class HomeFeedLoader(
    private val scope: CoroutineScope,
    private val session: HomeFeedSession,
    private val appContext: Context,
    private val repository: YouTubeRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val subscriptionFeedRepository: SubscriptionFeedRepository,
    private val playerPreferences: PlayerPreferences,
    private val feedSources: HomeFeedSources,
    private val persistentHomeFeedCache: HomeFeedCacheRepository,
    private val channelMemory: ChannelMemoryRepository,
    private val neighbourChannels: NeighbourChannelsRepository,
    private val chipFeeds: HomeChipFeeds,
    private val snapshotRecorder: HomeSnapshotRecorder,
    private val enricher: HomeChannelEnricher,
    private val wave2: HomeWave2Discovery,
    private val lateMerges: HomeLateMerges,
    private val onRefreshFailed: () -> Unit,
) {
    private companion object {
        const val MAX_RELATED_SEEDS = 4
        const val SNAPSHOT_FEED_DEBOUNCE_MS = 2_000L

        // Trace section the Home refresh benchmark reads (#1301).
        const val TRACE_WAVE1 = "home.wave1"
    }

    private val uiState = session.uiState
    private var feedJob: Job? = null
    private var visibleMetadataJob: Job? = null
    private var snapshotFeedJob: Job? = null

    val isLoadRunning: Boolean get() = feedJob?.isActive == true

    fun hydratePersistentHomeFeed() {
        scope.launch(PerformanceDispatcher.networkIO) {
            val cached =
                runCatching {
                    persistentHomeFeedCache.loadLastFeed(session.cacheFilters())
                }.getOrElse { emptyList() }
            if (cached.isEmpty()) return@launch
            // Cards the viewer already had on screen are old news; only the rest come back (#1447).
            val feedHistory = runCatching { FlowNeuroEngine.getBrainSnapshot().feedHistory }.getOrDefault(emptyMap())
            val unseen = unseenCachedCards(cached.filterWatched(session.watchedVideoIds.value)) { (feedHistory[it]?.showCount ?: 0) > 0 }
            if (unseen.isEmpty()) return@launch

            // Shown before any network call: the collaboration avatars fill in after (#1301).
            var shownIds = emptyList<String>()
            uiState.update { state ->
                if (state.videos.isNotEmpty()) return@update state
                val videos = unseen
                shownIds = videos.map { it.id }
                session.cachedOnScreenIds = shownIds.toSet()
                HomeFeedCache.update(videos, state.shorts)
                state.copy(
                    videos = videos,
                    isFlowFeed = true,
                    error = null,
                    lastRefreshTime = System.currentTimeMillis(),
                )
            }
            if (shownIds.isEmpty()) return@launch
            val hydratedCached = repository.enrichLikelyCollabAvatarStacks(cached, limit = 8)
            if (hydratedCached != cached) {
                val hydratedById = hydratedCached.associateBy { it.id }
                uiState.update { state ->
                    if (state.videos.map { it.id } != shownIds) return@update state
                    state.copy(videos = state.videos.map { hydratedById[it.id] ?: it })
                }
            }
            enricher.enrichVisibleChannelMetadata(hydratedCached)?.let {
                persistentHomeFeedCache.saveLastFeed(it)
            }
        }
    }

    fun loadFlowFeed(forceRefresh: Boolean = false) {
        if (uiState.value.isLoading && !forceRefresh) return

        wave2.cancel()
        feedJob?.cancel()
        lateMerges.cancelStoreRefreshes()
        visibleMetadataJob?.cancel()
        session.feedGeneration++
        uiState.update { it.copy(isLoading = true, error = null) }

        feedJob =
            scope.launch(PerformanceDispatcher.networkIO) {
                try {
                    session.discoveryQueries.clear()
                    session.searchPages.reset()
                    // A refresh restarts the discovery tree at its roots (broad pass);
                    // load-more regenerations then dig deeper per cluster.
                    session.discoveryQueries.addAll(FlowNeuroEngine.generateDiscoveryQueries(resetDepth = true))
                    session.currentQueryIndex = 0

                    val userSubs = session.subscriptionScope().boosted
                    val region = playerPreferences.trendingRegion.first()
                    val fetchStart = System.currentTimeMillis()

                    // ── Wave 1: first 3 queries + related lanes; subscriptions come from the store ──
                    val wave1QueryCount = session.discoveryQueries.size.coerceAtMost(3)
                    val wave1Queries = session.discoveryQueries.take(wave1QueryCount)
                    session.currentQueryIndex = wave1QueryCount

                    val results =
                        traced(TRACE_WAVE1) {
                            supervisorScope {
                                val deferredDiscovery =
                                    async {
                                        wave1Queries
                                            .map { query ->
                                                async {
                                                    query to
                                                        runCatching {
                                                            withTimeoutOrNull(
                                                                HOME_SEARCH_TIMEOUT_MS,
                                                            ) { session.searchPages.firstPage(query) }
                                                        }.getOrNull().orEmpty()
                                                }
                                            }.awaitAll()
                                    }

                                // ── Related-graph lane: harvest /next neighbours of recent positives ──
                                val deferredRelated =
                                    async {
                                        val seedInputs = feedSources.historySeedInputs()
                                        val longTermInputs = feedSources.longTermSeedInputs()
                                        val seedIds = FlowNeuroEngine.selectRelatedSeeds(seedInputs, MAX_RELATED_SEEDS, longTermInputs)
                                        feedSources.fetchRelatedGraph(seedInputs + longTermInputs, seedIds, session::cacheFilters)
                                    }

                                Wave1FeedResults(
                                    discovery = deferredDiscovery.await(),
                                    related = deferredRelated.await(),
                                )
                            }
                        }

                    val discoveryPairs = results.discovery
                    val rawDiscovery = discoveryPairs.flatMap { it.second }
                    val relatedFetch = results.related
                    val rawRelated = relatedFetch.candidates

                    // Stale-query feedback: queries whose results are mostly
                    // already-shown get skipped by the next generation cycle.
                    reportQueryNovelty(discoveryPairs)

                    Log.d(HOME_LOG_TAG, "Wave 1 fetch completed in ${System.currentTimeMillis() - fetchStart}ms")

                    val subAvatarMap: Map<String, String> =
                        runCatching {
                            subscriptionRepository
                                .getAllSubscriptions()
                                .first()
                                .filter {
                                    it.channelThumbnail.isNotEmpty() &&
                                        !ThumbnailUrlResolver.isUnusableChannelAvatar(it.channelThumbnail)
                                }.associate { it.channelId to it.channelThumbnail }
                        }.getOrElse { emptyMap() }

                    // Extract shorts from all sources for the shelf, ranked by FlowNeuro
                    val now = System.currentTimeMillis()
                    val brain = FlowNeuroEngine.getBrainSnapshot()
                    val taste = feedTasteProfile(brain, FlowNeuroEngine.getPersona(brain))

                    val exclusions = session.feedExclusions()
                    // Subscriptions come from the store, which is local: the network top-up runs
                    // after first paint and never holds the feed back.
                    val storedFeed =
                        if (userSubs.isEmpty()) {
                            emptyList()
                        } else {
                            runCatching { subscriptionFeedRepository.observeFeed().first() }.getOrDefault(emptyList())
                        }
                    val feedShorts =
                        (storedFeed.storedSubscriptionReels(now) + rawDiscovery.extractShorts())
                            .distinctBy { it.id }
                            .filterWatched(session.watchedShortIds.value)
                            .filterRecentHomeSuggestion(now)
                            .filterNot(exclusions::hidesFromRecommendations)
                    var rankedShorts: List<Video>? = null
                    if (feedShorts.isNotEmpty() && playerPreferences.effectiveHomeShortsShelfEnabled.first()) {
                        // A refresh replaces the shelf; appending kept the same reels at its front.
                        val ranked =
                            FlowNeuroEngine
                                .rank(feedShorts, userSubs)
                                .recentlyShownLast { NeuroScoring.isRecentlySeen(brain.feedHistory[it], now) }
                        rankedShorts = ranked
                        // Held with the feed it belongs to, so a held feed moves the grid once.
                        if (session.cachedOnScreenIds == null) uiState.update { state -> state.copy(shorts = ranked) }
                    }

                    val watched = session.watchedVideoIds.value
                    // Cached uploads only: the network refresh runs after first paint.
                    val rawMemory = runCatching { channelMemory.storedUploads(now) }.getOrDefault(emptyList())
                    val neighbours =
                        runCatching {
                            val followed =
                                subscriptionRepository.getAllSubscriptions().first().associate {
                                    it.channelId to
                                        it.channelName
                                }
                            neighbourChannels.neighbours(followed, now)
                        }.getOrDefault(emptyList())
                    val rawNeighbours = runCatching { neighbourChannels.storedUploads(neighbours, now) }.getOrDefault(emptyList())
                    val trace = if (snapshotRecorder.isRecording()) RankTrace() else null
                    session.snapshotTrace = trace
                    if (trace == null) snapshotFeedJob?.cancel()
                    val lanes =
                        buildHomeFeedLanes(
                            rawSubs = storedFeed.storedSubscriptionVideos(now),
                            rawDiscovery = rawDiscovery,
                            rawMemory = rawMemory,
                            rawRelated = rawRelated,
                            rssFeed = storedFeed,
                            watched = watched,
                            exclusions = exclusions,
                            isRecentlyShown = { id -> NeuroScoring.isRecentlySeen(brain.feedHistory[id], now) },
                            timesShown = { id -> brain.feedHistory[id]?.showCount ?: 0 },
                            taste = taste,
                            now = now,
                            freshSlotTarget = dynamicFreshSubSlots(userSubs.size),
                            subAvatarMap = subAvatarMap,
                            rank = { pool -> FlowNeuroEngine.rank(pool, userSubs, trace = trace) },
                            rankRelevant = { pool ->
                                FlowNeuroEngine.rank(pool, userSubs, NeuroScoring.LANE_RELEVANCE_FLOOR, trace)
                            },
                            share = FlowNeuroEngine::shareByInterest,
                            rawNeighbours = rawNeighbours,
                            searchOf = queriesById(discoveryPairs)::get,
                        )

                    Log.d(
                        HOME_LOG_TAG,
                        "Flow candidates: subs=${lanes.subsPoolSize}, discovery=${lanes.discoveryPoolSize}, " +
                            "memory=${lanes.memoryPoolSize}, related=${rawRelated.size}, subCount=${userSubs.size}",
                    )

                    val mix =
                        assembleHomeFeed(
                            lanes = lanes,
                            onScreenIds = uiState.value.videos.mapTo(HashSet()) { it.id },
                            subCount = userSubs.size,
                            totalInteractions = brain.totalInteractions,
                        )
                    val finalMix = mix.videos
                    session.subsBacklog = mix.subsBacklog
                    session.channelMemoryQuota = mix.quotas[FeedSource.CHANNEL_MEMORY] ?: 0
                    session.relatedPickIds.clear()
                    mix.sourceMix.items
                        .filter { it.source == FeedSource.RELATED }
                        .mapTo(session.relatedPickIds) { it.video.id }

                    if (finalMix.isEmpty()) {
                        settleWithoutFeed(error = null)
                        return@launch
                    }
                    val relatedMetrics =
                        buildRelatedLaneMetrics(
                            seedInputs = relatedFetch.seedInputs,
                            seedIds = relatedFetch.seedIds,
                            fetchedPerSeed = relatedFetch.fetchedPerSeed,
                            mergedRelatedCandidates = rawRelated,
                            filteredRelatedCandidates = lanes.relatedCandidates,
                            selectedSourceCounts = mix.selectedSourceCounts,
                            finalFeedCount = finalMix.size,
                            finalRelatedVideoIds =
                                mix.sourceMix.items
                                    .filter { it.source == FeedSource.RELATED }
                                    .mapTo(HashSet()) { it.video.id },
                            brain = brain,
                        )
                    Log.d(HOME_LOG_TAG, relatedMetrics.toLogString())

                    Log.d(
                        HOME_LOG_TAG,
                        "Flow mix: freshLane=${mix.freshAdded}, final=${finalMix.size}, " +
                            "quotas=${mix.quotas}, selected=${mix.sourceMix.sourceCounts}",
                    )

                    val sourceById = mix.sourceMix.items.associate { it.video.id to it.source }
                    val spacedMix =
                        repository.enrichLikelyCollabAvatarStacks(
                            spaceByChannel(
                                rhythmBySource(
                                    finalMix,
                                    isSubscribed = { it.channelId in userSubs },
                                    sourceOf = { sourceById[it.id] },
                                    keepTop = mix.freshAdded,
                                    deferExtra = true,
                                    minSize = FIRST_PAGE_MIN_SIZE,
                                ),
                                gap = CHANNEL_GAP,
                            ),
                            limit = 8,
                        )
                    val renderedIds = spacedMix.mapTo(HashSet()) { it.id }
                    val origins =
                        pageOrigins(
                            spacedMix,
                            sourceById::get,
                            relatedSeedTitles(lanes.relatedCandidates, relatedFetch.seedInputs, session.relatedPickIds),
                            queriesById(discoveryPairs),
                            userSubs,
                            neighbourPointerTexts(
                                neighbours,
                            ) { a, b -> appContext.getString(R.string.home_origin_neighbour_pair, a, b) },
                        )
                    val reserveCandidates =
                        cacheRelatedCandidates(lanes.bestRelated, lanes.relatedMetadata, renderedIds) +
                            cacheCandidates(FeedSource.DISCOVERY, lanes.bestDiscovery, renderedIds)
                    var visibleFeed = emptyList<Video>()
                    val holdBack = session.cachedOnScreenIds != null
                    uiState.update { state ->
                        visibleFeed = spacedMix.filterWatched(session.watchedVideoIds.value)
                        val settled =
                            state.copy(
                                isLoading = false,
                                isRefreshing = false,
                                hasMorePages = true,
                                loadMoreFailed = false,
                                isFlowFeed = true,
                                lastRefreshTime = now,
                            )
                        if (holdBack) {
                            settled.copy(pendingFeed = HomePendingFeed(visibleFeed, rankedShorts ?: state.shorts, origins))
                        } else {
                            settled.copy(videos = visibleFeed, origins = origins)
                        }
                    }
                    HomeFeedCache.update(visibleFeed, rankedShorts ?: uiState.value.shorts)
                    persistentHomeFeedCache.saveLastFeed(spacedMix)
                    persistentHomeFeedCache.saveReserve(reserveCandidates)
                    if (trace != null) {
                        snapshotRecorder.record(
                            createdAt = now,
                            subscriptions = userSubs,
                            watched = watched,
                            discovery = discoveryPairs,
                            related = relatedFetch,
                            subscriptionFeed = storedFeed,
                            memory = rawMemory,
                            page = spacedMix,
                            sourceOf = sourceById::get,
                            trace = trace,
                        )
                        recordShownFeed(trace)
                    }
                    // Its own job: feedJob ends once the feed is on screen, so a pull never waits on these lookups.
                    visibleMetadataJob =
                        scope.launch(PerformanceDispatcher.networkIO) {
                            enricher.enrichVisibleChannelMetadata(spacedMix)?.let {
                                persistentHomeFeedCache.saveLastFeed(it)
                            }
                        }

                    // Enrich (post-paint) with related neighbours of saved/watched videos.
                    lateMerges.enrichFeedWithSavedInterest(userSubs, taste)

                    wave2.startWave2Discovery(finalMix, userSubs, taste)
                    lateMerges.startSubscriptionTopUp(userSubs, storedFeed)
                    lateMerges.startChannelMemoryRefresh(userSubs)
                    val offTopicNeighbours = rawNeighbours.map { it.channelId }.toSet() - lanes.relevantNeighbourChannels
                    lateMerges.startNeighbourRefresh(neighbours, offTopicNeighbours, storedFeed, userSubs)
                    chipFeeds.refreshChips()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    Log.w(HOME_LOG_TAG, "Home feed load failed: ${e::class.simpleName}: ${e.message}")
                    settleWithoutFeed(error = appContext.getString(R.string.error_failed_to_load_feed))
                }
            }
    }

    /** Keeps the recorded snapshot's shown feed current as wave 2, late uploads and load-more add to it. */
    @OptIn(FlowPreview::class)
    private fun recordShownFeed(trace: RankTrace) {
        snapshotFeedJob?.cancel()
        snapshotFeedJob =
            scope.launch(PerformanceDispatcher.diskIO) {
                uiState
                    .map { it.videos }
                    .distinctUntilChanged()
                    .debounce(SNAPSHOT_FEED_DEBOUNCE_MS)
                    .collect { videos -> snapshotRecorder.recordShown(videos, uiState.value.origins, trace) }
            }
    }

    /**
     * Nothing to fall back to: the trending kiosk this used to load is retired, and the charts
     * that replaced it belong on Explore, not mixed into the feed. The screen settles empty and
     * offers a refresh instead of filling itself with unrelated content. A feed already on
     * screen stays, still loading more, and the viewer is told the refresh failed (#1317).
     */
    private fun settleWithoutFeed(error: String?) {
        session.cachedOnScreenIds = null
        var keptFeed = false
        uiState.update {
            keptFeed = it.isFlowFeed && it.videos.isNotEmpty()
            if (keptFeed) {
                it.copy(isLoading = false, isRefreshing = false)
            } else {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    hasMorePages = false,
                    isFlowFeed = false,
                    error = error,
                )
            }
        }
        if (keptFeed) onRefreshFailed()
    }
}
