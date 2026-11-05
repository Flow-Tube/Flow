package io.github.aedev.flow.data.repository

import android.util.Log
import android.util.LruCache
import io.github.aedev.flow.data.comments.CommentsPageResult
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.model.VideoCollaborator
import io.github.aedev.flow.data.model.needsCollaboratorResolution
import io.github.aedev.flow.data.shorts.ShortsClassifier
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.response.PaidPromotionLabel
import io.github.aedev.flow.innertube.models.response.VideoChapter
import io.github.aedev.flow.innertube.models.response.VideoChaptersParser
import io.github.aedev.flow.innertube.models.response.VideoHeatmap
import io.github.aedev.flow.innertube.models.response.VideoHeatmapParser
import io.github.aedev.flow.innertube.models.response.WatchMetadataResponse
import io.github.aedev.flow.innertube.pages.VideoDescriptionPage
import io.github.aedev.flow.innertube.pages.YouTubeCountParser
import io.github.aedev.flow.innertube.pages.parseYouTubeViewCount
import io.github.aedev.flow.innertube.pages.search.resultVideos
import io.github.aedev.flow.player.stream.InFlightRequestCoalescer
import io.github.aedev.flow.utils.PerformanceDispatcher
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import io.github.aedev.flow.utils.avatarImageIdentityKey
import io.github.aedev.flow.utils.bestImageUrl
import io.github.aedev.flow.utils.distinctBestImageUrls
import io.github.aedev.flow.utils.newPipeLocalization
import io.github.aedev.flow.utils.parseToTimestamp
import io.github.aedev.flow.utils.relativedate.RelativeUploadDateParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeRepository
    @Inject
    constructor(
        private val playerPreferences: PlayerPreferences,
    ) {
        private val service = ServiceList.YouTube

        private val returnYouTubeDislikeCoalescer =
            InFlightRequestCoalescer<String, ReturnYouTubeDislikeCounts?>(
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )

        private val watchNextCoalescer =
            InFlightRequestCoalescer<String, JsonElement?>(
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )

        private val videoCategoryCoalescer =
            InFlightRequestCoalescer<String, String?>(
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )

        private val paidPromotionCoalescer =
            InFlightRequestCoalescer<String, PaidPromotionLabel?>(
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )

        private val paidPromotionCache = LruCache<String, PaidPromotionAnswer>(PAID_PROMOTION_CACHE_SIZE)

        // A category never changes, so this is a plain memo rather than a short player-side cache:
        // the value is worth keeping for every video the engine has already learned from.
        private val videoCategoryCache = VideoCategoryMemo(VIDEO_CATEGORY_CACHE_SIZE)

        // The comment section, the attributed description and the related lane all read one watch
        // response, so it is fetched once per video and held for the few videos in play.
        private val watchNextCache = LruCache<String, JsonElement>(WATCH_NEXT_CACHE_SIZE)

        // Cache for channel avatar URLs to avoid redundant network calls
        private val channelAvatarCache = LruCache<String, String>(300)
        private val videoAvatarStackCache = LruCache<String, List<String>>(300)
        private val videoCollaboratorCache = LruCache<String, List<VideoCollaborator>>(300)
        private val videoChannelMetadataCache = LruCache<String, VideoChannelMetadata>(300)

        private data class VideoChannelMetadata(
            val channelId: String,
            val channelName: String,
            val avatarUrl: String,
        )

        /** A channel's avatar from a UC id, an @handle or a channel URL, read from its InnerTube header; "" on failure. */
        suspend fun fetchChannelAvatarById(channelId: String): String =
            withContext(Dispatchers.IO) {
                val reference = channelId.trim()
                if (reference.isBlank()) return@withContext ""
                channelAvatarCache[reference]?.let { return@withContext it }
                val browseId = channelBrowseId(reference) ?: return@withContext ""
                val url =
                    YouTube
                        .channelLanding(browseId)
                        .getOrNull()
                        ?.header
                        ?.avatarUrl
                        .orEmpty()
                if (url.isNotEmpty()) channelAvatarCache.put(reference, url)
                url
            }

        // A handle is not a browse id (InnerTube answers 400), so it is resolved to its UC id first.
        private suspend fun channelBrowseId(reference: String): String? {
            if (reference.startsWith("UC")) return reference
            CHANNEL_ID_IN_URL.find(reference)?.let { return it.groupValues[1] }
            val url =
                when {
                    reference.startsWith("http") -> reference
                    reference.startsWith("@") -> "https://www.youtube.com/$reference"
                    else -> "https://www.youtube.com/@$reference"
                }
            return YouTube.resolveChannelId(url).getOrNull()
        }

        /**
         * Enrich a list of [Video] objects that are missing [Video.channelThumbnailUrl]
         * by fetching avatar URLs in parallel (max 5 concurrent channel fetches).
         */
        suspend fun enrichVideosWithAvatars(videos: List<Video>): List<Video> =
            supervisorScope {
                val channelIds =
                    videos
                        .filter { it.channelThumbnailUrl.isEmpty() && it.channelId.isNotEmpty() }
                        .map { it.channelId }
                        .distinct()

                if (channelIds.isEmpty()) return@supervisorScope videos

                Log.d(TAG, "enrichVideosWithAvatars: fetching avatars for ${channelIds.size} channels")
                val avatarMap = mutableMapOf<String, String>()
                channelIds.chunked(5).forEach { batch ->
                    batch
                        .map { id ->
                            async(Dispatchers.IO) { withTimeoutOrNull(6_000L) { id to fetchChannelAvatarById(id) } }
                        }.awaitAll()
                        .forEach { pair ->
                            pair?.let { (id, url) -> if (url.isNotEmpty()) avatarMap[id] = url }
                        }
                }
                Log.d(TAG, "enrichVideosWithAvatars: resolved ${avatarMap.size}/${channelIds.size} avatars")
                if (avatarMap.isEmpty()) return@supervisorScope videos
                videos.map { video ->
                    if (video.channelThumbnailUrl.isEmpty()) {
                        avatarMap[video.channelId]?.let { avatar ->
                            video.copy(
                                channelThumbnailUrl = avatar,
                                channelThumbnailUrls = video.channelThumbnailUrls.ifEmpty { listOf(avatar) },
                            )
                        } ?: video
                    } else {
                        video
                    }
                }
            }

        suspend fun enrichMissingChannelMetadata(
            videos: List<Video>,
            limit: Int = 10,
        ): List<Video> =
            supervisorScope {
                val candidates =
                    videos
                        .filter { video -> video.id.isNotBlank() && video.needsChannelMetadata() }
                        .take(limit)
                if (candidates.isEmpty()) return@supervisorScope videos

                val semaphore = kotlinx.coroutines.sync.Semaphore(4)
                val metadataByVideoId =
                    candidates
                        .map { video ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    val metadata = resolveVideoChannelMetadata(video)
                                    if (metadata != null &&
                                        metadata.channelId.isNotBlank() &&
                                        metadata.avatarUrl.isNotBlank()
                                    ) {
                                        videoChannelMetadataCache.put(video.id, metadata)
                                    }
                                    video.id to metadata
                                }
                            }
                        }.awaitAll()
                        .mapNotNull { (videoId, metadata) ->
                            metadata?.let { videoId to it }
                        }.toMap()

                if (metadataByVideoId.isEmpty()) return@supervisorScope videos
                videos.map { video ->
                    val metadata = metadataByVideoId[video.id] ?: return@map video
                    val avatarUrl = metadata.avatarUrl.ifBlank { video.channelThumbnailUrl }
                    video.copy(
                        channelId = metadata.channelId.ifBlank { video.channelId },
                        channelName = metadata.channelName.ifBlank { video.channelName },
                        channelThumbnailUrl = avatarUrl,
                        channelThumbnailUrls =
                            if (avatarUrl.isNotBlank()) {
                                (listOf(avatarUrl) + video.channelThumbnailUrls).distinct()
                            } else {
                                video.channelThumbnailUrls
                            },
                    )
                }
            }

        private suspend fun resolveVideoChannelMetadata(video: Video): VideoChannelMetadata? {
            videoChannelMetadataCache[video.id]?.let { return it }

            val channelMetadata =
                video.channelId.takeIf { it.isNotBlank() }?.let { channelId ->
                    withTimeoutOrNull(6_000L) {
                        getChannelInfo(channelId)?.let { info ->
                            VideoChannelMetadata(
                                channelId = info.id.orEmpty(),
                                channelName = info.name.orEmpty(),
                                avatarUrl =
                                    info.avatars
                                        .maxByOrNull { it.height }
                                        ?.url
                                        .orEmpty(),
                            )
                        }
                    }
                }

            if (channelMetadata?.avatarUrl?.isNotBlank() == true) return channelMetadata

            val watchMetadata =
                withTimeoutOrNull(5_000L) {
                    getLiveWatchMetadata(video.id)?.let { result ->
                        VideoChannelMetadata(
                            channelId = result.channelId.orEmpty(),
                            channelName = result.channelName.orEmpty(),
                            avatarUrl = result.channelAvatarUrl.orEmpty(),
                        )
                    }
                }

            val merged =
                VideoChannelMetadata(
                    channelId =
                        watchMetadata?.channelId.orEmpty().ifBlank {
                            channelMetadata?.channelId.orEmpty().ifBlank { video.channelId }
                        },
                    channelName =
                        watchMetadata?.channelName.orEmpty().ifBlank {
                            channelMetadata?.channelName.orEmpty().ifBlank { video.channelName }
                        },
                    avatarUrl =
                        watchMetadata?.avatarUrl.orEmpty().ifBlank {
                            channelMetadata?.avatarUrl.orEmpty()
                        },
                )

            if (merged.avatarUrl.isNotBlank()) return merged

            val fallbackAvatar =
                merged.channelId
                    .takeIf { it.isNotBlank() }
                    ?.let { channelId ->
                        withTimeoutOrNull(6_000L) { fetchChannelAvatarById(channelId) }
                    }.orEmpty()
            return merged.copy(avatarUrl = fallbackAvatar)
        }

        /**
         * One page of plain search results as feed candidates. The native renderer already carries
         * the channel id, avatar and collaborators, so nothing is fetched per video afterwards.
         */
        suspend fun searchVideos(
            query: String,
            continuation: String? = null,
            params: String? = null,
        ): Pair<List<Video>, String?> = searchVideoPage(query, continuation, params).getOrElse { emptyList<Video>() to null }

        /** [searchVideos] that reports a failed request instead of an empty page, for callers that cache pages. */
        suspend fun searchVideoPage(
            query: String,
            continuation: String? = null,
            params: String? = null,
        ): Result<Pair<List<Video>, String?>> =
            withContext(Dispatchers.IO) {
                YouTube
                    .videoSearch(query, params = params, continuation = continuation)
                    .map { page ->
                        page.resultVideos().map { it.copy(isMusic = looksLikeMusicVideo(it.title, it.channelName)) } to page.continuation
                    }.onFailure { error ->
                        Log.w(TAG, "searchVideos failed for '$query': ${error::class.simpleName}: ${error.message}")
                    }
            }

        suspend fun enrichLikelyCollabAvatarStacks(
            videos: List<Video>,
            limit: Int = 10,
        ): List<Video> =
            supervisorScope {
                val candidates =
                    videos
                        .filter { it.needsCollaboratorResolution() }
                        .take(limit)

                if (candidates.isEmpty()) return@supervisorScope videos

                val fetched =
                    candidates
                        .chunked(3)
                        .flatMap { batch ->
                            batch
                                .map { video ->
                                    async(Dispatchers.IO) {
                                        val collaborators =
                                            videoCollaboratorCache[video.id]
                                                ?: withTimeoutOrNull(4_000L) {
                                                    YouTube.videoCollaborators(video.id).getOrNull()
                                                }.orEmpty().also { items ->
                                                    if (items.isNotEmpty()) {
                                                        videoCollaboratorCache.put(video.id, items)
                                                    }
                                                }
                                        val stack =
                                            collaborators
                                                .map { it.thumbnailUrl }
                                                .filter { it.isNotBlank() }
                                                .ifEmpty {
                                                    videoAvatarStackCache[video.id]
                                                        ?: withTimeoutOrNull(4_000L) {
                                                            YouTube.videoAvatarStack(video.id).getOrNull()
                                                        }.orEmpty().also { urls ->
                                                            videoAvatarStackCache.put(video.id, urls)
                                                        }
                                                }
                                        video.id to (collaborators to stack)
                                    }
                                }.awaitAll()
                        }.filter { (_, result) -> result.first.size > 1 || result.second.size > 1 }
                        .toMap()

                if (fetched.isEmpty()) return@supervisorScope videos

                videos.map { video ->
                    val (collaborators, stack) =
                        fetched[video.id]
                            ?: (emptyList<VideoCollaborator>() to emptyList())
                    if (stack.size <= 1 && collaborators.size <= 1) return@map video

                    val merged =
                        (stack + video.channelThumbnailUrls + video.channelThumbnailUrl)
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinctBy { it.avatarImageIdentityKey() }
                            .take(3)

                    if (merged.size > 1 || collaborators.size > 1) {
                        video.copy(
                            channelId = video.channelId.ifBlank { collaborators.firstOrNull()?.channelId.orEmpty() },
                            channelName =
                                collaborators
                                    .map { it.name }
                                    .filter { it.isNotBlank() }
                                    .takeIf { it.size > 1 }
                                    ?.joinToString(" and ")
                                    ?: video.channelName,
                            channelThumbnailUrl = merged.firstOrNull() ?: video.channelThumbnailUrl,
                            channelThumbnailUrls = merged.ifEmpty { video.channelThumbnailUrls },
                            collaborators = collaborators.ifEmpty { video.collaborators },
                        )
                    } else {
                        video
                    }
                }
            }

        /**
         * Get video stream info for playback.
         *
         * Throws the original exception on failure so callers can display specific, accurate
         * error messages (age restriction, geo-block, private video, etc.) instead of a
         * generic "unknown error".  Callers that want null-on-failure should wrap in
         * try/catch themselves.
         */
        suspend fun getVideoStreamInfo(videoId: String): StreamInfo? =
            withContext(Dispatchers.IO) {
                try {
                    val url = "https://www.youtube.com/watch?v=$videoId"
                    StreamInfo.getInfo(service, url)
                } catch (e: Exception) {
                    // NewPipe "The page needs to be reloaded" error handling
                    // This often happens due to stale internal state or specific YouTube bot identifiers
                    val isReloadError =
                        e.message?.contains("page needs to be reloaded", ignoreCase = true) == true ||
                            (
                                e is org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException &&
                                    e.message?.contains("reloaded") == true
                            )

                    if (isReloadError) {
                        Log.w(
                            "YouTubeRepository",
                            "Hit 'page needs to be reloaded' error for $videoId. Retrying with fresh state...",
                        )

                        // Re-init NewPipe to potentially clear internal state
                        try {
                            NewPipe.init(
                                NewPipe.getDownloader(),
                                NewPipe.getPreferredLocalization(),
                                NewPipe.getPreferredContentCountry(),
                            )
                        } catch (initEx: Exception) {
                            Log.e("YouTubeRepository", "Failed to re-init NewPipe", initEx)
                        }

                        // Retry with alternate URL format which works as a cache buster sometimes
                        try {
                            val altUrl = "https://youtu.be/$videoId"
                            Log.d("YouTubeRepository", "Retrying with alternate URL: $altUrl")
                            return@withContext StreamInfo.getInfo(service, altUrl)
                        } catch (retryEx: Exception) {
                            Log.e("YouTubeRepository", "Retry failed for $videoId: ${retryEx.message}", retryEx)
                            throw retryEx
                        }
                    } else {
                        Log.e("YouTubeRepository", "Error getting stream info for $videoId: ${e.message}", e)
                        throw e
                    }
                }
            }

        /**
         * Get a single video object by ID
         */
        suspend fun getVideo(videoId: String): Video? =
            withContext(Dispatchers.IO) {
                try {
                    val info = getVideoStreamInfo(videoId) ?: return@withContext null

                    val bestThumbnail =
                        info.thumbnails
                            .sortedByDescending { it.height }
                            .map { it.url }
                            .firstOrNull()
                            .let { ThumbnailUrlResolver.normalizeVideoThumbnail(videoId, it) }

                    val avatarUrls = info.uploaderAvatars.distinctBestImageUrls()
                    val bestAvatar = avatarUrls.firstOrNull().orEmpty()

                    Video(
                        id = videoId,
                        title = info.name ?: "Unknown Title",
                        channelName = info.uploaderName ?: "Unknown Channel",
                        channelId = extractChannelId(info.uploaderUrl),
                        thumbnailUrl = bestThumbnail,
                        duration = info.duration.toInt(),
                        viewCount = info.viewCount,
                        uploadDate = info.textualUploadDate ?: "Unknown",
                        timestamp =
                            resolveUploadTimestamp(
                                info.uploadDate
                                    ?.offsetDateTime()
                                    ?.toInstant()
                                    ?.toEpochMilli(),
                                info.textualUploadDate,
                            ),
                        channelThumbnailUrl = bestAvatar,
                        channelThumbnailUrls = avatarUrls,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "${e::class.simpleName}: ${e.message}")
                    null
                }
            }

        /**
         * Get related videos
         */
        suspend fun getRelatedVideos(videoId: String): List<Video> =
            withContext(Dispatchers.IO) {
                val streamInfo = fetchWatchStreamInfoWithAlternates(videoId) ?: return@withContext emptyList()
                getRelatedVideosFromStreamInfo(streamInfo)
                    .filter { it.id.isNotBlank() && it.id != videoId }
                    .distinctBy { it.id }
            }

        /**
         * Fetch channel info (best-effort) using NewPipe's channel extractor.
         */
        suspend fun getChannelInfo(channelIdOrUrl: String): org.schabi.newpipe.extractor.channel.ChannelInfo? =
            withContext(Dispatchers.IO) {
                try {
                    val value = channelIdOrUrl.trim()
                    val channelUrl =
                        when {
                            value.startsWith("http") -> value
                            value.startsWith("UC") -> "https://www.youtube.com/channel/$value"
                            value.startsWith("@") -> "https://www.youtube.com/$value"
                            else -> "https://www.youtube.com/@$value"
                        }
                    org.schabi.newpipe.extractor.channel.ChannelInfo
                        .getInfo(service, channelUrl)
                } catch (e: Exception) {
                    Log.w(TAG, "${e::class.simpleName}: ${e.message}")
                    null
                }
            }

        /**
         * Fetches a channel's creator-declared keyword tags (+ description) and
         * feeds them to the recommendation engine — authored channel identity,
         * used for topic profiles and interest clustering. Best-effort.
         */
        suspend fun learnChannelTags(
            context: android.content.Context,
            channelId: String,
        ) {
            val info = getChannelInfo(channelId) ?: return
            val tags = info.tags.orEmpty()
            if (tags.isEmpty() && info.description.isNullOrBlank()) return
            io.github.aedev.flow.data.recommendation.FlowNeuroEngine
                .onChannelTagsLearned(context, channelId, tags, info.description)
        }

        /**
         * The web watch response for [videoId], fetched once and shared.
         *
         * Returns null when the request fails; callers fall back to whatever they used before
         * rather than failing the surface.
         */
        suspend fun watchNextResponse(videoId: String): JsonElement? {
            watchNextCache.get(videoId)?.let { return it }
            return watchNextCoalescer.run(videoId) {
                YouTube
                    .watchNextJson(videoId)
                    .getOrNull()
                    ?.also { watchNextCache.put(videoId, it) }
            }
        }

        // Read-only by design: the cache is three deep and holds what the player is working with,
        // so the feed-side callers below reuse an entry but never evict one by populating it.
        private fun cachedWatchMetadata(videoId: String): WatchMetadataResponse? = watchNextCache.get(videoId)?.let(::decodeWatchMetadata)

        /**
         * The typed watch response, off the one request the player already makes.
         *
         * [watchNextResponse] is cached and coalesced; [YouTube.watchMetadata] is neither, and
         * issues its own `/next` (sometimes two). Reaching for that one directly on a cache miss is
         * what had a single load fetching the watch page up to three times, once per consumer, none
         * of them seeding the cache for the next.
         */
        private suspend fun watchMetadataFor(
            videoId: String,
            requireRelated: Boolean = false,
        ): WatchMetadataResponse? {
            cachedWatchMetadata(videoId)?.let { return it }
            val shared = watchNextResponse(videoId)?.let(::decodeWatchMetadata)
            // [YouTube.watchMetadata] retries against the other host when the lane comes back
            // empty, so a caller that needs one still gets that second chance.
            if (shared != null && (!requireRelated || shared.relatedVideos().isNotEmpty())) return shared
            return YouTube.watchMetadata(videoId).getOrNull() ?: shared
        }

        /** Seeds [videoCategory] when a player response happened to carry the category already. */
        fun rememberVideoCategory(
            videoId: String,
            category: String,
        ) {
            videoCategoryCache.remember(videoId, category)
        }

        fun cachedVideoCategory(videoId: String): String? = videoCategoryCache.cached(videoId)

        /**
         * The creator-declared category for [videoId], e.g. "Science & Technology".
         *
         * Costs one small MWEB request, because no client that serves playable URLs returns a
         * microformat and /next carries no category at all. Cached for the process: it is a strong,
         * stable clustering signal for the recommendation engine and never changes.
         */
        suspend fun videoCategory(videoId: String): String? {
            if (videoId.isBlank()) return null
            videoCategoryCache.cached(videoId)?.let { return it }
            return videoCategoryCoalescer.run(videoId) {
                YouTube
                    .videoCategory(videoId)
                    .getOrNull()
                    ?.also { videoCategoryCache.remember(videoId, it) }
            }
        }

        /**
         * The paid promotion label for [videoId], or null when it has none or the request failed.
         * An answer is kept for the process, so seeing a video again costs nothing.
         */
        suspend fun paidPromotion(videoId: String): PaidPromotionLabel? {
            paidPromotionCache.get(videoId)?.let { return it.label }
            return paidPromotionCoalescer.run(videoId) {
                val answer = YouTube.paidPromotion(videoId)
                answer.onSuccess { paidPromotionCache.put(videoId, PaidPromotionAnswer(it)) }
                answer.getOrNull()
            }
        }

        /**
         * The rewatch curve for [videoId], or null when the video has none.
         *
         * Free: it rides the watch response the description and comments already fetch. Plenty of
         * videos have no heatmap — too new, too few views, or live — so null is ordinary.
         */
        suspend fun videoHeatmap(videoId: String): VideoHeatmap? =
            withContext(Dispatchers.IO) {
                VideoHeatmapParser.parse(watchNextResponse(videoId))
            }

        /**
         * [video] filled in from the watch response: exact view and like counts, the upload date and
         * the real description.
         *
         * Read from the cached response where there is one, so the enrichment that used to ride on a
         * second extraction now costs nothing on top of the lane fetch.
         */
        suspend fun enrichFromWatchMetadata(video: Video): Video? =
            withContext(Dispatchers.IO) {
                val response =
                    watchMetadataFor(video.id)
                        ?: return@withContext null
                mergeWatchMetadata(video, response)
            }

        /** The creator's chapters for [videoId], empty when the video has none. */
        suspend fun videoChapters(videoId: String): List<VideoChapter> =
            withContext(Dispatchers.IO) {
                VideoChaptersParser.parse(watchNextResponse(videoId))
            }

        /** The watch page description for [videoId], or null when the response could not be read. */
        suspend fun getVideoDescription(videoId: String): VideoDescriptionPage? =
            withContext(Dispatchers.IO) {
                val response = watchNextResponse(videoId) ?: return@withContext null
                YouTube.videoDescription(response, videoId).takeIf { !it.isEmpty }
            }

        /**
         * The first page of a video's comments, in the order [sortToken] names, or the section's
         * own default when it is null.
         *
         * Falls back to the extractor when InnerTube returns nothing, so a schema change degrades
         * the section instead of emptying it.
         */
        suspend fun getVideoComments(
            videoId: String,
            sortToken: String? = null,
        ): CommentsPageResult =
            withContext(Dispatchers.IO) {
                val token =
                    sortToken
                        ?: watchNextResponse(videoId)?.let(YouTube::commentsContinuation)
                val page = token?.let { YouTube.comments(it, videoId).getOrNull() }
                if (page != null && page.comments.isNotEmpty()) {
                    // A first page has just read the watch response, so this costs no second request.
                    val panel =
                        if (sortToken == null && (page.sortOptions.isEmpty() || page.totalText == null)) {
                            watchNextResponse(videoId)?.let(YouTube::commentsPanelHeader)
                        } else {
                            null
                        }
                    return@withContext CommentsPageResult(
                        comments = page.comments,
                        continuation = page.continuation,
                        sortOptions = page.sortOptions.ifEmpty { panel?.sortOptions.orEmpty() },
                        totalText = page.totalText ?: panel?.totalText,
                        totalCount = page.totalCount ?: panel?.totalCount,
                    )
                }
                Log.i(TAG, "InnerTube comments empty for $videoId, falling back to the extractor")
                val (comments, legacyPage) = getComments(videoId)
                CommentsPageResult(comments = comments, legacyPage = legacyPage)
            }

        suspend fun getMoreVideoComments(
            videoId: String,
            continuation: String,
        ): CommentsPageResult =
            withContext(Dispatchers.IO) {
                val page = YouTube.comments(continuation, videoId).getOrNull() ?: return@withContext CommentsPageResult.EMPTY
                CommentsPageResult(comments = page.comments, continuation = page.continuation)
            }

        suspend fun getVideoCommentReplies(
            videoId: String,
            continuation: String,
        ): CommentsPageResult =
            withContext(Dispatchers.IO) {
                val page = YouTube.commentReplies(continuation, videoId).getOrNull() ?: return@withContext CommentsPageResult.EMPTY
                CommentsPageResult(comments = page.comments, continuation = page.continuation)
            }

        /**
         * Fetch the first page of comments for a video.
         * Returns the comments and a next-page token (null if no more pages).
         */
        suspend fun getComments(videoId: String): Pair<List<Comment>, Page?> =
            withContext(Dispatchers.IO) {
                try {
                    val url = "https://www.youtube.com/watch?v=$videoId"
                    val commentsInfo =
                        org.schabi.newpipe.extractor.comments.CommentsInfo
                            .getInfo(service, url)
                    val comments = mapComments(commentsInfo.relatedItems)
                    Pair(comments, commentsInfo.nextPage)
                } catch (e: Exception) {
                    Log.w(TAG, "${e::class.simpleName}: ${e.message}")
                    Pair(emptyList(), null)
                }
            }

        /**
         * Fetch the next page of top-level comments for a video.
         * Returns the new comments and an updated next-page token.
         */
        suspend fun getMoreComments(
            videoId: String,
            nextPage: Page,
        ): Pair<List<Comment>, Page?> =
            withContext(Dispatchers.IO) {
                try {
                    val url = "https://www.youtube.com/watch?v=$videoId"
                    val moreItems =
                        org.schabi.newpipe.extractor.comments.CommentsInfo
                            .getMoreItems(service, url, nextPage)
                    val comments = mapComments(moreItems.items)
                    Pair(comments, moreItems.nextPage)
                } catch (e: Exception) {
                    Log.w(TAG, "${e::class.simpleName}: ${e.message}")
                    Pair(emptyList(), null)
                }
            }

        /**
         * Fetch replies for a comment
         */
        suspend fun getCommentReplies(
            url: String,
            repliesPage: Page,
        ): Pair<List<Comment>, Page?> =
            withContext(Dispatchers.IO) {
                try {
                    val moreItems =
                        org.schabi.newpipe.extractor.comments.CommentsInfo
                            .getMoreItems(service, url, repliesPage)
                    val replies = mapComments(moreItems.items)
                    Pair(replies, moreItems.nextPage)
                } catch (e: Exception) {
                    Log.w(TAG, "${e::class.simpleName}: ${e.message}")
                    Pair(emptyList(), null)
                }
            }

        private suspend fun mapComments(items: List<CommentsInfoItem>): List<Comment> =
            supervisorScope {
                val embeddedAvatars =
                    items.map { item ->
                        ThumbnailUrlResolver.resolveChannelAvatar(item.uploaderAvatars.bestImageUrl())
                    }
                val uploaderReferences = items.map { item -> item.uploaderUrl.orEmpty().trim() }
                val missingAvatarReferences =
                    items.indices
                        .asSequence()
                        .filter { index -> embeddedAvatars[index].isBlank() }
                        .map { index -> uploaderReferences[index] }
                        .filter { reference -> reference.isNotBlank() }
                        .distinct()
                        .toList()

                val fallbackAvatars = mutableMapOf<String, String>()
                missingAvatarReferences.chunked(COMMENT_AVATAR_FETCH_CONCURRENCY).forEach { batch ->
                    batch
                        .map { reference ->
                            async(Dispatchers.IO) {
                                val avatar =
                                    runCatching {
                                        withTimeoutOrNull(COMMENT_AVATAR_FETCH_TIMEOUT_MS) {
                                            fetchChannelAvatarById(reference)
                                        }
                                    }.getOrNull().orEmpty()
                                reference to avatar
                            }
                        }.awaitAll()
                        .forEach { (reference, avatar) ->
                            if (avatar.isNotBlank()) fallbackAvatars[reference] = avatar
                        }
                }

                items.mapIndexed { index, item ->
                    val uploaderReference = uploaderReferences[index]
                    Comment(
                        id = item.commentId ?: "",
                        author = item.uploaderName ?: "Unknown",
                        authorThumbnail =
                            selectCommentAuthorThumbnail(
                                embeddedAvatar = embeddedAvatars[index],
                                resolvedChannelAvatar = fallbackAvatars[uploaderReference],
                            ),
                        text = item.commentText.content ?: "",
                        likeCount = item.likeCount.toInt(),
                        publishedTime = item.textualUploadDate ?: "",
                        replyCount = item.replyCount.toInt(),
                        repliesPage = item.replies,
                        isPinned = item.isPinned,
                        authorChannelId = extractChannelId(uploaderReference),
                    )
                }
            }

        /**
         * Helper to extract related videos directly from a StreamInfo object
         * This avoids a redundant network call when we already have the stream info.
         */
        fun getRelatedVideosFromStreamInfo(info: StreamInfo): List<Video> =
            try {
                info.relatedItems
                    .filterIsInstance<StreamInfoItem>()
                    .map { it.toVideo() }
                    .filter { it.id.isNotBlank() }
                    .distinctBy { it.id }
            } catch (e: Exception) {
                emptyList()
            }

        /** Like and dislike counts from the Return YouTube Dislike archive. */
        data class ReturnYouTubeDislikeCounts(
            val likes: Long?,
            val dislikes: Long?,
        )

        /**
         * One request per video id: the player asks for this from both the stream load and the live
         * metadata refresh, and both want the same response.
         */
        suspend fun returnYouTubeDislikeCounts(videoId: String): ReturnYouTubeDislikeCounts? =
            returnYouTubeDislikeCoalescer.run(videoId) {
                val response = YouTube.returnYouTubeDislike(videoId).getOrNull() ?: return@run null
                ReturnYouTubeDislikeCounts(
                    likes = response.likes?.toLong()?.takeIf { it >= 0L },
                    dislikes = response.dislikes?.toLong(),
                )
            }

        data class LiveWatchMetadata(
            val title: String?,
            val channelName: String?,
            val channelId: String?,
            val channelAvatarUrl: String?,
            val subscriberCount: Long?,
            val viewCount: Long?,
            val description: String?,
            val relatedVideos: List<Video>,
        )

        suspend fun getLiveWatchMetadata(videoId: String): LiveWatchMetadata? =
            withContext(Dispatchers.IO) {
                val resp =
                    watchMetadataFor(videoId, requireRelated = true)
                        ?: return@withContext null
                val related = WatchMetadataVideoMapper.relatedVideos(resp)
                Log.i(
                    TAG,
                    "InnerTube watch metadata for $videoId: " +
                        "rawRelated=${resp.relatedResultCount()} parsedRelated=${related.size}",
                )
                LiveWatchMetadata(
                    title = resp.title(),
                    channelName = resp.channelName(),
                    channelId = resp.channelId(),
                    channelAvatarUrl = resp.channelAvatarUrl(),
                    subscriberCount = YouTubeCountParser.parse(resp.subscriberCountText(), YouTube.locale.hl),
                    viewCount = YouTubeCountParser.parse(resp.viewCountText(), YouTube.locale.hl),
                    description = resp.description(),
                    relatedVideos = related,
                )
            }

        /**
         * The channel a video belongs to, off the watch response the description and comments already
         * fetch. Its owner is the channel people see, where a player response can name a hidden one.
         */
        internal suspend fun watchChannel(videoId: String): WatchChannel? =
            withContext(Dispatchers.IO) {
                watchMetadataFor(videoId)?.let { watchChannelOf(it, YouTube.locale.hl) }
            }

        /** Light related-video harvest for the feed (InnerTube /next, no stream resolution). */
        suspend fun getRelatedCandidates(videoId: String): List<Video> =
            withContext(Dispatchers.IO) {
                val resp =
                    watchMetadataFor(videoId, requireRelated = true)
                        ?: return@withContext emptyList()
                enrichLikelyCollabAvatarStacks(WatchMetadataVideoMapper.relatedVideos(resp))
                    .filter { it.id.isNotBlank() && it.id != videoId }
                    .distinctBy { it.id }
            }

        suspend fun refreshVideoMetadata(video: Video): Video? =
            withContext(Dispatchers.IO) {
                val response = YouTube.watchMetadataLite(video.id).getOrNull() ?: return@withContext null
                mergeWatchMetadata(video, response)
            }

        suspend fun getLiveRelatedVideosBySearch(
            videoId: String,
            title: String?,
            channelName: String?,
        ): List<Video> =
            withContext(Dispatchers.IO) {
                val query =
                    listOfNotNull(
                        channelName?.takeIf { it.isNotBlank() },
                        title?.takeIf { it.isNotBlank() },
                    ).joinToString(" ").takeIf { it.isNotBlank() } ?: return@withContext emptyList()
                val videos =
                    withTimeoutOrNull(8_000L) { searchVideos(query).first }
                        .orEmpty()
                        .filter { it.id.isNotBlank() && it.id != videoId }
                        .distinctBy { it.id }
                        .take(20)
                Log.i(TAG, "Live related search fallback for $videoId: query='$query' results=${videos.size}")
                videos
            }

        suspend fun getLiveWatchMetadataFromNewPipe(videoId: String): LiveWatchMetadata? =
            withContext(Dispatchers.IO) {
                val info = fetchWatchStreamInfoWithAlternates(videoId) ?: return@withContext null
                val thumbnail =
                    info.uploaderAvatars
                        .sortedByDescending { it.height }
                        .firstOrNull()
                        ?.url
                LiveWatchMetadata(
                    title = info.name,
                    channelName = info.uploaderName,
                    channelId = extractChannelId(info.uploaderUrl),
                    channelAvatarUrl = thumbnail,
                    subscriberCount = null,
                    viewCount = info.viewCount.takeIf { it > 0L },
                    description = info.description?.content,
                    relatedVideos =
                        getRelatedVideosFromStreamInfo(info)
                            .filter { it.id != videoId }
                            .distinctBy { it.id },
                )
            }

        private suspend fun fetchWatchStreamInfoWithAlternates(videoId: String): StreamInfo? {
            val urls =
                listOf(
                    "https://www.youtube.com/watch?v=$videoId",
                    "https://youtu.be/$videoId",
                    "https://m.youtube.com/watch?v=$videoId",
                    "https://www.youtube.com/live/$videoId",
                )
            var lastError: Throwable? = null
            urls.forEach { url ->
                val info =
                    try {
                        withTimeoutOrNull(6_000L) { StreamInfo.getInfo(service, url) }
                    } catch (e: Exception) {
                        lastError = e
                        null
                    }
                if (info != null) return info
            }
            Log.w(TAG, "NewPipe watch metadata unavailable for $videoId: ${lastError?.message}")
            return null
        }

        /**
         * Extension function to convert StreamInfoItem to our Video model
         */
        private fun StreamInfoItem.toVideo(): Video {
            val rawUrl = url ?: ""
            val videoId =
                when {
                    rawUrl.contains("watch?v=") -> rawUrl.substringAfter("watch?v=").substringBefore("&")
                    rawUrl.contains("youtu.be/") -> rawUrl.substringAfter("youtu.be/").substringBefore("?")
                    rawUrl.contains("/shorts/") -> rawUrl.substringAfter("/shorts/").substringBefore("?")
                    else -> rawUrl.substringAfterLast("/")
                }

            val bestThumbnail =
                thumbnails
                    .sortedByDescending { it.height }
                    .map { it.url }
                    .firstOrNull()
                    .let { ThumbnailUrlResolver.normalizeVideoThumbnail(videoId, it) }

            val avatarUrls = uploaderAvatars.distinctBestImageUrls()
            val bestAvatar = avatarUrls.firstOrNull().orEmpty()

            var durationSecs = if (duration > 0) duration.toInt() else 0

            val isReel = ShortsClassifier.isReel(this)

            if (isReel && durationSecs == 0) {
                durationSecs = 60
            }

            val isLiveStream = streamType == StreamType.LIVE_STREAM
            if (isLiveStream) {
                durationSecs = 0
            }

            return Video(
                id = videoId,
                title = name ?: "Unknown Title",
                channelName = uploaderName ?: "Unknown Channel",
                channelId = extractChannelId(uploaderUrl),
                thumbnailUrl = bestThumbnail,
                duration = durationSecs,
                viewCount = viewCount,
                uploadDate =
                    run {
                        val date = uploadDate
                        when {
                            textualUploadDate != null -> {
                                textualUploadDate!!
                            }

                            date != null -> {
                                try {
                                    val d = java.util.Date.from(date.offsetDateTime().toInstant())
                                    val sdf = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                                    sdf.format(d)
                                } catch (e: Exception) {
                                    "Unknown"
                                }
                            }

                            else -> {
                                "Unknown"
                            }
                        }
                    },
                timestamp =
                    resolveUploadTimestamp(
                        uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli(),
                        textualUploadDate,
                    ),
                channelThumbnailUrl = bestAvatar,
                channelThumbnailUrls = avatarUrls,
                isUpcoming = streamType == StreamType.NONE,
                isLive = isLiveStream,
                isShort = isReel,
                isMusic = looksLikeMusicVideo(name.orEmpty(), uploaderName.orEmpty()),
            )
        }

        private fun extractChannelId(uploaderUrl: String?): String {
            if (uploaderUrl.isNullOrBlank()) return ""
            val url = uploaderUrl.trim()
            return when {
                url.contains("/channel/") -> {
                    url
                        .substringAfter("/channel/")
                        .substringBefore("/")
                        .substringBefore("?")
                }

                url.contains("/@") -> {
                    "@" +
                        url
                            .substringAfter("/@")
                            .substringBefore("/")
                            .substringBefore("?")
                }

                url.contains("/user/") -> {
                    url
                        .substringAfter("/user/")
                        .substringBefore("/")
                        .substringBefore("?")
                }

                url.contains("/c/") -> {
                    url
                        .substringAfter("/c/")
                        .substringBefore("/")
                        .substringBefore("?")
                }

                else -> {
                    url.substringAfterLast("/").substringBefore("?")
                }
            }
        }

        private fun resolveUploadTimestamp(
            absoluteMillis: Long?,
            textualDate: String?,
        ): Long {
            absoluteMillis?.let { if (it > 0L) return it }
            // 0 is unknown: stamping an unreadable date as now made stale uploads win every recency sort.
            return RelativeUploadDateParser.parse(textualDate, YouTube.locale.hl) ?: 0L
        }

        companion object {
            private const val TAG = "YouTubeRepository"
            private const val COMMENT_AVATAR_FETCH_CONCURRENCY = 4
            private const val COMMENT_AVATAR_FETCH_TIMEOUT_MS = 6_000L
            private const val WATCH_NEXT_CACHE_SIZE = 3
            private const val VIDEO_CATEGORY_CACHE_SIZE = 500
            private const val PAID_PROMOTION_CACHE_SIZE = 100
            private val CHANNEL_ID_IN_URL = Regex("""/channel/(UC[\w-]{22})""")

            @Volatile
            private var instance: YouTubeRepository? = null

            fun getInstance(playerPreferences: io.github.aedev.flow.data.local.PlayerPreferences): YouTubeRepository =
                instance ?: synchronized(this) {
                    instance ?: YouTubeRepository(playerPreferences).also { instance = it }
                }

            fun getInstance(): YouTubeRepository =
                instance ?: error("YouTubeRepository not initialized. Call getInstance(playerPreferences) first.")
        }
    }

