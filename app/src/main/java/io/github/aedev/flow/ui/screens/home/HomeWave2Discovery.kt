package io.github.aedev.flow.ui.screens.home

import android.util.Log
import io.github.aedev.flow.data.local.HomeFeedCacheRepository
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.recommendation.NeuroScoring
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class HomeWave2Discovery(
    private val scope: CoroutineScope,
    private val session: HomeFeedSession,
    private val persistentHomeFeedCache: HomeFeedCacheRepository,
) {
    private val uiState = session.uiState
    private var job: Job? = null

    fun cancel() {
        job?.cancel()
    }

    suspend fun awaitRunning() {
        job?.takeIf { it.isActive }?.join()
    }

    /** Wave 2: the discovery queries wave 1 did not have time for, merged in after paint. */
    fun startWave2Discovery(
        finalMix: List<Video>,
        userSubs: Set<String>,
        taste: FeedTasteProfile,
    ) {
        val wave2Queries = session.discoveryQueries.drop(session.currentQueryIndex)
        // Asked once whatever they return: load-more must not search them again (#907).
        session.currentQueryIndex = session.discoveryQueries.size
        if (wave2Queries.isNotEmpty()) {
            val wave2FinalMixIds = finalMix.map { it.id }.toHashSet()
            val generation = session.feedGeneration
            job =
                scope.launch(PerformanceDispatcher.networkIO) wave2@{
                    try {
                        val wave2Raw =
                            wave2Queries
                                .map { q ->
                                    async {
                                        q to (
                                            withTimeoutOrNull(6_000L) {
                                                try {
                                                    session.searchPages.firstPage(q)
                                                } catch (cancellation: CancellationException) {
                                                    throw cancellation
                                                } catch (error: Exception) {
                                                    Log.d(HOME_LOG_TAG, "Wave 2 query failed for $q: ${error.message}")
                                                    emptyList()
                                                }
                                            } ?: emptyList()
                                        )
                                    }
                                }.awaitAll()

                        reportQueryNovelty(wave2Raw)

                        val wave2Watched = session.watchedVideoIds.value
                        val wave2Valid =
                            wave2Raw
                                .flatMap { it.second }
                                .filterValid()
                                .filterWatched(wave2Watched)
                                .filter { !wave2FinalMixIds.contains(it.id) }
                                .filterNot(session.feedExclusions()::hidesFromRecommendations)
                        if (wave2Valid.isEmpty()) return@wave2

                        val wave2Ranked =
                            FlowNeuroEngine
                                .shareByInterest(
                                    demoteByFit(
                                        FlowNeuroEngine.rank(
                                            wave2Valid,
                                            userSubs,
                                            NeuroScoring.LANE_RELEVANCE_FLOOR,
                                            session.snapshotTrace,
                                        ),
                                        taste,
                                    ),
                                ).take(15)

                        if (wave2Ranked.isNotEmpty()) {
                            var updatedSnapshot: List<Video>? = null
                            uiState.update { state ->
                                val currentIds = state.videos.map { it.id }.toHashSet()
                                val uniqueNew =
                                    wave2Ranked
                                        .filterWatched(session.watchedVideoIds.value)
                                        .filter { !currentIds.contains(it.id) }
                                        .filterNot { video -> state.videos.any { it.channelId == video.channelId } }
                                        .distinctBy { it.channelId }
                                if (uniqueNew.isEmpty()) return@update state
                                val updated = state.videos + uniqueNew
                                updatedSnapshot = updated
                                HomeFeedCache.update(updated, state.shorts)
                                state.copy(
                                    videos = updated,
                                    origins =
                                        state.origins + pageOrigins(uniqueNew, { null }, emptyMap(), queriesById(wave2Raw), userSubs),
                                )
                            }
                            updatedSnapshot?.let { persistentHomeFeedCache.saveLastFeed(it) }
                            Log.d(HOME_LOG_TAG, "Wave 2 merged ${wave2Ranked.size} extra candidates")
                        }
                    } catch (cancellation: CancellationException) {
                        // Leaving Home cancelled it, not a reload: load-more asks them instead.
                        // Any that finished come back from the page cache without a request.
                        if (generation == session.feedGeneration) session.discoveryQueries.addAll(wave2Queries)
                        throw cancellation
                    } catch (error: Exception) {
                        Log.d(HOME_LOG_TAG, "Wave 2 failed: ${error.message}")
                    }
                }
        }
    }
}

/**
 * Reports per-query result novelty to the engine: a query whose results
 * are mostly videos already shown recently gets marked stale and skipped
 * by the next discovery-generation cycle.
 */
internal suspend fun reportQueryNovelty(pairs: List<Pair<String, List<Video>>>) {
    if (pairs.isEmpty()) return
    runCatching {
        val recentlyShown = FlowNeuroEngine.getRecentlyShownVideoIds(48L)
        if (recentlyShown.isEmpty()) return
        pairs.forEach { (query, queryResults) ->
            if (queryResults.size >= 5) {
                val novel = queryResults.count { it.id !in recentlyShown }
                FlowNeuroEngine.reportQueryResultNovelty(
                    query,
                    novel.toDouble() / queryResults.size,
                )
            }
        }
    }
}
