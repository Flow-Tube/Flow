package io.github.aedev.flow.ui.screens.player

import android.util.Log
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FeedExclusions
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.innertube.models.response.PaidPromotionLabel
import io.github.aedev.flow.innertube.models.response.VideoChapter
import io.github.aedev.flow.innertube.models.response.VideoHeatmap
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.PlayerRelatedVideosPolicy
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.utils.OriginalTextPolicy
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What a secondary metadata fetch resolved to, for the player screen to fold into its own state. */
internal sealed interface SecondaryMetadata {
    val videoId: String
    val loadToken: Long

    class Channel(
        override val videoId: String,
        override val loadToken: Long,
        val fetchedAvatarUrl: String?,
        val embeddedAvatarUrl: String?,
        val subscriberCount: Long?,
    ) : SecondaryMetadata

    class Related(
        override val videoId: String,
        override val loadToken: Long,
        val videos: List<Video>,
    ) : SecondaryMetadata

    class Category(
        override val videoId: String,
        override val loadToken: Long,
        val category: String,
    ) : SecondaryMetadata

    class PaidPromotion(
        override val videoId: String,
        override val loadToken: Long,
        val label: PaidPromotionLabel,
    ) : SecondaryMetadata

    class Heatmap(
        override val videoId: String,
        override val loadToken: Long,
        val heatmap: VideoHeatmap,
    ) : SecondaryMetadata

    class Chapters(
        override val videoId: String,
        override val loadToken: Long,
        val chapters: List<VideoChapter>,
    ) : SecondaryMetadata

    class WatchInfo(
        override val videoId: String,
        override val loadToken: Long,
        val video: Video,
    ) : SecondaryMetadata

    class LiveWatch(
        override val videoId: String,
        override val loadToken: Long,
        val video: Video,
        val relatedVideos: List<Video>,
        val channelAvatarUrl: String?,
        val subscriberCount: Long?,
    ) : SecondaryMetadata
}

/**
 * The metadata the player screen wants *after* the first frame: the channel avatar and subscriber
 * count, the related lane, the late NewPipe enrichment of an InnerTube-only load, and the live
 * watch refresh.
 *
 * Each concern holds at most one job, keyed by the video and the load it belongs to: a repeat call
 * for the same pair while that job is in flight is dropped, and any other pair cancels it. Nothing
 * here writes screen state — every outcome leaves through [onResult] for the caller to apply.
 */
