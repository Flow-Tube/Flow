package io.github.aedev.flow.ui.screens.home

import io.github.aedev.flow.data.local.HomeFeedCacheFilters
import io.github.aedev.flow.data.local.HomeFeedCacheRepository
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.recommendation.GraphSeedInput
import io.github.aedev.flow.data.recommendation.GraphSeedSelector
import io.github.aedev.flow.data.recommendation.GraphSeedSource
import io.github.aedev.flow.data.recommendation.NeighbourChannels
import io.github.aedev.flow.data.repository.YouTubeRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

private const val RELATED_TTL_MS = 45L * 60L * 1000L
private const val RELATED_FETCH_CONCURRENCY = 3
private const val RELATED_FETCH_TIMEOUT_MS = 4_000L

// Related lists of followed uploads fetched per Home load to teach co-watch; each upload is used once.
private const val FOLLOWED_SOURCES_PER_LOAD = 2
private const val SAVED_SEED_COOLDOWN_MS = 3L * 60L * 60L * 1000L
private const val LONG_TERM_HISTORY_MAX = 200

internal data class RelatedGraphFetchResult(
    val seedInputs: List<GraphSeedInput>,
    val seedIds: List<String>,
    val candidates: List<GraphCandidate>,
    val fetchedPerSeed: Map<String, Int>,
)

/**
 * Seed discovery and related-graph retrieval for the home feed.
 *
 * Unscoped, so its per-seed caches live and die with the ViewModel that injects it — the same
 * lifetime the fields had when they were members of it.
 */
