package io.github.aedev.flow.data.innertube

import android.util.Log
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.shorts.ChannelReelIndex
import io.github.aedev.flow.data.subscriptions.ChannelRssClient
import io.github.aedev.flow.data.subscriptions.ChannelRssEntry
import io.github.aedev.flow.data.subscriptions.ChannelUploadsClient
import io.github.aedev.flow.data.subscriptions.classifiedFromVerdicts
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import io.github.aedev.flow.utils.formatYouTubeRelativeTime
import io.github.aedev.flow.utils.parsePremiereTimestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Which part of a refresh a chunk belongs to. Each has its own counter, so neither runs backwards. */
enum class SubscriptionRefreshStage {
    /** Every channel's RSS feed, eight at a time. */
    RSS,

    /** Shorts checks and channel page reads, only for the channels RSS could not settle. */
    CHECKS,
}

/**
 * One progressive slice of a subscription refresh.
 *
 * [failedChannelIds] holds channels that yielded nothing at all — neither RSS nor the channel tabs
 * answered — so the UI can say part of the feed is missing instead of silently showing less.
 */
data class SubscriptionFeedChunk(
    val videos: List<Video>,
    val failedChannelIds: Set<String>,
    /** Why each failed channel could not be read, keyed by channel id. */
    val failedChannelReasons: Map<String, String> = emptyMap(),
    /**
     * Channels whose RSS answered but whose slice is unfinished: the channel-tab pass failed, or the
     * Shorts tab could not say which uploads are reels. Their latest uploads arrived, so they are not
     * reported, but their earlier rows are kept and they are not stamped fresh, exactly like
     * [failedChannelIds] (#1094, #1186).
     */
    val incompleteChannelIds: Set<String> = emptySet(),
    val stage: SubscriptionRefreshStage = SubscriptionRefreshStage.RSS,
    /** Channels this [stage] has finished, out of [totalChannels] it covers. */
    val processedChannels: Int = 0,
    val totalChannels: Int = 0,
    /**
     * Set once, on the chunk that opens [SubscriptionRefreshStage.CHECKS]: the channels RSS settled
     * on its own. No later chunk changes their slice, so the caller can store them right away.
     */
    val rssSettledChannelIds: Set<String>? = null,
    /**
     * Every upload this sweep knows is a reel, including the older ones the feed leaves out (it keeps
     * a channel's newest reel only). A cached row of one of them is filed as a Short again.
     */
    val reelIds: Set<String> = emptySet(),
)

/**
 * Two-stage subscription extraction.
 *
 * The RSS stage reads every channel's feed: cheap, and the only source with a trustworthy publish
 * timestamp. Each entry's link says whether it is a reel (#1393); an entry without one is only shown
 * once [ChannelReelIndex] or a stored verdict says what it is, and a channel whose verdict is not at
 * hand is held back for the checks stage rather than shown with its reels as videos (#903).
 *
 * The checks stage asks the Shorts tab for those held channels, then reads the channel tabs for the
 * channels RSS could not satisfy, which is also the only way to see livestreams — their upload dates
 * are then back-filled from the RSS timestamps. With [readAllTabs], every channel gets the tab read,
 * which reaches further back than RSS's fifteen entries.
 */