internal fun selectCommentAuthorThumbnail(
    embeddedAvatar: String?,
    resolvedChannelAvatar: String?,
): String =
    ThumbnailUrlResolver
        .resolveChannelAvatar(embeddedAvatar)
        .ifBlank { ThumbnailUrlResolver.resolveChannelAvatar(resolvedChannelAvatar) }

/** No real channel id, or no avatar that can be shown (blank, a video frame or a channel page URL). */
internal fun Video.needsChannelMetadata(): Boolean =
    channelId.isBlank() ||
        !channelId.startsWith("UC") ||
        channelThumbnailUrl.isBlank() ||
        ThumbnailUrlResolver.isUnusableChannelAvatar(channelThumbnailUrl)

internal data class WatchChannel(
    val avatarUrl: String?,
    val subscriberCount: Long?,
)

internal fun watchChannelOf(
    response: WatchMetadataResponse,
    hl: String?,
): WatchChannel =
    WatchChannel(
        avatarUrl = response.channelAvatarUrl(),
        subscriberCount = YouTubeCountParser.parse(response.subscriberCountText(), hl),
    )

internal fun mergeWatchMetadata(
    video: Video,
    response: WatchMetadataResponse,
): Video? {
    val uploadDate = response.uploadDate()?.takeIf { it.isNotBlank() } ?: return null
    // A publish time the card already knew exactly wins. Then the relative form: the absolute one is
    // a date with no time, so on its own it places every upload at midnight.
    val timestamp =
        video.timestamp.takeIf { video.timestampIsExact }
            ?: response.relativeUploadDate()?.let { RelativeUploadDateParser.parse(it, YouTube.locale.hl) }
            ?: parseToTimestamp(uploadDate)
            ?: video.timestamp
    val avatarUrl = response.channelAvatarUrl().orEmpty().ifBlank { video.channelThumbnailUrl }
    return video.copy(
        title = response.title().orEmpty().ifBlank { video.title },
        channelName = response.channelName().orEmpty().ifBlank { video.channelName },
        channelId = response.channelId().orEmpty().ifBlank { video.channelId },
        viewCount = YouTubeCountParser.parse(response.viewCountText(), YouTube.locale.hl) ?: video.viewCount,
        likeCount = YouTubeCountParser.parse(response.likeCountText(), YouTube.locale.hl) ?: video.likeCount,
        uploadDate = uploadDate,
        timestamp = timestamp,
        description = response.description().orEmpty().ifBlank { video.description },
        channelThumbnailUrl = avatarUrl,
        channelThumbnailUrls =
            if (avatarUrl.isNotBlank()) {
                (listOf(avatarUrl) + video.channelThumbnailUrls).distinct()
            } else {
                video.channelThumbnailUrls
            },
    )
}