internal class PlayerSecondaryMetadataLoader(
    private val repository: YouTubeRepository,
    private val playerManager: EnhancedPlayerManager,
    private val playerPreferences: PlayerPreferences,
    private val scope: CoroutineScope,
    private val networkDispatcher: CoroutineDispatcher,
    private val currentState: () -> VideoPlayerUiState,
    private val relatedVideosFor: (String) -> List<Video>,
    private val shortsEnabled: () -> Boolean,
    private val exclusions: () -> FeedExclusions,
    private val isPlaybackCurrent: (Long) -> Boolean,
    private val onResult: (SecondaryMetadata) -> Unit,
) {
    private class ConcurrentLoad {
        var job: Job? = null
        var videoId: String? = null
        var loadToken: Long = -1L

        fun claim(
            videoId: String,
            loadToken: Long,
        ): Boolean {
            if (this.videoId == videoId && this.loadToken == loadToken) {
                if (job?.isActive == true) return false
            } else {
                cancel()
            }
            this.videoId = videoId
            this.loadToken = loadToken
            return true
        }

        fun holds(
            videoId: String,
            loadToken: Long,
        ): Boolean = this.videoId == videoId && this.loadToken == loadToken

        fun takeOver(
            videoId: String,
            loadToken: Long,
        ) {
            cancel()
            this.videoId = videoId
            this.loadToken = loadToken
        }

        fun cancel() {
            job?.cancel()
            job = null
            videoId = null
            loadToken = -1L
        }
    }

    private val channelLoad = ConcurrentLoad()
    private val relatedLoad = ConcurrentLoad()
    private val liveWatchLoad = ConcurrentLoad()
    private val categoryLoad = ConcurrentLoad()
    private val heatmapLoad = ConcurrentLoad()
    private val chaptersLoad = ConcurrentLoad()
    private val paidPromotionLoad = ConcurrentLoad()
    private val watchInfoLoad = ConcurrentLoad()

    /** Drops every fetch in flight; the next load re-arms the ones it needs. */
    fun cancel() {
        channelLoad.cancel()
        relatedLoad.cancel()
        categoryLoad.cancel()
        heatmapLoad.cancel()
        chaptersLoad.cancel()
        paidPromotionLoad.cancel()
        watchInfoLoad.cancel()
    }

    /**
     * Whether the creator declared paid promotion. Only asked for while the switch is on, because no
     * response the player already fetches says so.
     */
    fun loadPaidPromotion(
        videoId: String,
        loadToken: Long,
    ) {
        if (!paidPromotionLoad.claim(videoId, loadToken)) return

        paidPromotionLoad.job =
            scope.launch(networkDispatcher) {
                if (!playerPreferences.paidPromotionLabel.first()) return@launch
                awaitPlaybackStarted(videoId)
                if (!isPlaybackCurrent(loadToken)) return@launch
                val label =
                    withTimeoutOrNull(PAID_PROMOTION_TIMEOUT_MS) { repository.paidPromotion(videoId) }
                        ?: return@launch
                if (!isPlaybackCurrent(loadToken) || !paidPromotionLoad.holds(videoId, loadToken)) return@launch
                onResult(SecondaryMetadata.PaidPromotion(videoId, loadToken, label))
            }
    }

    /** The rewatch curve, off the watch response the description and comments already fetch. */
    fun loadHeatmap(
        videoId: String,
        loadToken: Long,
    ) {
        if (!heatmapLoad.claim(videoId, loadToken)) return

        heatmapLoad.job =
            scope.launch(networkDispatcher) {
                awaitPlaybackStarted(videoId)
                if (!isPlaybackCurrent(loadToken)) return@launch
                val heatmap =
                    withTimeoutOrNull(HEATMAP_TIMEOUT_MS) { repository.videoHeatmap(videoId) }
                        ?.takeIf { !it.isEmpty } ?: return@launch
                if (!isPlaybackCurrent(loadToken) || !heatmapLoad.holds(videoId, loadToken)) return@launch
                onResult(SecondaryMetadata.Heatmap(videoId, loadToken, heatmap))
            }
    }

    /**
     * The chapter list, off the same watch response.
     *
     * Not held behind [awaitPlaybackStarted] like the read-outs around it: chapters are drawn into
     * the seek bar itself, so arriving after the first scrub means the bar changes shape under the
     * finger already dragging it.
     */
    fun loadChapters(
        videoId: String,
        loadToken: Long,
    ) {
        if (!chaptersLoad.claim(videoId, loadToken)) return

        chaptersLoad.job =
            scope.launch(networkDispatcher) {
                val chapters =
                    withTimeoutOrNull(CHAPTERS_TIMEOUT_MS) { repository.videoChapters(videoId) }
                        ?.takeIf { it.isNotEmpty() } ?: return@launch
                if (!isPlaybackCurrent(loadToken) || !chaptersLoad.holds(videoId, loadToken)) return@launch
                onResult(SecondaryMetadata.Chapters(videoId, loadToken, chapters))
            }
    }

    /**
     * The counts, date and description the watch page carries and the player response does not.
     *
     * The extractor used to supply these as late metadata over a load it had lost the race for;
     * without it the screen showed whatever the card that opened it happened to hold, which is a
     * title, a channel and nothing else.
     */
    fun loadWatchInfo(
        videoId: String,
        video: Video,
        loadToken: Long,
    ) {
        if (!watchInfoLoad.claim(videoId, loadToken)) return

        watchInfoLoad.job =
            scope.launch(networkDispatcher) {
                awaitPlaybackStarted(videoId)
                if (!isPlaybackCurrent(loadToken)) return@launch
                val merged =
                    withTimeoutOrNull(WATCH_INFO_TIMEOUT_MS) { repository.enrichFromWatchMetadata(video) }
                        ?: return@launch
                val enriched = OriginalTextPolicy.keepOriginalText(merged, video, playerPreferences.originalTitles.first())
                if (!isPlaybackCurrent(loadToken) || !watchInfoLoad.holds(videoId, loadToken)) return@launch
                onResult(SecondaryMetadata.WatchInfo(videoId, loadToken, enriched))
            }
    }

    /**
     * The creator-declared category, which costs its own small request because no client that
     * serves playable streams returns one. Kept behind [awaitPlaybackStarted] like the other
     * secondary fetches, and still lands well before the watch signal the engine learns from.
     */
    fun loadCategory(
        videoId: String,
        loadToken: Long,
    ) {
        if (!categoryLoad.claim(videoId, loadToken)) return

        categoryLoad.job =
            scope.launch(networkDispatcher) {
                awaitPlaybackStarted(videoId)
                if (!isPlaybackCurrent(loadToken)) return@launch
                val category =
                    withTimeoutOrNull(CATEGORY_TIMEOUT_MS) { repository.videoCategory(videoId) }
                        ?.takeIf { it.isNotBlank() } ?: return@launch
                if (!isPlaybackCurrent(loadToken) || !categoryLoad.holds(videoId, loadToken)) return@launch
                onResult(SecondaryMetadata.Category(videoId, loadToken, category))
            }
    }

    fun loadChannelMetadata(
        videoId: String,
        embeddedAvatarUrls: List<String>,
        loadToken: Long,
        awaitPlayback: Boolean = true,
    ) {
        val embeddedAvatar =
            embeddedAvatarUrls
                .firstOrNull()
                ?.let(ThumbnailUrlResolver::resolveChannelAvatar)
                ?.takeIf { it.isNotBlank() }
        if (embeddedAvatar != null) {
            onResult(
                SecondaryMetadata.Channel(
                    videoId = videoId,
                    loadToken = loadToken,
                    fetchedAvatarUrl = embeddedAvatar,
                    embeddedAvatarUrl = null,
                    subscriberCount = null,
                ),
            )
        }

        if (LocalMediaIds.isLocal(videoId)) return
        if (!channelLoad.claim(videoId, loadToken)) return

        channelLoad.job =
            scope.launch(networkDispatcher) {
                if (awaitPlayback) awaitPlaybackStarted(videoId)
                if (!isPlaybackCurrent(loadToken)) return@launch

                val channel =
                    withTimeoutOrNull(CHANNEL_INFO_TIMEOUT_MS) { repository.watchChannel(videoId) }
                        ?: return@launch
                if (!isPlaybackCurrent(loadToken) || !channelLoad.holds(videoId, loadToken)) return@launch

                onResult(
                    SecondaryMetadata.Channel(
                        videoId = videoId,
                        loadToken = loadToken,
                        fetchedAvatarUrl =
                            channel.avatarUrl
                                ?.let(ThumbnailUrlResolver::resolveChannelAvatar)
                                ?.takeIf { it.isNotBlank() },
                        embeddedAvatarUrl = embeddedAvatar,
                        subscriberCount = channel.subscriberCount?.takeIf { it > 0L },
                    ),
                )
            }
    }

    fun loadRelatedVideos(
        videoId: String,
        primaryCandidates: List<Video>,
        loadToken: Long,
        awaitPlayback: Boolean = true,
    ) {
        val selected =
            PlayerRelatedVideosPolicy.select(
                videoId = videoId,
                primary = primaryCandidates,
                fallback = playerManager.relatedCandidatesFor(videoId),
                current = relatedVideosFor(videoId),
                shortsEnabled = shortsEnabled(),
                exclusions = exclusions(),
            )
        if (selected.isNotEmpty()) {
            relatedLoad.takeOver(videoId, loadToken)
            publish(videoId, selected, loadToken)
            return
        }

        if (!relatedLoad.claim(videoId, loadToken)) return

        relatedLoad.job =
            scope.launch(networkDispatcher) {
                // Keep this request off the critical startup path. It is only needed when the
                // playback resolver did not provide related items with its initial metadata.
                if (awaitPlayback) awaitPlaybackStarted(videoId)
                if (!isPlaybackCurrent(loadToken) || !relatedLoad.holds(videoId, loadToken)) return@launch

                val managerCandidates =
                    PlayerRelatedVideosPolicy.sanitize(
                        videoId = videoId,
                        candidates = playerManager.relatedCandidatesFor(videoId),
                        shortsEnabled = shortsEnabled(),
                        exclusions = exclusions(),
                    )
                if (managerCandidates.isNotEmpty()) {
                    publish(videoId, managerCandidates, loadToken)
                    return@launch
                }

                val fallbackCandidates =
                    withTimeoutOrNull(RELATED_FALLBACK_TIMEOUT_MS) {
                        repository.getRelatedCandidates(videoId)
                    }.orEmpty()
                if (!isPlaybackCurrent(loadToken) || !relatedLoad.holds(videoId, loadToken)) return@launch

                val resolved =
                    PlayerRelatedVideosPolicy.select(
                        videoId = videoId,
                        primary = primaryCandidates,
                        fallback = fallbackCandidates,
                        current = currentState().relatedVideos,
                        shortsEnabled = shortsEnabled(),
                        exclusions = exclusions(),
                    )
                if (resolved.isNotEmpty()) {
                    publish(videoId, resolved, loadToken)
                } else {
                    Log.d(TAG, "No related videos resolved for $videoId")
                }
            }
    }

    /**
     * Folds the NewPipe metadata a fast InnerTube load raced past into the screen once it lands,
     * then re-arms the related lane and the channel request from the richer references it carries.
     */
    fun refreshLiveWatchMetadata(
        videoId: String,
        fallbackVideo: Video,
        loadToken: Long,
    ) {
        if (!liveWatchLoad.claim(videoId, loadToken)) return

        liveWatchLoad.job =
            scope.launch(networkDispatcher) {
                val newPipeMeta =
                    withTimeoutOrNull(LIVE_NEWPIPE_METADATA_TIMEOUT_MS) {
                        repository.getLiveWatchMetadataFromNewPipe(videoId)
                    }
                val innerTubeMeta =
                    if (
                        newPipeMeta == null ||
                        newPipeMeta.relatedVideos.isEmpty() ||
                        newPipeMeta.subscriberCount == null
                    ) {
                        withTimeoutOrNull(LIVE_INNERTUBE_METADATA_TIMEOUT_MS) { repository.getLiveWatchMetadata(videoId) }
                    } else {
                        null
                    }
                val meta = newPipeMeta ?: innerTubeMeta ?: return@launch
                if (!isPlaybackCurrent(loadToken)) return@launch

                val likes =
                    if (playerPreferences.rytdEnabled.first()) {
                        withTimeoutOrNull(RYD_TIMEOUT_MS) { repository.returnYouTubeDislikeCounts(videoId) }?.likes
                    } else {
                        null
                    }
                val refreshed =
                    fallbackVideo.copy(
                        title = meta.title?.takeIf { it.isNotBlank() } ?: fallbackVideo.title,
                        channelName = meta.channelName?.takeIf { it.isNotBlank() } ?: fallbackVideo.channelName,
                        channelId = meta.channelId?.takeIf { it.isNotBlank() } ?: fallbackVideo.channelId,
                        description = meta.description ?: fallbackVideo.description,
                        channelThumbnailUrl = meta.channelAvatarUrl ?: fallbackVideo.channelThumbnailUrl,
                        viewCount = meta.viewCount ?: fallbackVideo.viewCount,
                        likeCount = likes ?: fallbackVideo.likeCount,
                        isLive = true,
                    )
                val enriched = OriginalTextPolicy.keepOriginalText(refreshed, fallbackVideo, playerPreferences.originalTitles.first())
                val metadataRelated =
                    PlayerRelatedVideosPolicy.sanitize(
                        videoId = videoId,
                        candidates =
                            newPipeMeta?.relatedVideos?.takeIf { it.isNotEmpty() }
                                ?: innerTubeMeta?.relatedVideos
                                ?: meta.relatedVideos,
                        shortsEnabled = shortsEnabled(),
                        exclusions = exclusions(),
                    )
                val related =
                    metadataRelated.ifEmpty {
                        withTimeoutOrNull(LIVE_RELATED_SEARCH_TIMEOUT_MS) {
                            repository.getLiveRelatedVideosBySearch(
                                videoId = videoId,
                                title = enriched.title,
                                channelName = enriched.channelName,
                            )
                        }.orEmpty()
                    }

                withContext(Dispatchers.Main) {
                    onResult(
                        SecondaryMetadata.LiveWatch(
                            videoId = videoId,
                            loadToken = loadToken,
                            video = enriched,
                            relatedVideos = related,
                            channelAvatarUrl = meta.channelAvatarUrl,
                            subscriberCount = meta.subscriberCount ?: innerTubeMeta?.subscriberCount,
                        ),
                    )
                }
            }
    }

    private fun publish(
        videoId: String,
        videos: List<Video>,
        loadToken: Long,
    ) = onResult(SecondaryMetadata.Related(videoId = videoId, loadToken = loadToken, videos = videos))

    private suspend fun awaitPlaybackStarted(videoId: String) {
        withTimeoutOrNull(PLAYBACK_STARTED_TIMEOUT_MS) {
            playerManager.playerState.first { state ->
                state.currentVideoId == videoId && (state.isPlaying || state.hasEnded || state.error != null)
            }
        }
    }

    private companion object {
        const val TAG = "PlayerSecondaryMetadata"
        const val PLAYBACK_STARTED_TIMEOUT_MS = 15_000L
        const val CHANNEL_INFO_TIMEOUT_MS = 8_000L
        const val RELATED_FALLBACK_TIMEOUT_MS = 10_000L
        const val LIVE_NEWPIPE_METADATA_TIMEOUT_MS = 12_000L
        const val LIVE_INNERTUBE_METADATA_TIMEOUT_MS = 8_000L
        const val LIVE_RELATED_SEARCH_TIMEOUT_MS = 8_000L
        const val RYD_TIMEOUT_MS = 5_000L
        const val CATEGORY_TIMEOUT_MS = 8_000L
        const val HEATMAP_TIMEOUT_MS = 8_000L
        const val CHAPTERS_TIMEOUT_MS = 8_000L
        const val PAID_PROMOTION_TIMEOUT_MS = 8_000L
        const val WATCH_INFO_TIMEOUT_MS = 8_000L
    }
}
