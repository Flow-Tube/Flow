package io.github.aedev.flow.data.subscriptions

import android.util.Log
import androidx.room.withTransaction
import io.github.aedev.flow.data.innertube.RssSubscriptionService
import io.github.aedev.flow.data.innertube.SubscriptionRefreshStage
import io.github.aedev.flow.data.local.AppDatabase
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.local.dao.CacheDao
import io.github.aedev.flow.data.local.entity.SubscriptionFeedEntity
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.model.VideoCollaborator
import io.github.aedev.flow.data.subscriptions.SubscriptionFeedMerger.preservingEnrichedMetadata
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** One progressive step of a refresh, as the caller should render it. */
data class SubscriptionFeedRefreshProgress(
    val videos: List<Video>,
    val failedChannelIds: Set<String>,
    val failedChannelReasons: Map<String, String>,
    val processedChannels: Int,
    val totalChannels: Int,
    val stage: SubscriptionRefreshStage = SubscriptionRefreshStage.RSS,
    /** The feed is stored and the counters are done; anything after this only adds collaborations. */
    val finished: Boolean = false,
)

/**
 * The single owner of the subscription feed cache.
 *
 * Every trigger — opening the screen, the periodic in-screen refresh, app startup and the
 * background new-upload check — goes through here, so there is one staleness policy, one merge
 * implementation and one writer for `subscription_feed_cache`.
 */
