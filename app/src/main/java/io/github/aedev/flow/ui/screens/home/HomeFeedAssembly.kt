package io.github.aedev.flow.ui.screens.home

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FeedExclusions

internal const val FRESH_UPLOAD_MAX_SIGHTINGS = 3
internal const val HOME_TARGET_SIZE = 40

// No channel twice within this many cards, across pages and late arrivals.
internal const val RECENT_CHANNEL_WINDOW = 20

// Repeats a short page could not avoid sit at least this far apart.
internal const val CHANNEL_GAP = 10

// Followed-channel videos past the first page's rhythm wait for later pages while it keeps this many.
internal const val FIRST_PAGE_MIN_SIZE = 30

// Fresh subs pinned to the very top; the rest interleave via the SUBS lane.
private const val FRESH_SUBS_PIN_TOP = 2

// Seen fresh uploads follow this many ranked subs: on the first page, but not the first card again.
private const val SHOWN_FRESH_AFTER_SUBS = 3

private const val BEST_SUBS_LIMIT = 15
private const val BEST_DISCOVERY_LIMIT = 15
private const val BEST_MEMORY_LIMIT = 6
private const val BEST_NEIGHBOUR_LIMIT = 4
private const val BEST_RELATED_LIMIT = 12

internal fun List<Video>.enrichAvatars(subAvatarMap: Map<String, String>): List<Video> =
    if (subAvatarMap.isEmpty()) {
        this
    } else {
        map { v ->
            if (v.channelThumbnailUrl.isEmpty() && subAvatarMap.containsKey(v.channelId)) {
                v.copy(
                    channelThumbnailUrl = subAvatarMap.getValue(v.channelId),
                    channelThumbnailUrls =
                        v.channelThumbnailUrls.ifEmpty {
                            listOf(subAvatarMap.getValue(v.channelId))
                        },
                )
            } else {
                v
            }
        }
    }

internal data class HomeFeedLanes(
    val pinnedFresh: List<Video>,
    val overflowFresh: List<Video>,
    val shownFresh: List<Video>,
    val bestSubs: List<Video>,
    val bestDiscovery: List<Video>,
    val bestMemory: List<Video>,
    val bestNeighbours: List<Video>,
    /** Neighbour channels with at least one upload on the viewer's subjects; the others are dropped. */
    val relevantNeighbourChannels: Set<String>,
    val bestRelated: List<Video>,
    val relatedCandidates: List<GraphCandidate>,
    val relatedMetadata: Map<String, GraphCandidate>,
    val subsByRecency: List<Video>,
    val subsPoolSize: Int,
    val discoveryPoolSize: Int,
    val memoryPoolSize: Int,
) {
    val freshCandidates: Sequence<Video>
        get() =
            pinnedFresh.asSequence() + overflowFresh.asSequence() + bestSubs.asSequence() + shownFresh.asSequence() +
                bestRelated.asSequence() + bestDiscovery.asSequence() + bestMemory.asSequence() + bestNeighbours.asSequence()
}

/**
 * Turns the raw fetch results into the ranked lanes the blend draws from.
 *
 * [rank] is the engine call, passed in so the whole pipeline can be exercised without one.
 * [rankRelevant] ranks the discovery and related lanes and may drop candidates that share nothing
 * with the viewer's taste; subscriptions and remembered channels use [rank]. [share] then spreads
 * those two lanes over the viewer's interests before they are cut to length.
 */
