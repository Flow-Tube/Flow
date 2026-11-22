package io.github.aedev.flow.ui.screens.home

import android.util.Log
import io.github.aedev.flow.data.feed.FeedPrefetchQueue
import io.github.aedev.flow.data.local.HomeFeedCacheRepository
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.recommendation.NeuroScoring
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Cards a load-more attempt found but did not show yet, with why each is there. */
internal class HeldPage(
    val videos: List<Video>,
    val origins: Map<String, HomeCardOrigin>,
)

internal class PrefetchAttempt(
    val outcome: LoadMoreOutcome,
    val held: HeldPage? = null,
)

internal class HomeLoadMorePages(
    private val session: HomeFeedSession,
    private val homePrefetchQueue: FeedPrefetchQueue,
    private val sources: HomeLoadMoreSources,
    private val enricher: HomeChannelEnricher,
    private val playerPreferences: PlayerPreferences,
    private val persistentHomeFeedCache: HomeFeedCacheRepository,
) {
    private companion object {
        // Next pages of earlier searches a short load-more page may fetch.
        const val DEEPER_SEARCH_PAGES = 2
    }

    private val uiState = session.uiState

    /**
     * One load-more attempt. Cards [held] from the run's earlier attempts start the page, and a
     * page too small to show is handed back held unless this is the [lastAttempt].
     */
    suspend fun loadNextPrefetchPage(
        generation: Int,
        held: HeldPage?,
        lastAttempt: Boolean,
    ): PrefetchAttempt {
        try {
            val now = System.currentTimeMillis()
            val userSubs = session.subscriptionScope().boosted
            val brain = FlowNeuroEngine.getBrainSnapshot()
            val taste = feedTasteProfile(brain, FlowNeuroEngine.getPersona(brain))
            val currentIds =
                uiState.value.videos
                    .map { it.id }
                    .toHashSet()
            val page = held?.videos.orEmpty().toMutableList()
            // Channels the viewer just scrolled past count as used, so a new page never repeats them.
            val channelCounts =
                (uiState.value.videos.takeLast(RECENT_CHANNEL_WINDOW) + page)
                    .filter { it.channelId.isNotBlank() }
                    .associateTo(HashMap()) { it.channelId to 1 }
            val pageIds = HashSet<String>(currentIds)
            page.mapTo(pageIds) { it.id }
            val relatedTo = HashMap<String, String>()
            val queries = HashMap<String, String>()
            val requestFailed = AtomicBoolean(false)

            val reserveSearchIds = HashSet<String>()

            fun origins() =
                pageOrigins(
                    page,
                    {
                        when (it) {
                            in session.relatedPickIds -> FeedSource.RELATED
                            in reserveSearchIds -> FeedSource.DISCOVERY
                            else -> null
                        }
                    },
                    relatedTo,
                    queries,
                    userSubs,
                ) + held?.origins.orEmpty()

            suspend fun finish(): PrefetchAttempt =
                when (val outcome = loadMoreOutcome(page.size, lastAttempt, requestFailed.get())) {
                    LoadMoreOutcome.APPENDED -> {
                        val appended = appendLoadMorePage(page, generation, origins())
                        appended?.let { persistentHomeFeedCache.saveLastFeed(it) }
                        PrefetchAttempt(if (appended != null) LoadMoreOutcome.APPENDED else LoadMoreOutcome.EXHAUSTED)
                    }

                    LoadMoreOutcome.HELD -> {
                        PrefetchAttempt(outcome, HeldPage(page.toList(), origins()))
                    }

                    else -> {
                        PrefetchAttempt(outcome)
                    }
                }

            sources.fillPageFromChannelMemory(page, channelCounts, pageIds, userSubs, now)

            val reserveAdded = sources.fillPageFromReserve(page, channelCounts, pageIds, userSubs, now, reserveSearchIds)
            reserveSearchIds.forEach { id -> session.searchPages.queryOf(id)?.let { queries[id] = it } }
            if (page.size >= HOME_LOAD_MORE_PAGE_SIZE) {
                Log.d(HOME_LOG_TAG, "Load-more filled from reserve: +$reserveAdded")
                return finish()
            }

            val graphSeedCount = sources.fillPageFromRelatedGraph(page, channelCounts, pageIds, now, userSubs, taste, brain, relatedTo)
            if (page.size >= HOME_LOAD_MORE_PAGE_SIZE) {
                Log.d(HOME_LOG_TAG, "Load-more filled from reserve/graph: reserve=$reserveAdded graphSeeds=$graphSeedCount")
                return finish()
            }

            if (session.currentQueryIndex >= session.discoveryQueries.size) {
                session.discoveryQueries.addAll(unaskedQueries(FlowNeuroEngine.generateDiscoveryQueries(), session.discoveryQueries))
            }

            // The next page of a search already filling Home takes one of the two searches.
            val continued = nextSearchPage(requestFailed) { session.searchPages.nextPageOfProductive(uiState.value.origins) }
            val queryA = session.discoveryQueries.getOrNull(session.currentQueryIndex++)
            val queryB = if (continued == null) session.discoveryQueries.getOrNull(session.currentQueryIndex++) else null

            val searchQueries = listOfNotNull(queryA, queryB)

            val searches =
                listOfNotNull(continued) +
                    coroutineScope {
                        searchQueries
                            .map { query ->
                                async {
                                    query to (
                                        withTimeoutOrNull(HOME_SEARCH_TIMEOUT_MS) {
                                            try {
                                                session.searchPages.firstPage(query)
                                            } catch (cancellation: CancellationException) {
                                                throw cancellation
                                            } catch (error: Exception) {
                                                Log.d(HOME_LOG_TAG, "Prefetch query failed for $query: ${error.message}")
                                                requestFailed.set(true)
                                                emptyList()
                                            }
                                        } ?: emptyList<Video>().also { requestFailed.set(true) }
                                    )
                                }
                            }.awaitAll()
                    }
            queries += queriesById(searches)
            val rawVideos = searches.flatMap { it.second }
            if (!homePrefetchQueue.isCurrent(generation)) return PrefetchAttempt(LoadMoreOutcome.EXHAUSTED)

            // Extract shorts for shelf — rank through FlowNeuro
            val moreShorts =
                rawVideos
                    .extractShorts()
                    .filterWatched(session.watchedShortIds.value)
                    .filterRecentHomeSuggestion(now)
                    .filterNot(session.feedExclusions()::hidesFromRecommendations)
            if (moreShorts.isNotEmpty() && playerPreferences.effectiveHomeShortsShelfEnabled.first()) {
                val rankedMore =
                    FlowNeuroEngine
                        .rank(moreShorts, userSubs)
                        .recentlyShownLast { NeuroScoring.isRecentlySeen(brain.feedHistory[it], now) }
                uiState.update { state ->
                    state.copy(shorts = (state.shorts + rankedMore).distinctBy { it.id })
                }
            }

            suspend fun addDiscovery(videos: List<Video>) {
                val newVideos =
                    videos
                        .filterValid()
                        .filterWatched(session.watchedVideoIds.value)
                        .filterRecentHomeSuggestion(now)
                if (newVideos.isEmpty()) return
                val rankedDiscovery =
                    FlowNeuroEngine
                        .shareByInterest(
                            demoteByFit(
                                FlowNeuroEngine.rank(newVideos, userSubs, NeuroScoring.LANE_RELEVANCE_FLOOR, session.snapshotTrace),
                                taste,
                            ),
                        ).let { spreadAcrossSearches(it, queries::get, it.size) }
                addUniquePageVideos(
                    candidates = rankedDiscovery,
                    targetList = page,
                    channelCounts = channelCounts,
                    usedVideoIds = pageIds,
                    targetSize = HOME_LOAD_MORE_PAGE_SIZE,
                )
                persistentHomeFeedCache.saveReserve(
                    cacheCandidates(FeedSource.DISCOVERY, rankedDiscovery, pageIds),
                )
            }
            addDiscovery(rawVideos)

            // Still short: the next pages of searches already made, before any wider related pass (#907).
            repeat(DEEPER_SEARCH_PAGES) {
                if (page.size >= HOME_LOAD_MORE_PAGE_SIZE || !homePrefetchQueue.isCurrent(generation)) return@repeat
                val (query, deeper) = nextSearchPage(requestFailed) { session.searchPages.nextPageOfAny() } ?: return@repeat
                queries += queriesById(listOf(query to deeper))
                addDiscovery(deeper)
            }

            if (page.size < HOME_LOAD_MORE_PAGE_SIZE && session.subsBacklog.isNotEmpty()) {
                val exclusions = session.feedExclusions()
                session.subsBacklog =
                    session.subsBacklog.filterNot {
                        exclusions.hidesFromRecommendations(it) || NeuroScoring.isRecentlySeen(brain.feedHistory[it.id], now)
                    }
                addUniquePageVideos(
                    candidates = session.subsBacklog,
                    targetList = page,
                    channelCounts = channelCounts,
                    usedVideoIds = pageIds,
                    targetSize = HOME_LOAD_MORE_PAGE_SIZE,
                )
                session.subsBacklog = session.subsBacklog.filterNot { pageIds.contains(it.id) }
            }

            // Never run dry: when every other source thinned out, escalate with a
            // wider related pass — more seeds, drawn from the feed itself and saved
            // interests. The engine's seed cooldown keeps the lanes rotating, and
            // its scarcity fallback re-admits cooled seeds when the pool is thin.
            if (page.size < HOME_LOAD_MORE_PAGE_SIZE) {
                sources.fillPageFromFallbackGraph(page, channelCounts, pageIds, now, userSubs, taste, relatedTo) {
                    homePrefetchQueue.isCurrent(generation)
                }
            }

            return finish()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.d(HOME_LOG_TAG, "Home prefetch page failed: ${error.message}")
            return PrefetchAttempt(LoadMoreOutcome.FAILED)
        }
    }

    /** The next page [fetch] picks, or null when there is none or its request failed, which [failed] records. */
    private suspend fun nextSearchPage(
        failed: AtomicBoolean,
        fetch: suspend () -> Pair<String, List<Video>>?,
    ): Pair<String, List<Video>>? {
        val attempt =
            withTimeoutOrNull(HOME_SEARCH_TIMEOUT_MS) {
                try {
                    Result.success(fetch())
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    Log.d(HOME_LOG_TAG, "Next search page failed: ${error.message}")
                    Result.failure(error)
                }
            }
        if (attempt == null || attempt.isFailure) failed.set(true)
        return attempt?.getOrNull()
    }

    private suspend fun appendLoadMorePage(
        page: List<Video>,
        generation: Int,
        origins: Map<String, HomeCardOrigin>,
    ): List<Video>? {
        if (page.isEmpty() || !homePrefetchQueue.isCurrent(generation)) return null
        val exclusions = session.feedExclusions()
        val followed = session.subscriptionScope().boosted
        var updatedSnapshot: List<Video>? = null
        var appendedPage = emptyList<Video>()
        uiState.update { state ->
            if (!homePrefetchQueue.isCurrent(generation)) return@update state
            val existingVideoIds = state.videos.mapTo(HashSet()) { it.id }
            appendedPage =
                page
                    .filterWatched(session.watchedVideoIds.value)
                    .filterNot { it.id in existingVideoIds || exclusions.hidesFromRecommendations(it) }
            if (appendedPage.isEmpty()) return@update state
            val tailChannels = state.videos.takeLast(CHANNEL_GAP).map { it.channelId }
            val updated =
                state.videos +
                    spaceByChannel(
                        rhythmBySource(appendedPage, isSubscribed = { it.channelId in followed }),
                        gap = CHANNEL_GAP,
                        seedRecent = tailChannels,
                    )
            updatedSnapshot = updated
            HomeFeedCache.update(updated, state.shorts)
            val appendedIds = appendedPage.mapTo(HashSet()) { it.id }
            state.copy(
                videos = updated,
                hasMorePages = true,
                origins = state.origins + origins.filterKeys { it in appendedIds },
            )
        }
        if (appendedPage.isEmpty()) return null
        Log.d(HOME_LOG_TAG, "Load-more appended ${appendedPage.size} cards")
        return enricher.enrichVisibleChannelMetadata(appendedPage) ?: updatedSnapshot
    }
}