class HomeFeedSources
    @Inject
    constructor(
        private val repository: YouTubeRepository,
        private val homeFeedCache: HomeFeedCacheRepository,
        private val viewHistory: ViewHistory,
        private val likedVideosRepository: LikedVideosRepository,
        private val playlistRepository: PlaylistRepository,
        private val neuroEngine: FlowNeuroEngine,
    ) {
        private data class CachedRelated(
            val videos: List<Video>,
            val ts: Long,
        )

        private val relatedCache = ConcurrentHashMap<String, CachedRelated>()
        private val relatedSemaphore = Semaphore(RELATED_FETCH_CONCURRENCY)
        private val savedSeedCooldown = ConcurrentHashMap<String, Long>()

        suspend fun historySeedInputs(): List<GraphSeedInput> =
            graphSeedInputsFromHistory(viewHistory.getRecentVideoHistory(HISTORY_SEED_MAX, includeShorts = false))
                .withoutDisliked()

        internal suspend fun gatherSavedSeedSources(): SavedSeedSources {
            val historySeeds =
                runCatching {
                    graphSeedInputsFromHistory(viewHistory.getRecentVideoHistory(HISTORY_SEED_MAX, includeShorts = false))
                        .withoutDisliked()
                }.getOrElse { emptyList() }
            return SavedSeedSources(historySeeds, likedSeedInputs(), playlistSeedInputs().withoutDisliked())
        }

        /** A disliked video must never open a related lane, however much of it was watched (#907). */
        private suspend fun List<GraphSeedInput>.withoutDisliked(): List<GraphSeedInput> {
            val disliked = runCatching { likedVideosRepository.dislikedVideoIds() }.getOrElse { emptySet() }
            return if (disliked.isEmpty()) this else filterNot { it.id in disliked }
        }

        /** Seeds for lasting interests: likes, saved playlists and the watches older than the recent window. */
        suspend fun longTermSeedInputs(): List<GraphSeedInput> {
            val olderHistory =
                runCatching {
                    graphSeedInputsFromHistory(
                        viewHistory.getRecentVideoHistory(LONG_TERM_HISTORY_MAX, includeShorts = false),
                        max = LONG_TERM_HISTORY_MAX,
                    ).drop(HISTORY_SEED_MAX)
                }.getOrElse { emptyList() }
            return (likedSeedInputs() + playlistSeedInputs() + olderHistory).distinctBy { it.id }.withoutDisliked()
        }

        private suspend fun likedSeedInputs(): List<GraphSeedInput> =
            runCatching {
                likedVideosRepository.getLikedVideosFlow().first().map {
                    GraphSeedInput(
                        id = it.videoId,
                        title = it.title,
                        channelId = it.channelId.orEmpty(),
                        source = GraphSeedSource.LIKED,
                        engagementWeight = 1.0,
                        timestamp = it.likedAt,
                        durationSec = 0,
                        percentWatched = 0.0,
                    )
                }
            }.getOrElse { emptyList() }

        private suspend fun playlistSeedInputs(): List<GraphSeedInput> =
            runCatching {
                playlistRepository.getSavedVideoPlaylistVideos().map {
                    GraphSeedInput(
                        id = it.id,
                        title = it.title,
                        channelId = it.channelId,
                        source = GraphSeedSource.PLAYLIST,
                        engagementWeight = 1.0,
                        timestamp = it.timestamp,
                        durationSec = it.duration,
                        percentWatched = 0.0,
                    )
                }
            }.getOrElse { emptyList() }

        fun activeSavedSeedCooldown(now: Long): Set<String> {
            savedSeedCooldown.entries.removeAll { now - it.value > SAVED_SEED_COOLDOWN_MS }
            return savedSeedCooldown.keys.toHashSet()
        }

        fun markSeedsUsed(
            seedIds: Collection<String>,
            now: Long,
        ) {
            seedIds.forEach { savedSeedCooldown[it] = now }
        }

        /** One seed's related list, through the same memory and Room caches the feed uses. */
        internal suspend fun relatedVideos(
            seedId: String,
            filters: suspend () -> HomeFeedCacheFilters,
        ): List<Video> = fetchRelatedVideos(seedId, filters)

        /**
         * Lets the related lists of up to two recent uploads of followed channels, each used once, teach
         * co-watch which channels sit beside the viewer's own ([NeighbourChannels]). Two requests at most.
         */
        suspend fun learnFromFollowed(
            subscriptionFeed: List<Video>,
            followed: Set<String>,
            filters: suspend () -> HomeFeedCacheFilters,
            now: Long = System.currentTimeMillis(),
        ) {
            if (followed.isEmpty()) return
            val known = neuroEngine.coWatchSourceIds()
            subscriptionFeed
                .asSequence()
                .filter { it.channelId in followed && it.id !in known && !it.isShort && !it.isLive && !it.isUpcoming }
                .filter { now - it.timestamp in 0..NeighbourChannels.UPLOAD_WINDOW_MS }
                .sortedByDescending { it.timestamp }
                .distinctBy { it.channelId }
                .take(FOLLOWED_SOURCES_PER_LOAD)
                .toList()
                .forEach { upload ->
                    val related = fetchRelatedVideos(upload.id, filters)
                    if (related.isNotEmpty()) neuroEngine.noteRelatedOfFollowed(upload, related)
                }
        }

        private suspend fun fetchRelatedVideos(
            seedId: String,
            filters: suspend () -> HomeFeedCacheFilters,
        ): List<Video> {
            val ts = System.currentTimeMillis()
            relatedCache[seedId]?.takeIf { ts - it.ts < RELATED_TTL_MS }?.videos?.let { return it }

            val persisted =
                runCatching {
                    homeFeedCache.loadRelated(seedId, filters(), ts)
                }.getOrElse { emptyList() }
            if (persisted.isNotEmpty()) {
                relatedCache[seedId] = CachedRelated(persisted, ts)
                return persisted
            }

            return (
                relatedSemaphore.withPermit {
                    withTimeoutOrNull(RELATED_FETCH_TIMEOUT_MS) {
                        repository.getRelatedCandidates(seedId, forFeed = true)
                    } ?: emptyList()
                }
            ).also {
                // A timeout returns nothing; caching that would blank the seed's lane for the whole TTL.
                if (it.isNotEmpty()) {
                    relatedCache[seedId] = CachedRelated(it, ts)
                    homeFeedCache.saveRelated(seedId, it, ts)
                }
            }
        }

        /** Expands seed video ids into related (/next) neighbours with graph metadata. */
        internal suspend fun fetchRelatedGraph(
            seedInputs: List<GraphSeedInput>,
            seedIds: List<String>,
            filters: suspend () -> HomeFeedCacheFilters,
        ): RelatedGraphFetchResult =
            coroutineScope {
                if (seedIds.isEmpty()) {
                    return@coroutineScope RelatedGraphFetchResult(seedInputs, seedIds, emptyList(), emptyMap())
                }
                val now = System.currentTimeMillis()
                val seedMetadata =
                    seedInputs
                        .filter { it.id in seedIds }
                        .groupBy { it.id }
                        .mapValues { (_, seeds) -> seeds.maxBy { GraphSeedSelector.scoreSeed(it, now) } }

                val perSeed =
                    seedIds
                        .map { seedId ->
                            async {
                                val seed = seedMetadata[seedId]
                                val videos = fetchRelatedVideos(seedId, filters)
                                if (seed != null && videos.isNotEmpty()) neuroEngine.noteRelatedOfWatched(seed, videos)
                                val seedScore = seed?.let { GraphSeedSelector.scoreSeed(it, now) } ?: 0.0
                                val seedCluster = seed?.let { GraphSeedSelector.clusterKey(it) } ?: "misc"
                                val candidates =
                                    videos.mapIndexed { index, video ->
                                        GraphCandidate(
                                            video = video,
                                            seedId = seedId,
                                            seedScore = seedScore,
                                            graphRank = index,
                                            seedCluster = seedCluster,
                                            seedResultCount = videos.size,
                                        )
                                    }
                                seedId to candidates
                            }
                        }.awaitAll()
                val rawCandidates = perSeed.flatMap { it.second }
                RelatedGraphFetchResult(
                    seedInputs = seedInputs,
                    seedIds = seedIds,
                    candidates = mergeGraphCandidates(rawCandidates),
                    fetchedPerSeed = perSeed.associate { (seedId, candidates) -> seedId to candidates.size },
                )
            }

        internal suspend fun fetchRelatedGraphCandidates(
            seedInputs: List<GraphSeedInput>,
            seedIds: List<String>,
            filters: suspend () -> HomeFeedCacheFilters,
        ): List<GraphCandidate> = fetchRelatedGraph(seedInputs, seedIds, filters).candidates
    }

/**
 * Videos on screen, usable as related-graph seeds for load-more. What a related lane put on screen is left out ([relatedPickIds]), so
 * a lane is at most one hop from something the viewer watched, searched or follows.
 */
internal fun feedSeedInputs(
    videos: List<Video>,
    now: Long,
    max: Int,
    relatedPickIds: Set<String> = emptySet(),
): List<GraphSeedInput> =
    videos
        .asSequence()
        .filter { !it.isShort && it.id.isNotBlank() && it.id !in relatedPickIds }
        .take(max)
        .map { video ->
            GraphSeedInput(
                id = video.id,
                title = video.title,
                channelId = video.channelId,
                source = GraphSeedSource.FEED,
                engagementWeight = 0.6,
                timestamp = now,
                durationSec = video.duration,
                percentWatched = 0.0,
            )
        }.toList()