internal suspend fun buildHomeFeedLanes(
    rawSubs: List<Video>,
    rawDiscovery: List<Video>,
    rawMemory: List<Video>,
    rawRelated: List<GraphCandidate>,
    rssFeed: List<Video>,
    watched: Set<String>,
    exclusions: FeedExclusions,
    isRecentlyShown: (videoId: String) -> Boolean,
    timesShown: (videoId: String) -> Int = { 0 },
    taste: FeedTasteProfile,
    now: Long,
    freshSlotTarget: Int,
    subAvatarMap: Map<String, String>,
    rank: suspend (List<Video>) -> List<Video>,
    rankRelevant: suspend (List<Video>) -> List<Video> = rank,
    share: suspend (List<Video>) -> List<Video> = { it },
    rawNeighbours: List<Video> = emptyList(),
    searchOf: (videoId: String) -> String? = { null },
): HomeFeedLanes {
    // The fresh-subs lane and the subs backlog bypass rank(), so hidden videos, channels and
    // topics are dropped here or they would resurface through them.
    val subsPool =
        rawSubs
            .distinctBy { it.id }
            .filterValid()
            .filterWatched(watched)
            .filterNot(exclusions::hidesFromRecommendations)
            .enrichAvatars(subAvatarMap)
    val discoveryPool =
        rawDiscovery
            .filterValid()
            .filterWatched(watched)
            .filterRecentHomeSuggestion(now)
            .filterNot(exclusions::hidesFromRecommendations)
    val memoryPool =
        rawMemory
            .filterValid()
            .filterWatched(watched)
            .filterRecentHomeSuggestion(now)
            .filterNot(exclusions::hidesFromRecommendations)
    val neighbourPool =
        rawNeighbours
            .filterValid()
            .filterWatched(watched)
            .filterRecentHomeSuggestion(now)
            .filterNot(exclusions::hidesFromRecommendations)

    val subsByRecency = subsPool.sortedByDescending { it.timestamp }

    // Fresh-subs lane is RSS-FIRST: the subscription feed store covers ALL subscribed channels
    // with real publish timestamps, so a fresh upload is visible even when its channel missed
    // this refresh's rotating 10-18 channel fetch window.
    val rssFresh =
        rssFeed
            .asSequence()
            .filter { !it.isShort && !it.isUpcoming && (it.duration > 0 || it.isLive) }
            .filter { (now - it.timestamp) in 0..FRESH_SUB_WINDOW_MS }
            .filterNot(exclusions::hidesFromRecommendations)
            .toList()
    val freshSubsLane =
        (rssFresh + subsByRecency.filter { isFreshSubscribedCandidate(it, now) })
            .filterWatched(watched)
            // Passed this often without a tap, a fresh upload has had its chance on Home (#1565).
            .filterNot { timesShown(it.id) >= FRESH_UPLOAD_MAX_SIGHTINGS }
            .distinctBy { it.id }
            .sortedByDescending { it.timestamp }
            // One fresh slot per channel — a channel that uploaded three times today must not
            // occupy three fresh slots.
            .distinctBy { it.channelId.ifBlank { it.id } }
    // An upload already shown recently stays on Home but goes to the back of the SUBS lane, so the
    // same card is not pinned to the top of every launch for three days. Splitting before the cap
    // lets the next unseen upload take the slot.
    val (shownFresh, unshownFresh) =
        freshSubsLane
            .partition { isRecentlyShown(it.id) }
            .let { (shown, unshown) -> shown.take(freshSlotTarget) to unshown.take(freshSlotTarget) }
    val freshIds = (shownFresh + unshownFresh).mapTo(HashSet()) { it.id }

    val rankedSubs = rank(subsPool)
    val bestSubs =
        rankedSubs
            .filter { !freshIds.contains(it.id) }
            .take(BEST_SUBS_LIMIT)

    val relatedCandidates =
        rawRelated
            .filterValidGraph()
            .filterWatchedGraph(watched)
            .filterRecentHomeSuggestionGraph(now)
            .filterNot { exclusions.hidesFromRecommendations(it.video) }
    val relatedPool = relatedCandidates.map { it.video }
    val relatedMetadata = relatedCandidates.associateBy { it.video.id }

    // Neighbours are new to the viewer, so they meet the relevance floor like discovery does.
    val relevantNeighbours = if (neighbourPool.isEmpty()) emptyList() else rankRelevant(neighbourPool)

    return HomeFeedLanes(
        // Only a couple of fresh subs are pinned to the very top; the rest ride the SUBS lane so
        // the first screen is a real source MIX instead of a wall of subscriptions.
        pinnedFresh = unshownFresh.take(FRESH_SUBS_PIN_TOP),
        overflowFresh = unshownFresh.drop(FRESH_SUBS_PIN_TOP),
        shownFresh = shownFresh,
        bestSubs = bestSubs,
        bestDiscovery = spreadAcrossSearches(share(demoteByFit(rankRelevant(discoveryPool), taste)), searchOf, BEST_DISCOVERY_LIMIT),
        // One upload per remembered channel: the lane spreads across channels, never floods with one.
        bestMemory =
            demoteByFit(rank(memoryPool), taste)
                .distinctBy { it.channelId }
                .take(BEST_MEMORY_LIMIT),
        bestNeighbours =
            demoteByFit(relevantNeighbours, taste)
                .distinctBy { it.channelId }
                .take(BEST_NEIGHBOUR_LIMIT),
        relevantNeighbourChannels = relevantNeighbours.mapTo(HashSet()) { it.channelId },
        bestRelated =
            share(
                demoteByFit(
                    applyGraphBoost(rankRelevant(relatedPool), relatedMetadata),
                    taste,
                ),
            ).take(BEST_RELATED_LIMIT),
        relatedCandidates = relatedCandidates,
        relatedMetadata = relatedMetadata,
        subsByRecency = subsByRecency,
        subsPoolSize = subsPool.size,
        discoveryPoolSize = discoveryPool.size,
        memoryPoolSize = memoryPool.size,
    )
}