/**
 * An official release, told by its title and uploader conventions. Search results carry no music
 * marker of their own, and this flag makes a card download as a song and a saved playlist open in
 * the music player.
 */
internal fun looksLikeMusicVideo(
    title: String,
    channelName: String,
): Boolean {
    val channel = channelName.lowercase()
    val lowerTitle = title.lowercase()
    return channel.contains("vevo") || channel.contains(" - topic") || MUSIC_TITLE_MARKERS.any(lowerTitle::contains)
}

private val MUSIC_TITLE_MARKERS = listOf("official music video", "official video", "official audio", "(official)")

internal fun parseDurationTextToSeconds(text: String?): Int {
    if (text.isNullOrBlank()) return 0
    val parts = text.split(":").mapNotNull { it.trim().toIntOrNull() }
    return when (parts.size) {
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        2 -> parts[0] * 60 + parts[1]
        1 -> parts[0]
        else -> 0
    }
}

internal fun String?.isLiveViewCountText(): Boolean {
    if (isNullOrBlank()) return false
    val lower = lowercase(Locale.US)
    return lower.contains("watching") || lower.contains("viewer")
}

/**
 * Process-lifetime memo for video categories. Separate from the repository so the keep/skip rules
 * can be exercised without standing one up; a category never changes, so there is no TTL.
 */
