package io.github.aedev.flow.data.shorts

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.contentfilter.aislist.AiListRepository
import io.github.aedev.flow.data.contentfilter.aislist.AiVerdict
import io.github.aedev.flow.data.local.CachedHomeVideo
import io.github.aedev.flow.data.local.HomeFeedCacheFilters
import io.github.aedev.flow.data.local.HomeFeedCacheRepository
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.model.ShortVideo
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.model.toShortVideo
import io.github.aedev.flow.data.model.toVideo
import io.github.aedev.flow.data.recommendation.FeedExclusions
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.recommendation.ShortsSeedInput
import io.github.aedev.flow.data.recommendation.ShortsSeedSource
import io.github.aedev.flow.data.shorts.feed.InnerTubeShortsFeedSources
import io.github.aedev.flow.data.shorts.feed.ShortsFeedContext
import io.github.aedev.flow.data.shorts.feed.ShortsFeedEngine
import io.github.aedev.flow.data.shorts.feed.ShortsFeedFilters
import io.github.aedev.flow.data.shorts.feed.ShortsFeedLane
import io.github.aedev.flow.data.shorts.feed.ShortsFeedPager
import io.github.aedev.flow.data.shorts.feed.ShortsFeedProfile
import io.github.aedev.flow.data.shorts.feed.ShortsLaneItem
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The algorithmic reel feed. One [ShortsFeedPager] lives as long as it is fresh, so reopening the
 * tab continues the same feed without a request; a cold open seeds the lanes from the persistent
 * reserve so the first page is on screen while the opening round runs.
 */
