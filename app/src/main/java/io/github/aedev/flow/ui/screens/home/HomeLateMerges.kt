package io.github.aedev.flow.ui.screens.home

import android.util.Log
import io.github.aedev.flow.data.local.HomeFeedCacheRepository
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.ChannelMemoryRepository
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.recommendation.NeighbourChannel
import io.github.aedev.flow.data.recommendation.NeighbourChannelsRepository
import io.github.aedev.flow.data.recommendation.NeuroScoring
import io.github.aedev.flow.data.subscriptions.HomeSubscriptionUploads
import io.github.aedev.flow.data.subscriptions.SubscriptionFeedRepository
import io.github.aedev.flow.innertube.pages.renderer.FeedItemOwner
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class HomeLateMerges(
    private val scope: CoroutineScope,
    private val session: HomeFeedSession,
    private val subscriptionRepository: SubscriptionRepository,
    private val subscriptionFeedRepository: SubscriptionFeedRepository,
    private val homeSubscriptionUploads: HomeSubscriptionUploads,
    private val channelMemory: ChannelMemoryRepository,
    private val neighbourChannels: NeighbourChannelsRepository,
    private val feedSources: HomeFeedSources,
    private val persistentHomeFeedCache: HomeFeedCacheRepository,
) {
    private companion object {
        const val MAX_SAVED_SEEDS = 5
        const val SAVED_RELATED_SLOTS = 8
        const val LATE_SUBS_MAX = 8
        const val LATE_SUBS_PER_CHANNEL = 5

        // Before the grid reports its viewport the first screen is still in view; never shift it.
        const val LATE_SUBS_MIN_INDEX = 5
    }

    private val uiState = session.uiState
    private var subsTopUpJob: Job? = null
    private var channelMemoryJob: Job? = null
    private var neighbourJob: Job? = null
    private var savedInterestJob: Job? = null

    fun cancelStoreRefreshes() {
        subsTopUpJob?.cancel()
        channelMemoryJob?.cancel()
    }

    fun cancelSavedInterest() {
        savedInterestJob?.cancel()
    }

    /**
     * Tops the subscription store up from channel tabs after first paint, then adds what it found
     * below the cards on screen. One at a time; a reload cancels it, so a changed setting cannot be
     * undone by a late merge.
     */
    fun startSubscriptionTopUp(
        userSubs: Set<String>,
        storedFeed: List<Video>,
    ) {
        if (userSubs.isEmpty() || subsTopUpJob?.isActive == true) return
        subsTopUpJob =
            scope.launch(PerformanceDispatcher.networkIO) {
                val uploads =
                    try {
                        homeSubscriptionUploads.fetch(
                            subscriptions = subscriptionOwners(userSubs),
                            priorityChannelIds = storedFeed.channelsMissingLengths(System.currentTimeMillis()),
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        Log.d(HOME_LOG_TAG, "Subscription top-up failed: ${error.message}")
                        emptyList()
                    }
                if (uploads.isEmpty()) return@launch
                // Passive channel profiling: upload titles teach the engine what each channel is about.
                runCatching { FlowNeuroEngine.onChannelUploadsObserved(uploads) }
                subscriptionFeedRepository.mergeChannelTabUploads(uploads)
                mergeLateSubscriptions(uploads, userSubs)
            }
    }

    private suspend fun mergeLateSubscriptions(
        uploads: List<Video>,
        userSubs: Set<String>,
    ) {
        val exclusions = session.feedExclusions()
        val videos = uploads.filterNot(exclusions::hidesFromRecommendations)
        val onScreen = uiState.value.videos.mapTo(HashSet()) { it.id }
        val candidates =
            videos
                .filterValid()
                .filterWatched(session.watchedVideoIds.value)
                .filterNot { it.id in onScreen }
                .groupBy { it.channelId }
                .values
                .flatMap { uploads -> uploads.sortedByDescending { it.timestamp }.take(LATE_SUBS_PER_CHANNEL) }
        val late =
            FlowNeuroEngine
                .rank(candidates, userSubs)
                .distinctBy { it.channelId }
                .take(LATE_SUBS_MAX)
        var merged: List<Video>? = null
        var added = 0
        uiState.update { state ->
            if (state.isLoading || state.videos.isEmpty()) return@update state
            val below = session.lastVisibleVideoIndex.coerceAtLeast(LATE_SUBS_MIN_INDEX)
            val updated =
                insertBelowViewport(
                    state.videos,
                    late.filterWatched(session.watchedVideoIds.value),
                    below,
                    isGrouped = { it.channelId in userSubs },
                )
            if (updated.size == state.videos.size) return@update state
            added = updated.size - state.videos.size
            merged = updated
            HomeFeedCache.update(updated, state.shorts)
            state.copy(videos = updated, origins = state.origins + late.associate { it.id to HomeCardOrigin.subscription })
        }
        merged?.let {
            persistentHomeFeedCache.saveLastFeed(it)
            Log.d(HOME_LOG_TAG, "Subscription top-up added $added uploads below the viewport")
        }
    }

    /**
     * Refreshes channel memory after first paint and slips new uploads in below the viewport,
     * within the lane's quota and one per channel.
     */
    fun startChannelMemoryRefresh(userSubs: Set<String>) {
        if (channelMemoryJob?.isActive == true) return
        channelMemoryJob =
            scope.launch(PerformanceDispatcher.networkIO) {
                val fresh =
                    try {
                        channelMemory.refresh()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        Log.d(HOME_LOG_TAG, "Channel memory refresh failed: ${error.message}")
                        emptyList()
                    }
                if (fresh.isEmpty()) return@launch
                val now = System.currentTimeMillis()
                val brain = FlowNeuroEngine.getBrainSnapshot()
                val exclusions = session.feedExclusions()
                val ranked =
                    FlowNeuroEngine.rank(
                        fresh
                            .filterValid()
                            .filterWatched(session.watchedVideoIds.value)
                            .filterNot {
                                exclusions.hidesFromRecommendations(it) ||
                                    NeuroScoring.isRecentlySeen(brain.feedHistory[it.id], now)
                            },
                        userSubs,
                    )
                var added = 0
                uiState.update { state ->
                    if (state.isLoading || state.videos.isEmpty()) return@update state
                    val memoryOnScreen = state.videos.filter { state.origins[it.id]?.kind == HomeCardOrigin.Kind.CHANNEL_MEMORY }
                    val late =
                        channelMemoryPicks(
                            ranked = ranked,
                            onScreenIds = state.videos.mapTo(HashSet()) { it.id },
                            onScreenMemoryChannels = memoryOnScreen.mapTo(HashSet()) { it.channelId },
                            room = session.channelMemoryQuota - memoryOnScreen.size,
                        )
                    val below = session.lastVisibleVideoIndex.coerceAtLeast(LATE_SUBS_MIN_INDEX)
                    val updated = insertBelowViewport(state.videos, late, below)
                    added = updated.size - state.videos.size
                    if (added == 0) return@update state
                    HomeFeedCache.update(updated, state.shorts)
                    state.copy(videos = updated, origins = state.origins + late.associate { it.id to HomeCardOrigin.channelMemory })
                }
                if (added > 0) Log.d(HOME_LOG_TAG, "Channel memory added $added uploads below the viewport")
            }
    }

    /**
     * After paint: fetches the newest uploads of neighbour channels for the next load, and lets two
     * recent uploads of followed channels teach which channels sit beside them (#907).
     */
    fun startNeighbourRefresh(
        neighbours: List<NeighbourChannel>,
        offTopic: Set<String>,
        subscriptionFeed: List<Video>,
        followed: Set<String>,
    ) {
        neighbourJob?.cancel()
        neighbourJob =
            scope.launch(PerformanceDispatcher.networkIO) {
                runCatching { neighbourChannels.dropOffTopic(offTopic) }.onFailure { if (it is CancellationException) throw it }
                runCatching { neighbourChannels.refresh(neighbours.filter { it.channelId !in offTopic }) }
                    .onFailure {
                        if (it is CancellationException) {
                            throw it
                        } else {
                            Log.d(
                                HOME_LOG_TAG,
                                "Neighbour refresh failed: ${it.message}",
                            )
                        }
                    }
                runCatching { feedSources.learnFromFollowed(subscriptionFeed, followed, session::cacheFilters) }
                    .onFailure {
                        if (it is CancellationException) {
                            throw it
                        } else {
                            Log.d(
                                HOME_LOG_TAG,
                                "Followed co-watch failed: ${it.message}",
                            )
                        }
                    }
            }
    }

    /**
     * Enriches the feed with related neighbours of the videos the user saved/watched, on top of the
     * lane quotas. Runs after first paint so it never delays load; chosen seeds enter a cooldown.
     */
    fun enrichFeedWithSavedInterest(
        userSubs: Set<String>,
        taste: FeedTasteProfile,
    ) {
        savedInterestJob?.cancel()
        savedInterestJob =
            scope.launch(PerformanceDispatcher.networkIO) {
                try {
                    val now = System.currentTimeMillis()
                    val cooldown = feedSources.activeSavedSeedCooldown(now)
                    val seedInputs = savedInterestSeedInputs(feedSources.gatherSavedSeedSources(), cooldown)
                    val longTermInputs = feedSources.longTermSeedInputs().filterNot { it.id in cooldown }
                    val seeds = FlowNeuroEngine.selectRelatedSeeds(seedInputs, MAX_SAVED_SEEDS, longTermInputs)
                    if (seeds.isEmpty()) return@launch
                    feedSources.markSeedsUsed(seeds, now)

                    val exclusions = session.feedExclusions()
                    val relatedCandidates =
                        feedSources
                            .fetchRelatedGraphCandidates(seedInputs + longTermInputs, seeds, session::cacheFilters)
                            .filterValidGraph()
                            .filterWatchedGraph(session.watchedVideoIds.value)
                            .filterRecentHomeSuggestionGraph(now)
                            .filterNot { exclusions.hidesFromRecommendations(it.video) }
                    if (relatedCandidates.isEmpty()) return@launch

                    val existing = uiState.value.videos.mapTo(HashSet()) { it.id }
                    val relatedMetadata = relatedCandidates.associateBy { it.video.id }
                    val enriched =
                        FlowNeuroEngine
                            .shareByInterest(
                                demoteByFit(
                                    applyGraphBoost(
                                        FlowNeuroEngine.rank(
                                            relatedCandidates
                                                .map { it.video }
                                                .filterNot { existing.contains(it.id) },
                                            userSubs,
                                            NeuroScoring.LANE_RELEVANCE_FLOOR,
                                            session.snapshotTrace,
                                        ),
                                        relatedMetadata,
                                    ),
                                    taste,
                                ),
                            ).take(SAVED_RELATED_SLOTS)
                    if (enriched.isEmpty()) return@launch
                    enriched.mapTo(session.relatedPickIds) { it.id }

                    uiState.update { state ->
                        val recentChannels = state.videos.takeLast(RECENT_CHANNEL_WINDOW).mapTo(HashSet()) { it.channelId }
                        val visibleEnriched =
                            enriched
                                .filterWatched(session.watchedVideoIds.value)
                                .filter { it.channelId.isBlank() || it.channelId !in recentChannels }
                                .distinctBy { it.channelId.ifBlank { it.id } }
                        val tail = state.videos.takeLast(CHANNEL_GAP).map { it.channelId }
                        val merged = state.videos + spaceByChannel(visibleEnriched, gap = CHANNEL_GAP, seedRecent = tail)
                        HomeFeedCache.update(merged, state.shorts)
                        val shownIds = visibleEnriched.mapTo(HashSet()) { it.id }
                        val seedTitles = relatedSeedTitles(relatedCandidates, seedInputs + longTermInputs, shownIds)
                        state.copy(
                            videos = merged,
                            origins = state.origins + shownIds.associateWith { HomeCardOrigin.related(seedTitles[it]) },
                        )
                    }
                    Log.d(HOME_LOG_TAG, "Saved-interest enrichment: +${enriched.size} from ${seeds.size} seeds")
                } catch (e: Exception) {
                    Log.d(HOME_LOG_TAG, "Saved-interest enrichment failed: ${e.message}")
                }
            }
    }

    private suspend fun subscriptionOwners(channelIds: Set<String>): List<FeedItemOwner> =
        subscriptionRepository
            .getAllSubscriptions()
            .first()
            .filter { it.channelId in channelIds }
            .map { FeedItemOwner(id = it.channelId, name = it.channelName, avatarUrl = it.channelThumbnail) }
}