private class PaidPromotionAnswer(
    val label: PaidPromotionLabel?,
)

internal class VideoCategoryMemo(
    private val maxEntries: Int = 500,
) {
    // A plain access-ordered map rather than android.util.LruCache: that one is an Android stub in
    // a JVM test and silently returns null, which would make these rules untestable.
    private val entries =
        object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > maxEntries
        }

    @Synchronized
    fun cached(videoId: String): String? = videoId.takeIf { it.isNotBlank() }?.let(entries::get)

    @Synchronized
    fun remember(
        videoId: String,
        category: String,
    ) {
        if (videoId.isBlank() || category.isBlank()) return
        entries[videoId] = category
    }
}

private val watchMetadataJson = Json { ignoreUnknownKeys = true }

/** The watch response as the typed model, or null when the payload no longer matches it. */
internal fun decodeWatchMetadata(raw: JsonElement): WatchMetadataResponse? =
    runCatching { watchMetadataJson.decodeFromJsonElement(WatchMetadataResponse.serializer(), raw) }.getOrNull()

internal object WatchMetadataVideoMapper {
    fun relatedVideos(resp: WatchMetadataResponse): List<Video> =
        resp.relatedVideos(YouTube.locale.hl).mapNotNull { cv ->
            val id = cv.videoId ?: return@mapNotNull null
            val viewText = cv.viewCountText?.text()
            val isLive = cv.isLive || viewText.isLiveViewCountText()
            val uploadDateText = cv.publishedTimeText?.text() ?: ""
            Video(
                id = id,
                title = cv.title?.text() ?: "",
                channelName = cv.longBylineText?.text() ?: "",
                channelId = cv.channelId().orEmpty(),
                thumbnailUrl =
                    cv.thumbnail?.bestUrl()?.let { ThumbnailUrlResolver.normalizeVideoThumbnail(id, it) }
                        ?: ThumbnailUrlResolver.buildHighQualityYoutubeThumbnail(id),
                channelThumbnailUrl =
                    cv.channelAvatarUrl?.let(ThumbnailUrlResolver::resolveChannelAvatar).orEmpty(),
                channelThumbnailUrls = cv.collaborators.map { it.thumbnailUrl }.filter { it.isNotBlank() },
                collaborators = cv.collaborators,
                duration = if (isLive) 0 else parseDurationTextToSeconds(cv.lengthText?.text()),
                viewCount = parseYouTubeViewCount(viewText),
                uploadDate = uploadDateText,
                // Video.timestamp defaults to now(), which made every related item
                // look brand new — defeating the age filter and shorts-shelf sort.
                // Parse the real age; 0 means unknown (callers fall back to text).
                timestamp = RelativeUploadDateParser.parse(uploadDateText, YouTube.locale.hl) ?: 0L,
                isLive = isLive,
            )
        }
}