@Singleton
class SubscriptionFeedRepository
    @Inject
    constructor(
        private val subscriptionRepository: SubscriptionRepository,
        private val rssSubscriptionService: RssSubscriptionService,
        private val cacheDao: CacheDao,
        private val database: AppDatabase,
        private val playerPreferences: PlayerPreferences,
        private val collabIndex: ChannelCollabIndex,
    ) {
        /**
         * Guards against overlapping refreshes; the screen, the startup pass and the worker can all
         * fire within the same second, and a second sweep would only duplicate the first one's work.
         */
        private val refreshLock = Mutex()

        private val collaborationsLock = Mutex()

        /** The refresh holding [refreshLock], for a caller that arrives while it runs. */
        private val running = MutableStateFlow<SubscriptionFeedRefreshProgress?>(null)

        /**
         * The stored feed. With a group's [scope], that group's rows are read on their own as well,
         * so its older uploads are not cut off by the newest rows of every other channel.
         */
        fun observeFeed(scope: Set<String>? = null): Flow<List<Video>> {
            val all = cacheDao.getSubscriptionFeed()
            val rows =
                if (scope.isNullOrEmpty() || scope.size > SCOPED_READ_MAX_CHANNELS) {
                    all
                } else {
                    combine(cacheDao.observeSubscriptionFeedForChannels(scope.toList()), all) { group, everything ->
                        (group + everything).distinctBy { it.videoId }
                    }
                }
            return combine(rows, playerPreferences.subscriptionCollaborationsEnabled) { entities, collaborations ->
                entities.filter { collaborations || it.feedChannelId.isEmpty() }.map { it.toVideo() }
            }
        }

        /**
         * Which channels are due a refresh right now; empty when everything is still fresh. With a
         * group's [scope], only that group's channels are considered.
         */
        suspend fun planRefresh(
            force: Boolean,
            scope: Set<String>? = null,
        ): SubscriptionRefreshPlan {
            val subscriptions = subscriptionRepository.getAllSubscriptions().first()
            val now = System.currentTimeMillis()
            return if (scope == null) {
                SubscriptionRefreshPlanner.plan(subscriptions = subscriptions, now = now, force = force)
            } else {
                SubscriptionRefreshPlanner.planForGroup(subscriptions, scope, now, force)
            }
        }

        /**
         * Runs [plan] and writes the result. Emits a progressively more complete feed so the caller
         * can render partial results. The channels RSS settles on its own are stored as soon as the
         * RSS stage ends; the rest are stored when the checks finish.
         *
         * Emits nothing when the plan is empty. When another refresh already holds the lock, follows
         * that one's progress instead of starting a second sweep.
         */
        fun refresh(plan: SubscriptionRefreshPlan): Flow<SubscriptionFeedRefreshProgress> =
            flow {
                if (plan.isEmpty) return@flow
                if (!refreshLock.tryLock()) {
                    Log.d(TAG, "A subscription refresh is already running; following it")
                    emitAll(
                        running.takeWhile { it != null }.filterNotNull().transformWhile {
                            emit(it)
                            !it.finished
                        },
                    )
                    return@flow
                }

                val done =
                    try {
                        runRefresh(plan) { progress ->
                            running.value = progress
                            emit(progress)
                        }
                    } finally {
                        running.value = null
                        refreshLock.unlock()
                    }
                if (refreshCollaborations()) {
                    emit(done.copy(videos = withContext(PerformanceDispatcher.diskIO) { loadCachedFeed(plan.scope) }))
                }
            }

        private suspend fun runRefresh(
            plan: SubscriptionRefreshPlan,
            publish: suspend (SubscriptionFeedRefreshProgress) -> Unit,
        ): SubscriptionFeedRefreshProgress {
            val cachedRows =
                withContext(PerformanceDispatcher.diskIO) {
                    plan.channelIds.chunked(SQLITE_VARIABLE_LIMIT).flatMap { cacheDao.getSubscriptionFeedForChannels(it) }
                }
            val sliceCached = cachedRows.map { it.toVideo() }
            val lastFeedFetchAt =
                withContext(PerformanceDispatcher.diskIO) {
                    subscriptionRepository.getAllSubscriptions().first().associate { it.channelId to it.lastFeedFetchAt }
                }

            var previewVideos = withContext(PerformanceDispatcher.diskIO) { loadCachedFeed(plan.scope) }
            var latestChunkVideos = emptyList<Video>()
            var failedChannelIds = emptySet<String>()
            var failedChannelReasons = emptyMap<String, String>()
            var incompleteChannelIds = emptySet<String>()
            var reelIds = emptySet<String>()
            val writtenChannelIds = HashSet<String>()

            rssSubscriptionService
                .fetchSubscriptionVideos(
                    channelIds = plan.channelIds,
                    knownVideoIds = if (plan.isFullRefresh) emptySet() else sliceCached.mapTo(HashSet()) { it.id },
                    storedReelVerdicts = trustedReelVerdicts(cachedRows, lastFeedFetchAt),
                    readAllTabs = plan.readAllTabs,
                ).collect { chunk ->
                    failedChannelIds = chunk.failedChannelIds
                    failedChannelReasons = chunk.failedChannelReasons
                    incompleteChannelIds = chunk.incompleteChannelIds
                    reelIds = chunk.reelIds
                    if (chunk.videos.isNotEmpty()) {
                        latestChunkVideos = chunk.videos
                        previewVideos =
                            SubscriptionFeedMerger
                                .mergeSubscriptionFeed(
                                    freshVideos = chunk.videos,
                                    cachedVideos = previewVideos,
                                    now = System.currentTimeMillis(),
                                    windowMs = SUBSCRIPTION_CACHE_WINDOW_MS,
                                    maxItems = Int.MAX_VALUE,
                                ).withHighQualityThumbnails()
                    }
                    val settled = chunk.rssSettledChannelIds.orEmpty()
                    if (settled.isNotEmpty()) {
                        persist(
                            channelIds = plan.channelIds.filter { it in settled },
                            freshVideos = latestChunkVideos,
                            sliceCached = sliceCached,
                            failedChannelIds = emptySet(),
                            refreshTime = System.currentTimeMillis(),
                            dropUnfollowed = false,
                            reelIds = reelIds,
                        )
                        writtenChannelIds += settled
                        previewVideos = withContext(PerformanceDispatcher.diskIO) { loadCachedFeed(plan.scope) }
                    }
                    publish(
                        SubscriptionFeedRefreshProgress(
                            videos = previewVideos,
                            failedChannelIds = failedChannelIds,
                            failedChannelReasons = failedChannelReasons,
                            processedChannels = chunk.processedChannels,
                            totalChannels = chunk.totalChannels,
                            stage = chunk.stage,
                        ),
                    )
                }

            val remaining = plan.channelIds.filterNot { it in writtenChannelIds }
            if (remaining.isNotEmpty() || plan.isFullRefresh) {
                persist(
                    channelIds = remaining,
                    freshVideos = latestChunkVideos,
                    sliceCached = sliceCached,
                    failedChannelIds = failedChannelIds + incompleteChannelIds,
                    refreshTime = System.currentTimeMillis(),
                    dropUnfollowed = plan.isFullRefresh,
                    reelIds = reelIds,
                )
            }
            val done =
                SubscriptionFeedRefreshProgress(
                    videos = withContext(PerformanceDispatcher.diskIO) { loadCachedFeed(plan.scope) },
                    failedChannelIds = failedChannelIds,
                    failedChannelReasons = failedChannelReasons,
                    processedChannels = 0,
                    totalChannels = 0,
                    stage = SubscriptionRefreshStage.CHECKS,
                    finished = true,
                )
            publish(done)
            return done
        }

        /**
         * Replaces the rows of [channelIds] with their fetched and earlier rows together, and stamps
         * the ones that answered. Every other channel's rows are left as they are. [dropUnfollowed]
         * also removes the uploads of channels no longer followed, which only a full refresh may do.
         */
        private suspend fun persist(
            channelIds: List<String>,
            freshVideos: List<Video>,
            sliceCached: List<Video>,
            failedChannelIds: Set<String>,
            refreshTime: Long,
            dropUnfollowed: Boolean,
            reelIds: Set<String>,
        ) {
            val written = channelIds.toHashSet()
            val subscribedChannelIds =
                withContext(PerformanceDispatcher.diskIO) {
                    subscriptionRepository.getAllSubscriptions().first().mapTo(HashSet()) { it.channelId }
                }
            val write =
                subscriptionFeedWrite(
                    channelIds = channelIds,
                    freshVideos = freshVideos.filter { it.channelId in written },
                    cachedSlice = sliceCached.filter { it.channelId in written },
                    failedChannelIds = failedChannelIds,
                    subscribedChannelIds = subscribedChannelIds,
                    now = refreshTime,
                    windowMs = SUBSCRIPTION_CACHE_WINDOW_MS,
                    maxPerChannel = MAX_ROWS_PER_CHANNEL,
                    reelIds = reelIds,
                )

            val entities = write.rows.map { it.toEntity(refreshTime) }
            withContext(PerformanceDispatcher.diskIO) {
                database.withTransaction {
                    val deleted =
                        if (dropUnfollowed) {
                            channelIds + cacheDao.subscriptionUploadChannelIds().filterNot { it in subscribedChannelIds }
                        } else {
                            channelIds
                        }
                    deleted.chunked(SQLITE_VARIABLE_LIMIT).forEach { ids ->
                        cacheDao.deleteSubscriptionFeedForChannels(ids)
                    }
                    cacheDao.insertSubscriptionFeed(entities)
                    cacheDao.pruneSubscriptionFeedOlderThan(refreshTime - SUBSCRIPTION_CACHE_WINDOW_MS)
                }
                subscriptionRepository.markFeedFetched(write.fetchedChannelIds, refreshTime)
                val cachedCount = cacheDao.getSubscriptionFeedCount()
                playerPreferences.setSubscriptionLastRefresh(refreshTime, cachedCount)
            }
            Log.i(
                TAG,
                "Persisted ${entities.size} rows for ${channelIds.size} channels " +
                    "(dropUnfollowed=$dropUnfollowed, failed=${failedChannelIds.size})",
            )
        }

        /**
         * Adds videos the background new-upload check discovered, without disturbing rows the feed
         * has already enriched. The channel is deliberately *not* marked as fetched: RSS alone
         * cannot see livestreams, so the feed still owes it a full pass.
         *
         * [reelVideoIds] is the caller's reel verdict for these entries, from their links or the
         * Shorts tab. Without it every seeded reel reaches the feed as an ordinary upload and stays
         * there until the channel's next full refresh, visible even with Shorts switched off (#903).
         */
        suspend fun seedFromNotificationCheck(
            channelId: String,
            channelName: String?,
            entries: List<ChannelRssEntry>,
            reelVideoIds: Set<String> = emptySet(),
        ) {
            if (entries.isEmpty()) return
            val now = System.currentTimeMillis()
            val cutoff = now - SUBSCRIPTION_CACHE_WINDOW_MS
            val rows =
                entries
                    .filter { it.publishedAtMillis > cutoff }
                    .map { entry ->
                        entry.toEntity(
                            channelId = channelId,
                            channelName = channelName,
                            cachedAt = now,
                            isShort = entry.videoId in reelVideoIds,
                        )
                    }
            if (rows.isEmpty()) return

            withContext(PerformanceDispatcher.diskIO) {
                cacheDao.insertSubscriptionFeedIfAbsent(rows)
            }
            Log.d(TAG, "Seeded ${rows.size} row(s) for $channelId from the notification check")
        }

        /** The feed's stored copy of [videoId], or null when the feed has never seen it. */
        suspend fun cachedVideo(videoId: String): Video? =
            withContext(PerformanceDispatcher.diskIO) { cacheDao.getSubscriptionFeedRow(videoId)?.toVideo() }

        /** Writes back metadata the on-demand player lookup resolved for already-cached rows. */
        suspend fun updateEnrichedMetadata(videos: Collection<Video>) {
            if (videos.isEmpty()) return
            withContext(PerformanceDispatcher.diskIO) {
                database.withTransaction {
                    videos.forEach { video ->
                        cacheDao.updateSubscriptionFeedMetadata(
                            videoId = video.id,
                            title = video.title,
                            channelName = video.channelName,
                            channelId = video.channelId,
                            thumbnailUrl = video.thumbnailUrl,
                            duration = video.duration,
                            viewCount = video.viewCount,
                            // The cache has no column for a scheduled stream, so it is stored as
                            // live + upcoming and read back the same way.
                            isLive = video.isLive || video.isScheduledLive,
                            isUpcoming = video.isUpcoming,
                            uploadDate = video.uploadDate,
                            timestamp = video.timestamp,
                            timestampIsExact = video.timestampIsExact,
                        )
                    }
                }
            }
        }

        /**
         * Uploads Home read from channel tabs. A row RSS already stored keeps its exact publish time
         * and gains the length and counts RSS cannot give; an upload RSS has not seen yet is added.
         * The channel is not marked fetched, so the feed's own refresh schedule is unchanged.
         */
        suspend fun mergeChannelTabUploads(uploads: List<Video>) {
            if (uploads.isEmpty()) return
            val now = System.currentTimeMillis()
            val recent = uploads.filter { it.timestamp > now - SUBSCRIPTION_CACHE_WINDOW_MS }
            if (recent.isEmpty()) return
            val stored = withContext(PerformanceDispatcher.diskIO) { loadCachedFeed() }
            val (inserts, updates) = splitChannelTabUploads(recent, stored)
            withContext(PerformanceDispatcher.diskIO) {
                cacheDao.insertSubscriptionFeedIfAbsent(inserts.map { it.toEntity(now) })
            }
            updateEnrichedMetadata(updates)
        }

        private suspend fun loadCachedFeed(scope: Set<String>? = null): List<Video> = observeFeed(scope).first()

        /**
         * Looks a few followed channels over for collaborations someone else uploaded and stores what
         * each one shows, replacing that channel's earlier set. With the setting off, drops them all.
         * True when the stored feed changed. Runs after the refresh has released its lock and ended
         * its progress, so the screen does not wait on it.
         */
        private suspend fun refreshCollaborations(): Boolean {
            if (!collaborationsLock.tryLock()) return false
            return try {
                scanCollaborations()
            } finally {
                collaborationsLock.unlock()
            }
        }

        private suspend fun scanCollaborations(): Boolean {
            if (!playerPreferences.subscriptionCollaborationsEnabled.first()) {
                withContext(PerformanceDispatcher.diskIO) { cacheDao.deleteCollaborations() }
                return false
            }
            val followed =
                withContext(PerformanceDispatcher.diskIO) {
                    subscriptionRepository.getAllSubscriptions().first().map { it.channelId }
                }
            val found = collabIndex.scan(followed)
            if (found.isEmpty()) return false
            val now = System.currentTimeMillis()
            withContext(PerformanceDispatcher.diskIO) {
                found.forEach { (feedChannelId, collabs) ->
                    val recent = collabs.filter { it.timestamp > now - SUBSCRIPTION_CACHE_WINDOW_MS }
                    cacheDao.replaceCollaborations(feedChannelId, recent.map { it.toEntity(now, feedChannelId) })
                }
            }
            Log.i(TAG, "Collaborations: looked at ${found.size} channel(s), ${found.values.sumOf { it.size }} video(s)")
            return true
        }

        private companion object {
            const val TAG = "SubsFeedRepo"
            const val SUBSCRIPTION_FEED_LOOKBACK_DAYS = 60L
            const val SUBSCRIPTION_CACHE_WINDOW_MS = SUBSCRIPTION_FEED_LOOKBACK_DAYS * 24L * 60L * 60L * 1000L
            const val MAX_ROWS_PER_CHANNEL = 60

            /** A group read binds every id twice; this keeps it inside SQLite's 999 variables. */
            const val SCOPED_READ_MAX_CHANNELS = 400

            /** SQLite allows 999 bound variables per statement; stay comfortably below it. */
            const val SQLITE_VARIABLE_LIMIT = 500
        }
    }