@Singleton
class RssSubscriptionService
    @Inject
    constructor(
        private val rssClient: ChannelRssClient,
        private val channelReelIndex: ChannelReelIndex,
        private val channelUploads: ChannelUploadsClient,
    ) {
        fun fetchSubscriptionVideos(
            channelIds: List<String>,
            knownVideoIds: Set<String> = emptySet(),
            storedReelVerdicts: Map<String, Boolean> = emptyMap(),
            readAllTabs: Boolean = false,
        ): Flow<SubscriptionFeedChunk> =
            flow {
                val uniqueChannelIds = channelIds.distinct()
                Log.i(TAG, "======== FEED FETCH START: ${uniqueChannelIds.size} channels, allTabs=$readAllTabs ========")
                if (uniqueChannelIds.isEmpty()) {
                    Log.w(TAG, "No channel IDs provided — emitting empty list")
                    emit(SubscriptionFeedChunk(emptyList(), emptySet()))
                    return@flow
                }

                val sweep = Sweep()
                val channelExtractionCount = AtomicInteger(0)
                val minimumDateMillis = System.currentTimeMillis() - (SUBSCRIPTION_FEED_LOOKBACK_DAYS * 86400000L)
                Log.i(TAG, "Age cutoff: ${Date(minimumDateMillis)} (${SUBSCRIPTION_FEED_LOOKBACK_DAYS}d)")

                Log.i(TAG, "RSS stage: ${uniqueChannelIds.size} channels")
                val rssChunks = uniqueChannelIds.chunked(RSS_CHUNK_SIZE)
                for ((chunkIndex, chunk) in rssChunks.withIndex()) {
                    val results =
                        coroutineScope {
                            chunk
                                .map { channelId ->
                                    async(Dispatchers.IO) {
                                        val result = fetchRssVideos(channelId, minimumDateMillis, knownVideoIds)
                                        Triple(channelId, result, knownReels(channelId, result, storedReelVerdicts))
                                    }
                                }.awaitAll()
                        }
                    for ((channelId, result, classified) in results) {
                        sweep.hasRecent[channelId] = result.hasRecent
                        sweep.rssDates.putAll(result.videoTimestamps)
                        when {
                            result.failed -> {
                                sweep.unreachable += channelId
                                result.failureReason?.let { sweep.reasons[channelId] = it }
                                sweep.needsTabs += channelId
                            }

                            classified == null -> {
                                sweep.rssAnswered += channelId
                                sweep.held[channelId] = result
                                if (readAllTabs && result.hasRecent) sweep.needsTabs += channelId
                            }

                            else -> {
                                sweep.rssAnswered += channelId
                                sweep.add(classified)
                                if (result.needsTabs(classified, readAllTabs)) sweep.needsTabs += channelId
                            }
                        }
                    }
                    sweep.compact()
                    val processed = ((chunkIndex + 1) * RSS_CHUNK_SIZE).coerceAtMost(uniqueChannelIds.size)
                    emit(sweep.chunk(SubscriptionRefreshStage.RSS, processed, uniqueChannelIds.size))
                    if (chunkIndex > 0 && chunkIndex % (CHANNEL_BATCH_SIZE / RSS_CHUNK_SIZE).coerceAtLeast(1) == 0) {
                        delay(CHANNEL_BATCH_DELAY.random())
                    }
                }

                val checked = uniqueChannelIds.filter { it in sweep.held || it in sweep.needsTabs }
                val settled = sweep.rssAnswered - sweep.held.keys - sweep.needsTabs
                Log.i(TAG, "RSS stage complete: ${settled.size} settled, ${checked.size} to check, ${sweep.rssDates.size} dates")
                emit(sweep.chunk(SubscriptionRefreshStage.CHECKS, 0, checked.size, settled))

                var processedChecks = 0
                val shortsChecks = checked.filter { it in sweep.held && it !in sweep.needsTabs }
                for (chunk in shortsChecks.chunked(SHORTS_CHECK_CHUNK_SIZE)) {
                    val marked =
                        coroutineScope {
                            chunk
                                .map { channelId ->
                                    async(Dispatchers.IO) {
                                        channelId to channelReelIndex.markReels(channelId, sweep.held.getValue(channelId).videos)
                                    }
                                }.awaitAll()
                        }
                    for ((channelId, classified) in marked) {
                        val result = sweep.held.remove(channelId) ?: continue
                        if (classified == null) {
                            // Shown as uploads, but never stamped or trusted, so the next pass asks again.
                            sweep.add(result.videos)
                            sweep.incomplete += channelId
                        } else {
                            sweep.add(classified)
                            if (result.needsTabs(classified, readAllTabs)) sweep.needsTabs += channelId
                        }
                        if (channelId !in sweep.needsTabs) processedChecks++
                    }
                    sweep.compact()
                    emit(sweep.chunk(SubscriptionRefreshStage.CHECKS, processedChecks, checked.size))
                }

                val tabChannels = checked.filter { it in sweep.needsTabs }
                Log.i(TAG, "Checks stage: ${shortsChecks.size} Shorts checks, channel tabs for ${tabChannels.size}")
                val chunks = tabChannels.chunked(CHANNEL_CHUNK_SIZE)
                for ((chunkIndex, chunk) in chunks.withIndex()) {
                    if (channelExtractionCount.get() >= CHANNEL_BATCH_SIZE) {
                        Log.i(TAG, "Batch limit reached, throttling...")
                        delay(CHANNEL_BATCH_DELAY.random())
                        channelExtractionCount.set(0)
                    }

                    val chunkResults =
                        coroutineScope {
                            chunk
                                .map { channelId ->
                                    async(Dispatchers.IO) {
                                        channelId to
                                            runChannelTabFetch(channelId, minimumDateMillis, sweep.rssDates, channelExtractionCount)
                                    }
                                }.awaitAll()
                        }

                    for ((channelId, result) in chunkResults) {
                        when {
                            !result.failed -> {
                                sweep.unreachable -= channelId
                                sweep.reasons -= channelId
                                sweep.incomplete -= channelId
                            }

                            channelId in sweep.rssAnswered -> {
                                sweep.incomplete += channelId
                            }

                            else -> {
                                sweep.unreachable += channelId
                                result.failureReason?.let { sweep.reasons[channelId] = it }
                            }
                        }
                        sweep.held.remove(channelId)?.let { rss ->
                            val reelIds = result.videos.filter { it.isShort }.mapTo(HashSet()) { it.id }
                            sweep.add(rss.videos.map { if (it.id in reelIds) it.copy(isShort = true) else it })
                        }
                        sweep.add(result.videos)
                    }
                    sweep.compact()
                    processedChecks = (processedChecks + chunk.size).coerceAtMost(checked.size)
                    Log.d(TAG, "Chunk ${chunkIndex + 1}/${chunks.size}: +${chunkResults.sumOf { it.second.videos.size }}")
                    emit(sweep.chunk(SubscriptionRefreshStage.CHECKS, processedChecks, checked.size))
                }

                emit(sweep.chunk(SubscriptionRefreshStage.CHECKS, checked.size, checked.size))
                Log.i(
                    TAG,
                    "======== FEED FETCH COMPLETE: regular=${sweep.regular.size} shorts=${sweep.shorts.size} " +
                        "unreachable=${sweep.unreachable.size} incomplete=${sweep.incomplete.size} ========",
                )
            }

        /** What one sweep has gathered so far, channel by channel. */
        private inner class Sweep {
            val regular = mutableListOf<Video>()
            val shorts = mutableListOf<Video>()
            val reelIds = HashSet<String>()
            val rssDates = mutableMapOf<String, Long>()
            val hasRecent = mutableMapOf<String, Boolean>()

            /** RSS answered, but no verdict on its reels is at hand yet. */
            val held = LinkedHashMap<String, RssResult>()
            val needsTabs = mutableSetOf<String>()
            val rssAnswered = mutableSetOf<String>()

            // Seeded by the RSS stage and cleared per channel as soon as the tabs answer for it.
            val unreachable = mutableSetOf<String>()
            val reasons = mutableMapOf<String, String>()
            val incomplete = mutableSetOf<String>()

            fun add(videos: List<Video>) {
                videos.forEach {
                    if (it.isShort) {
                        shorts += it
                        reelIds += it.id
                    } else {
                        regular += it
                    }
                }
            }

            fun compact() {
                compactAccumulator(regular, MAX_REGULAR_VIDEOS)
                compactAccumulator(shorts, MAX_SHORTS)
            }

            fun chunk(
                stage: SubscriptionRefreshStage,
                processed: Int,
                total: Int,
                rssSettled: Set<String>? = null,
            ) = SubscriptionFeedChunk(
                videos = buildSweepFeed(regular, shorts),
                failedChannelIds = unreachable.toSet(),
                failedChannelReasons = reasons.toMap(),
                incompleteChannelIds = incomplete.toSet(),
                stage = stage,
                processedChannels = processed,
                totalChannels = total,
                rssSettledChannelIds = rssSettled,
                reelIds = reelIds.toSet(),
            )
        }

        /**
         * RSS carries only the last fifteen uploads, so a Shorts-heavy channel can fill all of them
         * with reels and hide its latest long-form upload entirely.
         */
        private fun RssResult.needsTabs(
            classified: List<Video>,
            readAllTabs: Boolean,
        ): Boolean = hasRecent && (readAllTabs || needsChannelFallback || (hasRecentEntries && classified.none { !it.isShort }))

        private suspend fun runChannelTabFetch(
            channelId: String,
            minimumDateMillis: Long,
            rssDateMap: Map<String, Long>,
            channelExtractionCount: AtomicInteger,
        ): ChannelFetchResult =
            try {
                getChannelVideos(channelId, minimumDateMillis, rssDateMap).also {
                    if (it.videos.isNotEmpty()) channelExtractionCount.incrementAndGet()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "UNCAUGHT in channel $channelId: ${e::class.simpleName}: ${e.message}")
                ChannelFetchResult(emptyList(), failed = true, failureReason = "${e::class.simpleName}: ${e.message}")
            }

        private data class RssResult(
            val hasRecent: Boolean,
            val videoTimestamps: Map<String, Long>,
            val videos: List<Video>,
            val needsChannelFallback: Boolean,
            val linkVerdicts: Map<String, Boolean> = emptyMap(),
            /** The feed lists an upload inside the window, whether or not it survived the filters. */
            val hasRecentEntries: Boolean = false,
            val failed: Boolean = false,
            val failureReason: String? = null,
        )

        private data class ChannelFetchResult(
            val videos: List<Video>,
            val failed: Boolean,
            val failureReason: String? = null,
        )

        /**
         * RSS gives accurate dates for all recent uploads (including Shorts) but never a duration or a
         * live flag, so its timestamps double as the date source for the Phase 2 tab items.
         */
        private suspend fun fetchRssVideos(
            channelId: String,
            minimumDateMillis: Long,
            knownVideoIds: Set<String>,
        ): RssResult {
            val feed =
                rssClient.fetch(channelId).getOrElse { error ->
                    Log.w(TAG, "[$channelId] RSS FAILED: ${error::class.simpleName}: ${error.message}")
                    return RssResult(
                        hasRecent = true,
                        videoTimestamps = emptyMap(),
                        videos = emptyList(),
                        needsChannelFallback = true,
                        failed = true,
                        failureReason = "RSS: ${error::class.simpleName}: ${error.message}",
                    )
                }

            val timestamps = mutableMapOf<String, Long>()
            val videos = mutableListOf<Video>()
            val linkVerdicts = mutableMapOf<String, Boolean>()
            var newestTimestamp = 0L

            for (entry in feed.entries) {
                val publishedAt = entry.publishedAtMillis
                if (publishedAt <= 0L) continue
                timestamps[entry.videoId] = publishedAt
                if (publishedAt > newestTimestamp) newestTimestamp = publishedAt
                if (publishedAt > minimumDateMillis && !entry.isPaidOrMembersOnly()) {
                    videos += entry.toVideo(channelId = channelId, channelName = feed.channelName)
                    entry.isShort?.let { linkVerdicts[entry.videoId] = it }
                }
            }

            if (timestamps.isEmpty()) {
                return RssResult(
                    hasRecent = true,
                    videoTimestamps = emptyMap(),
                    videos = emptyList(),
                    needsChannelFallback = true,
                )
            }

            val hasUnknownRecentUpload =
                timestamps.any { (videoId, timestamp) ->
                    timestamp > minimumDateMillis && videoId !in knownVideoIds
                }
            return RssResult(
                hasRecent = newestTimestamp > minimumDateMillis || hasUnknownRecentUpload,
                videoTimestamps = timestamps,
                videos = videos,
                needsChannelFallback = videos.isEmpty() && newestTimestamp > minimumDateMillis,
                linkVerdicts = linkVerdicts,
                hasRecentEntries = newestTimestamp > minimumDateMillis,
            )
        }

        /**
         * Each entry's link, else a stored verdict, classifies the slice without a browse. When some
         * entry has neither, a Shorts tab read within the cache window is reused; otherwise the
         * channel needs a Shorts check, one full browse, and null holds it for the checks stage.
         */
        private suspend fun knownReels(
            channelId: String,
            result: RssResult,
            storedReelVerdicts: Map<String, Boolean>,
        ): List<Video>? =
            classifiedFromVerdicts(result.videos, result.linkVerdicts, storedReelVerdicts)
                ?: channelReelIndex.markReelsIfCached(channelId, result.videos)

        private fun ChannelRssEntry.toVideo(
            channelId: String,
            channelName: String?,
        ): Video {
            val now = System.currentTimeMillis()
            val isUpcoming = publishedAtMillis > now + 60_000L
            return Video(
                id = videoId,
                title = title,
                channelName = channelName.orEmpty().ifBlank { UNKNOWN_LABEL },
                channelId = channelId,
                thumbnailUrl = ThumbnailUrlResolver.normalizeVideoThumbnail(videoId, thumbnailUrl),
                duration = 0,
                viewCount = viewCount,
                uploadDate = if (isUpcoming) "" else formatRelativeTime(publishedAtMillis),
                timestamp = publishedAtMillis,
                timestampIsExact = true,
                channelThumbnailUrl = "",
                description = description.orEmpty(),
                isShort = isShort == true,
                isLive = false,
                isUpcoming = isUpcoming,
            )
        }

        /**
         * A channel's own Videos, Shorts and Live tabs, through the native InnerTube browse.
         *
         * @param rssDateMap Pre-fetched RSS timestamps keyed by video ID. Used to assign accurate
         *   upload dates to Shorts tab items, which carry no date metadata of their own.
         */
        private suspend fun getChannelVideos(
            channelId: String,
            minimumDateMillis: Long,
            rssDateMap: Map<String, Long>,
        ): ChannelFetchResult {
            val uploads =
                channelUploads.fetch(channelId, minimumDateMillis).getOrElse { error ->
                    if (error is CancellationException) throw error
                    Log.e(TAG, "[$channelId] Channel tabs FAILED (${error::class.simpleName}): ${error.message}")
                    return ChannelFetchResult(
                        videos = emptyList(),
                        failed = true,
                        failureReason = "Channel: ${error::class.simpleName}: ${error.message}",
                    )
                }
            val channelAvatar =
                uploads.owner.avatarUrl
                    .takeIf { it.isNotBlank() }
                    ?.let { ThumbnailUrlResolver.resolveChannelAvatar(it) }
                    .orEmpty()
            val shortIds = uploads.shorts.mapTo(HashSet()) { it.id }
            val liveIds = uploads.live.mapTo(HashSet()) { it.id }
            val videos =
                (uploads.videos + uploads.shorts + uploads.live)
                    .distinctBy { it.id }
                    .mapNotNull { video ->
                        video.toFeedVideo(
                            channelId = channelId,
                            channelName = uploads.owner.name,
                            channelAvatar = channelAvatar,
                            fromShortsTab = video.id in shortIds,
                            fromLiveTab = video.id in liveIds,
                            rssUploadTimeMillis = rssDateMap[video.id],
                            minimumDateMillis = minimumDateMillis,
                        )
                    }

            Log.i(TAG, "[$channelId] RESULT: ${videos.size} videos (${videos.count { it.isShort }} shorts)")
            return ChannelFetchResult(videos, failed = false)
        }

        private fun Video.toFeedVideo(
            channelId: String,
            channelName: String,
            channelAvatar: String,
            fromShortsTab: Boolean,
            fromLiveTab: Boolean,
            rssUploadTimeMillis: Long?,
            minimumDateMillis: Long,
        ): Video? {
            if (isPaidOrMembersOnly()) return null
            val premiereAt = if (isUpcoming) parsePremiereTimestamp(uploadDate) else null
            val uploadedAt = rssUploadTimeMillis ?: timestamp.takeIf { it > 0L } ?: premiereAt ?: return null
            if (uploadedAt <= minimumDateMillis) return null

            val upcoming = isUpcoming || uploadedAt > System.currentTimeMillis() + 60_000L
            val isArchivedLivestream = fromLiveTab && !isLive && !upcoming
            return copy(
                channelName = this.channelName.ifBlank { channelName }.ifBlank { UNKNOWN_LABEL },
                channelId = channelId,
                thumbnailUrl = ThumbnailUrlResolver.normalizeVideoThumbnail(id, thumbnailUrl),
                uploadDate =
                    if (upcoming && uploadDate.isNotBlank()) {
                        uploadDate
                    } else {
                        formatRelativeTime(uploadedAt).let { if (isArchivedLivestream) "Streamed $it" else it }
                    },
                timestamp = uploadedAt,
                timestampIsExact = rssUploadTimeMillis != null,
                channelThumbnailUrl = channelAvatar.ifBlank { channelThumbnailUrl },
                isShort = isShort || fromShortsTab,
                isLive = !upcoming && (isLive || fromLiveTab),
                isUpcoming = upcoming,
            )
        }

        private fun formatRelativeTime(timestampMillis: Long): String = formatYouTubeRelativeTime(timestampMillis)

        /** The structured members-only badge, or a strong marker in the title itself. */
        private fun Video.isPaidOrMembersOnly(): Boolean = membersOnlyText != null || hasRestrictionMarker(title)

        /**
         * Title only: "join this channel" and "channel members" are YouTube's stock membership call
         * to action in ordinary descriptions, so matching descriptions dropped normal uploads (#1094).
         */
        private fun ChannelRssEntry.isPaidOrMembersOnly(): Boolean = hasRestrictionMarker(title)

        private fun hasRestrictionMarker(title: String): Boolean {
            val normalized = title.lowercase(Locale.US)
            return RESTRICTION_MARKERS.any { marker -> normalized.contains(marker) }
        }

        private companion object {
            const val TAG = "InnertubeSubs"
            const val UNKNOWN_LABEL = "Unknown"

            const val RSS_CHUNK_SIZE = 8
            const val SHORTS_CHECK_CHUNK_SIZE = 4
            const val CHANNEL_CHUNK_SIZE = 3
            const val CHANNEL_BATCH_SIZE = 50
            val CHANNEL_BATCH_DELAY = (100L..400L)
            const val SUBSCRIPTION_FEED_LOOKBACK_DAYS = 60L

            val RESTRICTION_MARKERS =
                listOf(
                    "members only",
                    "members-only",
                    "member only",
                    "member-only",
                    "membership only",
                    "requires membership",
                )
        }
    }
