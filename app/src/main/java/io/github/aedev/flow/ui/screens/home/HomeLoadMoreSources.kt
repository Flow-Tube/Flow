package io.github.aedev.flow.ui.screens.home

import android.util.Log
import io.github.aedev.flow.data.local.HomeFeedCacheRepository
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.ChannelMemoryRepository
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.recommendation.GraphSeedInput
import io.github.aedev.flow.data.recommendation.NeuroScoring
import io.github.aedev.flow.data.recommendation.UserBrain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update

internal class HomeLoadMoreSources(
    private val session: HomeFeedSession,
    private val feedSources: HomeFeedSources,
    private val persistentHomeFeedCache: HomeFeedCacheRepository,
    private val channelMemory: ChannelMemoryRepository,
) {
    private companion object {
        const val LOAD_MORE_GRAPH_SEEDS = 3

        // Saved rows of each source ranked again on load-more; the best of them fill the page.
        const val RESERVE_RERANK_PER_SOURCE = 16

        // Never-dry load-more: fallback related pass seeded from feed + saved interests.
        const val LOAD_MORE_FALLBACK_SEEDS = 4
        const val FEED_SEED_POOL = 30
    }

    private val uiState = session.uiState

    /** One upload from a remembered channel per load-more page, so the lane outlives the first screen. */
    suspend fun fillPageFromChannelMemory(
        page: MutableList<Video>,
        channelCounts: MutableMap<String, Int>,
        pageIds: MutableSet<String>,
        userSubs: Set<String>,
        now: Long,
    ) {
        val brain = FlowNeuroEngine.getBrainSnapshot()
        val exclusions = session.feedExclusions()
        val candidates =
            runCatching { channelMemory.storedUploads(now) }
                .getOrDefault(emptyList())
                .filterValid()
                .filterWatched(session.watchedVideoIds.value)
                .filterNot { exclusions.hidesFromRecommendations(it) || NeuroScoring.isRecentlySeen(brain.feedHistory[it.id], now) }
        if (candidates.isEmpty()) return
        val pick =
            channelMemoryPicks(
                ranked = FlowNeuroEngine.rank(candidates, userSubs),
                onScreenIds = pageIds,
                onScreenMemoryChannels = page.mapTo(HashSet()) { it.channelId },
                room = 1,
            ).firstOrNull() ?: return
        if (addUniqueVideo(pick, page, channelCounts, pageIds, maxPerChannel = 1)) {
            uiState.update { it.copy(origins = it.origins + (pick.id to HomeCardOrigin.channelMemory)) }
        }
    }

    /**
     * Cheapest load-more source: candidates already fetched and persisted by an earlier pass, ranked
     * again because the viewer's taste may have moved since they were saved.
     */
    suspend fun fillPageFromReserve(
        page: MutableList<Video>,
        channelCounts: MutableMap<String, Int>,
        pageIds: MutableSet<String>,
        userSubs: Set<String>,
        now: Long,
        searchIds: MutableSet<String>,
    ): Int {
        val reserveRows =
            try {
                persistentHomeFeedCache.loadReservePage(
                    session.cacheFilters(),
                    maxRelated = RESERVE_RERANK_PER_SOURCE,
                    maxDiscovery = RESERVE_RERANK_PER_SOURCE,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.d(HOME_LOG_TAG, "Reserve prefetch unavailable: ${error.message}")
                emptyList()
            }
        if (reserveRows.isEmpty()) return 0
        val relatedIds =
            reserveRows
                .filter { it.source == HomeFeedCacheRepository.SOURCE_RELATED }
                .mapTo(HashSet()) { it.video.id }
        val reserveVideos =
            FlowNeuroEngine.shareByInterest(
                FlowNeuroEngine.rank(
                    reserveRows
                        .map { it.video }
                        .filterValid()
                        .filterRecentHomeSuggestion(now),
                    userSubs,
                    NeuroScoring.LANE_RELEVANCE_FLOOR,
                    session.snapshotTrace,
                ),
            )
        val start = page.size
        val reserveAdded =
            addUniquePageVideos(
                candidates = reserveVideos,
                targetList = page,
                channelCounts = channelCounts,
                usedVideoIds = pageIds,
                targetSize = HOME_LOAD_MORE_PAGE_SIZE,
            )
        // Channel memory may already be on the page, so the served rows are the ones added here.
        val served = page.drop(start).map { it.id }
        served.filterTo(session.relatedPickIds) { it in relatedIds }
        served.filterNotTo(searchIds) { it in relatedIds }
        if (served.isNotEmpty()) {
            runCatching { persistentHomeFeedCache.consumeReserve(served) }
        }
        return reserveAdded
    }

    /**
     * Digs related lanes from the widest seed universe — history, liked, playlists and the
     * feed videos on screen. Returns how many seeds were expanded.
     */
    suspend fun fillPageFromRelatedGraph(
        page: MutableList<Video>,
        channelCounts: MutableMap<String, Int>,
        pageIds: MutableSet<String>,
        now: Long,
        userSubs: Set<String>,
        taste: FeedTasteProfile,
        brain: UserBrain,
        relatedTo: MutableMap<String, String>,
    ): Int {
        val seedInputs = loadMoreSeedInputs()
        val longTermInputs = feedSources.longTermSeedInputs()
        val seedIds = FlowNeuroEngine.selectRelatedSeeds(seedInputs, LOAD_MORE_GRAPH_SEEDS, longTermInputs)
        if (seedIds.isEmpty()) return 0

        val graphFetch = feedSources.fetchRelatedGraph(seedInputs + longTermInputs, seedIds, session::cacheFilters)
        val graphCandidates =
            graphFetch.candidates
                .filterValidGraph()
                .filterWatchedGraph(session.watchedVideoIds.value)
                .filterRecentHomeSuggestionGraph(now)
        val graphMetadata = graphCandidates.associateBy { it.video.id }
        val graphRanked =
            FlowNeuroEngine.shareByInterest(
                demoteByFit(
                    applyGraphBoost(
                        FlowNeuroEngine.rank(
                            graphCandidates.map { it.video },
                            userSubs,
                            NeuroScoring.LANE_RELEVANCE_FLOOR,
                            session.snapshotTrace,
                        ),
                        graphMetadata,
                    ),
                    taste,
                ),
            )
        val graphStartIndex = page.size
        addUniquePageVideos(
            candidates = graphRanked,
            targetList = page,
            channelCounts = channelCounts,
            usedVideoIds = pageIds,
            targetSize = HOME_LOAD_MORE_PAGE_SIZE,
        )
        val graphAdded = page.drop(graphStartIndex).mapTo(HashSet()) { it.id }
        session.relatedPickIds += graphAdded
        relatedTo += relatedSeedTitles(graphCandidates, graphFetch.seedInputs, graphAdded)
        persistentHomeFeedCache.saveReserve(
            cacheRelatedCandidates(graphRanked, graphMetadata, pageIds),
        )
        if (page.size >= HOME_LOAD_MORE_PAGE_SIZE) {
            val selectedGraphIds = page.drop(graphStartIndex).mapTo(HashSet()) { it.id }
            Log.d(
                HOME_LOG_TAG,
                buildRelatedLaneMetrics(
                    seedInputs = graphFetch.seedInputs,
                    seedIds = graphFetch.seedIds,
                    fetchedPerSeed = graphFetch.fetchedPerSeed,
                    mergedRelatedCandidates = graphFetch.candidates,
                    filteredRelatedCandidates = graphCandidates,
                    selectedSourceCounts = mapOf(FeedSource.RELATED to selectedGraphIds.size),
                    finalFeedCount = selectedGraphIds.size,
                    finalRelatedVideoIds = selectedGraphIds,
                    brain = brain,
                ).toLogString(),
            )
        }
        return seedIds.size
    }

    suspend fun fillPageFromFallbackGraph(
        page: MutableList<Video>,
        channelCounts: MutableMap<String, Int>,
        pageIds: MutableSet<String>,
        now: Long,
        userSubs: Set<String>,
        taste: FeedTasteProfile,
        relatedTo: MutableMap<String, String>,
        isCurrent: () -> Boolean,
    ) {
        val fallbackInputs = loadMoreSeedInputs()
        val fallbackLongTerm = feedSources.longTermSeedInputs()
        val fallbackSeeds =
            FlowNeuroEngine.selectRelatedSeeds(fallbackInputs, LOAD_MORE_FALLBACK_SEEDS, fallbackLongTerm)
        if (fallbackSeeds.isNotEmpty() && isCurrent()) {
            val fallbackFetch =
                feedSources.fetchRelatedGraph(fallbackInputs + fallbackLongTerm, fallbackSeeds, session::cacheFilters)
            val fallbackCandidates =
                fallbackFetch.candidates
                    .filterValidGraph()
                    .filterWatchedGraph(session.watchedVideoIds.value)
                    .filterRecentHomeSuggestionGraph(now)
            val fallbackMetadata = fallbackCandidates.associateBy { it.video.id }
            val fallbackRanked =
                FlowNeuroEngine.shareByInterest(
                    demoteByFit(
                        applyGraphBoost(
                            FlowNeuroEngine.rank(
                                fallbackCandidates.map { it.video },
                                userSubs,
                                NeuroScoring.LANE_RELEVANCE_FLOOR,
                                session.snapshotTrace,
                            ),
                            fallbackMetadata,
                        ),
                        taste,
                    ),
                )
            val fallbackStartIndex = page.size
            addUniquePageVideos(
                candidates = fallbackRanked,
                targetList = page,
                channelCounts = channelCounts,
                usedVideoIds = pageIds,
                targetSize = HOME_LOAD_MORE_PAGE_SIZE,
            )
            val fallbackAdded = page.drop(fallbackStartIndex).mapTo(HashSet()) { it.id }
            session.relatedPickIds += fallbackAdded
            relatedTo += relatedSeedTitles(fallbackCandidates, fallbackFetch.seedInputs, fallbackAdded)
            persistentHomeFeedCache.saveReserve(
                cacheRelatedCandidates(fallbackRanked, fallbackMetadata, pageIds),
            )
            Log.d(HOME_LOG_TAG, "Load-more fallback: ${fallbackSeeds.size} feed/saved seeds → page=${page.size}")
        }
    }

    /**
     * The load-more seed universe: saved interests (history, liked, playlists)
     * PLUS the feed itself — so paging can always dig another related lane and
     * the feed never runs dry. The engine's seed cooldown rotates them.
     */
    private suspend fun loadMoreSeedInputs(): List<GraphSeedInput> =
        (
            savedInterestSeedInputs(feedSources.gatherSavedSeedSources(), emptySet()) +
                feedSeedInputs(uiState.value.videos, System.currentTimeMillis(), FEED_SEED_POOL, session.relatedPickIds)
        ).distinctBy { it.id }
}