/** New rows to add, and stored rows with the tab's length and counts but their own publish time. */
internal fun splitChannelTabUploads(
    uploads: List<Video>,
    stored: List<Video>,
): Pair<List<Video>, List<Video>> {
    val byId = stored.associateBy { it.id }
    val (known, unseen) = uploads.partition { it.id in byId }
    return unseen to known.map { byId.getValue(it.id).preservingEnrichedMetadata(it) }
}

private fun SubscriptionFeedEntity.toVideo() =
    Video(
        id = videoId,
        title = title,
        channelName = channelName,
        channelId = channelId,
        thumbnailUrl = thumbnailUrl,
        duration = duration,
        viewCount = viewCount,
        uploadDate = uploadDate,
        timestamp = timestamp,
        timestampIsExact = timestampIsExact,
        channelThumbnailUrl = channelThumbnailUrl,
        collaborators = decodeCollaborators(collaboratorsJson),
        channelThumbnailUrls = decodeCollaborators(collaboratorsJson).map { it.thumbnailUrl }.filter { it.isNotBlank() },
        isShort = isShort,
        isLive = isLive && uploadDate.containsLiveMarker(),
        // A cached "upcoming" outlives its start time only until the next look at the row.
        isUpcoming = isUpcoming && timestamp > System.currentTimeMillis(),
        isScheduledLive = isUpcoming && isLive && timestamp > System.currentTimeMillis(),
    )