@Singleton
class ShortsFeedRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val sources: InnerTubeShortsFeedSources,
        private val subscriptionRepository: SubscriptionRepository,
        private val viewHistory: ViewHistory,
        private val likedVideosRepository: LikedVideosRepository,
        private val playlistRepository: PlaylistRepository,
        private val playerPreferences: PlayerPreferences,
        private val homeFeedCache: HomeFeedCacheRepository,
        private val aiList: AiListRepository,
    ) {
        private val lock = Mutex()
        private var pager: ShortsFeedPager? = null
        private var openedAtMillis = 0L
        private val scope = CoroutineScope(SupervisorJob() + PerformanceDispatcher.diskIO)

        val isExhausted: Boolean
            get() = false

        /** The first page. A fresh pager is reused on a seedless open, so a return visit costs nothing. */
        suspend fun openFeed(seedVideoId: String?): List<ShortVideo> =
            withContext(PerformanceDispatcher.networkIO) {
                FlowNeuroEngine.initialize(context)
                lock.withLock {
                    val existing = pager
                    if (seedVideoId == null && existing != null && System.currentTimeMillis() - openedAtMillis < FEED_TTL_MS) {
                        val page = existing.nextPage()
                        if (page.isNotEmpty()) return@withLock page
                    }
                    val fresh = ShortsFeedPager(sources, engine, feedContext)
                    fresh.restoreReserve(loadReserve())
                    pager = fresh
                    openedAtMillis = System.currentTimeMillis()
                    fresh.open(seedVideoId).also { saveReserve(fresh) }
                }
            }

        suspend fun nextPage(): List<ShortVideo> =
            withContext(PerformanceDispatcher.networkIO) {
                val current = lock.withLock { pager } ?: return@withContext openFeed(null)
                current.nextPage().also { saveReserve(current) }
            }

        /** "Want more like this": a chain from [short], returned for the queue to interleave. */
        suspend fun chainFrom(short: ShortVideo): List<ShortVideo> =
            withContext(PerformanceDispatcher.networkIO) {
                lock.withLock { pager }?.chainFrom(short).orEmpty()
            }

        fun noteDwell(short: ShortVideo) {
            pager?.noteDwell(short)
        }

        suspend fun recordShown(videoId: String) {
            if (videoId.isBlank()) return
            runCatching {
                FlowNeuroEngine.initialize(context)
                FlowNeuroEngine.recordSeenShorts(listOf(videoId))
            }.onFailure { Log.w(TAG, "Failed to record shown Short $videoId", it) }
        }

        fun clearCaches() {
            pager = null
            openedAtMillis = 0L
        }

        /** Drops a channel from every lane pool and from the persisted reserve. */
        fun evictChannel(channelId: String) {
            if (channelId.isBlank()) return
            pager?.evictChannel(channelId)
            scope.launch { runCatching { homeFeedCache.deleteChannel(channelId) } }
        }

        /**
         * Whether a reel that has just been named must leave the queue: sequence reels carry no
         * channel or title until `/player` answers, so the page filters could not judge them.
         */
        suspend fun isBlocked(
            channelId: String,
            title: String,
            channelName: String,
        ): Boolean {
            if (channelId.isNotBlank() && channelId in excludedChannelIds()) return true
            if (aiList.currentMatcher().verdict(channelId, null) == AiVerdict.BLOCK) return true
            return blockedTextMatcher()(title, channelName)
        }

        private fun saveReserve(pager: ShortsFeedPager) {
            val tail = pager.reserveTail(RESERVE_MAX)
            scope.launch {
                runCatching {
                    homeFeedCache.saveShortsReserve(tail.map { CachedHomeVideo(it.short.toVideo(), it.lane.name, it.seedId) })
                }.onFailure { Log.w(TAG, "Failed to save the reel reserve", it) }
            }
        }

        private suspend fun loadReserve(): List<ShortsLaneItem> =
            runCatching {
                homeFeedCache
                    .loadShortsReserve(HomeFeedCacheFilters(watchedVideoIds = watchedReelIds(), exclusions = feedExclusions()))
                    .mapNotNull { cached ->
                        ShortsFeedLane.entries
                            .firstOrNull { it.name == cached.source }
                            ?.let { lane -> ShortsLaneItem(cached.video.toShortVideo(), lane, cached.relatedSeedId) }
                    }
            }.getOrElse { emptyList() }

        private suspend fun excludedChannelIds(): Set<String> =
            runCatching { FlowNeuroEngine.getExcludedChannelIds() }.getOrDefault(emptySet())

        private suspend fun feedExclusions(): FeedExclusions =
            runCatching { FlowNeuroEngine.feedExclusions() }.getOrDefault(FeedExclusions.NONE)

        private suspend fun blockedTextMatcher(): (String, String) -> Boolean =
            runCatching { FlowNeuroEngine.blockedContentMatcher() }.getOrElse { { _, _ -> false } }

        private suspend fun watchedReelIds(): Set<String> {
            if (!playerPreferences.hideWatchedShorts.first()) return emptySet()
            return runCatching { viewHistory.getShortProgress().finishedShortIds() }.getOrDefault(emptySet())
        }

        private val engine =
            object : ShortsFeedEngine {
                override suspend fun rank(
                    videos: List<Video>,
                    subscribedChannelIds: Set<String>,
                ): List<Video> = runCatching { FlowNeuroEngine.rank(videos, subscribedChannelIds) }.getOrDefault(videos)

                override suspend fun discoveryQueries(resetDepth: Boolean): List<String> =
                    runCatching { FlowNeuroEngine.generateDiscoveryQueries(resetDepth) }.getOrDefault(emptyList())

                override suspend fun selectSeeds(
                    candidates: List<ShortsSeedInput>,
                    maxSeeds: Int,
                ): List<String> = runCatching { FlowNeuroEngine.selectShortsSeeds(candidates, maxSeeds) }.getOrDefault(emptyList())

                override suspend fun reportQueryNovelty(
                    query: String,
                    novelRatio: Double,
                ) {
                    runCatching { FlowNeuroEngine.reportQueryResultNovelty(query, novelRatio) }
                }

                override suspend fun recentlyShownIds(): Set<String> =
                    runCatching { FlowNeuroEngine.getRecentlyShownVideoIds() }.getOrDefault(emptySet())
            }

        private val feedContext =
            object : ShortsFeedContext {
                override suspend fun profile(): ShortsFeedProfile {
                    val interactions = runCatching { FlowNeuroEngine.getBrainSnapshot().totalInteractions }.getOrDefault(0)
                    return ShortsFeedProfile(
                        subscribedChannelIds = subscriptionRepository.getAllSubscriptionIds(),
                        isColdStart = interactions < COLD_START_INTERACTIONS,
                    )
                }

                override suspend fun filters(): ShortsFeedFilters {
                    val brain = runCatching { FlowNeuroEngine.getBrainSnapshot() }.getOrNull()
                    val aiMatcher = aiList.currentMatcher()
                    return ShortsFeedFilters(
                        watchedIds = watchedReelIds(),
                        seenIds = runCatching { FlowNeuroEngine.getRecentlySeenShorts() }.getOrDefault(emptySet()),
                        suppressedIds = brain?.suppressedVideoIds?.keys.orEmpty(),
                        excludedChannelIds = excludedChannelIds(),
                        isBlockedText = blockedTextMatcher(),
                        isAiBlocked = { channelId -> aiMatcher.verdict(channelId, null) == AiVerdict.BLOCK },
                    )
                }

                override suspend fun seedInputs(): List<ShortsSeedInput> {
                    val history = runCatching { viewHistory.getVideoHistoryFlow().first() }.getOrDefault(emptyList()).filter { it.isShort }
                    val reelIds = history.mapTo(HashSet()) { it.videoId }
                    val liked =
                        runCatching { likedVideosRepository.getLikedVideosFlow().first() }
                            .getOrDefault(emptyList())
                            .filter { it.videoId in reelIds }
                            .map {
                                ShortsSeedInput(
                                    it.videoId,
                                    history
                                        .first { h ->
                                            h.videoId == it.videoId
                                        }.channelId,
                                    ShortsSeedSource.LIKED,
                                    it.likedAt,
                                )
                            }
                    val saved =
                        runCatching { playlistRepository.getSavedShortsFlow().first() }
                            .getOrDefault(emptyList())
                            .map { ShortsSeedInput(it.id, it.channelId, ShortsSeedSource.SAVED, it.timestamp) }
                    val watched =
                        history
                            .sortedByDescending { it.timestamp }
                            .take(SEED_HISTORY_MAX)
                            .map {
                                ShortsSeedInput(
                                    it.videoId,
                                    it.channelId,
                                    ShortsSeedSource.WATCHED,
                                    it.timestamp,
                                    it.progressPercentage.toDouble(),
                                )
                            }
                    return liked.take(SEED_SOURCE_MAX) + saved.take(SEED_SOURCE_MAX) + watched
                }
            }

        private companion object {
            const val TAG = "ShortsFeedRepository"
            const val FEED_TTL_MS = 30L * 60L * 1000L
            const val RESERVE_MAX = 120
            const val COLD_START_INTERACTIONS = 30
            const val SEED_HISTORY_MAX = 40
            const val SEED_SOURCE_MAX = 40
        }
    }