internal data class HomeFeedMix(
    val videos: List<Video>,
    val sourceMix: FeedMixResult,
    val selectedSourceCounts: Map<FeedSource, Int>,
    val quotas: Map<FeedSource, Int>,
    val freshAdded: Int,
    val subsBacklog: List<Video>,
)

/**
 * Fills the feed from the lanes: pinned fresh first, then the quota blend.
 *
 * [onScreenIds] is excluded so a refresh produces a visibly different feed, but only while the
 * lanes are deep enough to still fill half the target without them.
 */
internal fun assembleHomeFeed(
    lanes: HomeFeedLanes,
    onScreenIds: Set<String>,
    subCount: Int,
    totalInteractions: Int,
    targetSize: Int = HOME_TARGET_SIZE,
): HomeFeedMix {
    val finalMix = mutableListOf<Video>()
    val usedChannelCounts = mutableMapOf<String, Int>()
    val usedVideoIds = mutableSetOf<String>()
    var freshAdded = 0

    if (onScreenIds.isNotEmpty()) {
        val freshCandidateCount = lanes.freshCandidates.distinctBy { it.id }.count { it.id !in onScreenIds }
        if (freshCandidateCount >= targetSize / 2) {
            usedVideoIds += onScreenIds
        }
    }

    lanes.pinnedFresh.forEach { video ->
        if (addUniqueVideo(video, finalMix, usedChannelCounts, usedVideoIds)) freshAdded++
    }

    val remaining = (targetSize - finalMix.size).coerceAtLeast(0)
    val quotas = homeFeedQuotas(remaining, subCount, totalInteractions, hasNeighbours = lanes.bestNeighbours.isNotEmpty())
    val sourceMix =
        blendFeedSources(
            lanes =
                mapOf(
                    FeedSource.SUBS to
                        lanes.overflowFresh + lanes.bestSubs.take(SHOWN_FRESH_AFTER_SUBS) + lanes.shownFresh +
                        lanes.bestSubs.drop(SHOWN_FRESH_AFTER_SUBS),
                    FeedSource.RELATED to lanes.bestRelated,
                    FeedSource.DISCOVERY to lanes.bestDiscovery,
                    FeedSource.CHANNEL_MEMORY to lanes.bestMemory,
                    FeedSource.NEIGHBOURS to lanes.bestNeighbours,
                ),
            quotas = quotas,
            targetSize = remaining,
            channelCounts = usedChannelCounts,
            usedVideoIds = usedVideoIds,
            singleChannels = (lanes.bestMemory + lanes.bestNeighbours).mapTo(HashSet()) { it.channelId },
        )
    finalMix += sourceMix.videos

    return HomeFeedMix(
        videos = finalMix,
        sourceMix = sourceMix,
        selectedSourceCounts =
            sourceMix.sourceCounts.toMutableMap().also { counts ->
                counts[FeedSource.SUBS] = (counts[FeedSource.SUBS] ?: 0) + freshAdded
            },
        quotas = quotas,
        freshAdded = freshAdded,
        subsBacklog = lanes.subsByRecency.filterNot { usedVideoIds.contains(it.id) },
    )
}