private fun Video.toEntity(
    cachedAtMillis: Long,
    feedChannelId: String = "",
) = SubscriptionFeedEntity(
    videoId = id,
    title = title,
    channelName = channelName,
    channelId = channelId,
    thumbnailUrl = thumbnailUrl,
    duration = duration,
    viewCount = viewCount,
    uploadDate = uploadDate,
    timestamp = timestamp,
    channelThumbnailUrl = channelThumbnailUrl,
    isShort = isShort,
    isLive = isLive,
    isUpcoming = isUpcoming,
    cachedAt = cachedAtMillis,
    feedChannelId = feedChannelId,
    collaboratorsJson = if (collaborators.size > 1) collaboratorJson.encodeToString(collaborators) else "",
    timestampIsExact = timestampIsExact,
)

private val collaboratorJson = Json { ignoreUnknownKeys = true }

private fun decodeCollaborators(json: String): List<VideoCollaborator> =
    if (json.isBlank()) {
        emptyList()
    } else {
        runCatching {
            collaboratorJson.decodeFromString<List<VideoCollaborator>>(json)
        }.getOrDefault(emptyList())
    }

private fun ChannelRssEntry.toEntity(
    channelId: String,
    channelName: String?,
    cachedAt: Long,
    isShort: Boolean,
) = SubscriptionFeedEntity(
    videoId = videoId,
    title = title,
    channelName = channelName.orEmpty(),
    channelId = channelId,
    thumbnailUrl =
        io.github.aedev.flow.utils.ThumbnailUrlResolver
            .normalizeVideoThumbnail(videoId, thumbnailUrl),
    // RSS carries neither; the feed's on-demand enrichment fills them in when the item is shown.
    duration = 0,
    viewCount = viewCount,
    uploadDate = "",
    timestamp = publishedAtMillis,
    timestampIsExact = true,
    channelThumbnailUrl = "",
    isShort = isShort,
    isLive = false,
    isUpcoming = publishedAtMillis > cachedAt + 60_000L,
    cachedAt = cachedAt,
)

private fun String.containsLiveMarker(): Boolean {
    val text = lowercase()
    return text.contains("live") ||
        text.contains("stream") ||
        text.contains("watching") ||
        text.contains("started")
}
