package io.github.aedev.flow.ui.screens.shorts

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.comments.CommentsPager
import io.github.aedev.flow.data.comments.VideoCommentsSource
import io.github.aedev.flow.data.engagement.FeedInvalidationBus
import io.github.aedev.flow.data.engagement.VideoEngagementUseCase
import io.github.aedev.flow.data.feed.FeedPrefetchQueue
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.model.ShortVideo
import io.github.aedev.flow.data.model.toVideo
import io.github.aedev.flow.data.recommendation.BlockedChannelNames
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.recommendation.InteractionType
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.shorts.ShortAudioTrack
import io.github.aedev.flow.data.shorts.ShortPlaybackStreams
import io.github.aedev.flow.data.shorts.ShortVideoQuality
import io.github.aedev.flow.data.shorts.ShortsCommentsSource
import io.github.aedev.flow.data.shorts.ShortsFeedRepository
import io.github.aedev.flow.data.shorts.ShortsMetadataRepository
import io.github.aedev.flow.data.shorts.ShortsStreamResolver
import io.github.aedev.flow.data.shorts.ShortsWatchHistory
import io.github.aedev.flow.data.shorts.queue.ShortsQueueChange
import io.github.aedev.flow.data.shorts.queue.ShortsQueueController
import io.github.aedev.flow.data.shorts.queue.ShortsQueueLoaderFactory
import io.github.aedev.flow.data.shorts.queue.ShortsQueueSource
import io.github.aedev.flow.data.shorts.queue.openAtVideoId
import io.github.aedev.flow.data.stats.LedgerAction
import io.github.aedev.flow.data.stats.VideoStatsRecorder
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.innertube.pages.VideoCommentSort
import io.github.aedev.flow.innertube.pages.VideoDescriptionPage
import io.github.aedev.flow.player.stream.StreamSizeEstimator
import io.github.aedev.flow.utils.LoadErrorKind
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShortsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: YouTubeRepository,
        private val feed: ShortsFeedRepository,
        private val streams: ShortsStreamResolver,
        private val metadata: ShortsMetadataRepository,
        private val engagement: VideoEngagementUseCase,
        private val playlistRepository: PlaylistRepository,
        private val watchHistory: ShortsWatchHistory,
        private val queueFactory: ShortsQueueLoaderFactory,
        private val playerPreferences: PlayerPreferences,
        private val videoStats: VideoStatsRecorder,
        private val blockedNames: BlockedChannelNames,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ShortsUiState())
        val uiState: StateFlow<ShortsUiState> = _uiState.asStateFlow()

        private var queue: ShortsQueueController? = null
        private var loadedSource: ShortsQueueSource? = null
        private var loadJob: Job? = null

        private val prefetch =
            FeedPrefetchQueue(
                prefetchAheadItemCount = PREFETCH_AHEAD_REELS,
                triggerRemainingItems = PREFETCH_TRIGGER_REMAINING,
            )

        private val comments =
            CommentsPager(
                source = ShortsCommentsSource(metadata, fallback = VideoCommentsSource(repository)),
                scope = viewModelScope,
                fetchTimeoutMs = COMMENTS_FETCH_TIMEOUT_MS,
            )

        val commentsState: StateFlow<List<Comment>> = comments.comments
        val isLoadingComments: StateFlow<Boolean> = comments.isLoading
        val commentSortOptions: StateFlow<List<VideoCommentSort>> = comments.sortOptions
        val isLoadingMoreComments: StateFlow<Boolean> = comments.isLoadingMore
        val hasMoreComments: StateFlow<Boolean> = comments.hasMore
        val commentTotalText: StateFlow<String?> = comments.totalText
        val commentsDisabled: StateFlow<Boolean> = comments.isDisabled

        private val enricher =
            ShortsReelEnricher(
                scope = viewModelScope,
                repository = repository,
                feed = feed,
                metadata = metadata,
                playerPreferences = playerPreferences,
                shorts = { _uiState.value.shorts },
                queue = { queue },
                publishQueue = ::publishQueue,
            )

        private val watchSignals = ShortsWatchSignals(viewModelScope, watchHistory, videoStats)

        private val engagementState = ShortsEngagementState(viewModelScope, engagement, playlistRepository)

        private val _snackbarMessage = MutableStateFlow<String?>(null)
        val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

        fun clearSnackbar() {
            _snackbarMessage.value = null
        }

        init {
            engagementState.collectSavedShorts()
            viewModelScope.launch {
                FeedInvalidationBus.events.collect { event ->
                    if (event is FeedInvalidationBus.Event.ChannelBlocked) dropChannel(event.channelId)
                }
            }
        }

        /** Mirrors the controller's state into [uiState], the single thing the screen observes. */
        private fun publishQueue() {
            val controller = queue ?: return
            _uiState.value =
                _uiState.value.copy(
                    shorts = controller.items.value,
                    currentIndex = controller.currentIndex.value,
                    isLoadingMore = controller.isLoadingMore.value,
                )
        }

        fun isVideoLikedState(videoId: String): StateFlow<Boolean> = engagementState.isLiked(videoId)

        fun isChannelSubscribedState(channelId: String): StateFlow<Boolean> = engagementState.isSubscribed(channelId)

        fun isShortSavedState(videoId: String): StateFlow<Boolean> = engagementState.isSaved(videoId)

        /**
         * Opens the queue for [source]. Every surface funnels through here; which loader that needs,
         * and whether the algorithmic feed follows it, is [ShortsQueueLoaderFactory]'s decision.
         *
         * Idempotent for the same source: re-entering the screen must not refetch or reset the
         * position. A different source rebuilds, because a reused back stack entry hands this
         * ViewModel a new source rather than a new ViewModel.
         */
        fun load(source: ShortsQueueSource) {
            if (source == loadedSource && (queue != null || _uiState.value.isLoading)) return
            loadJob?.cancel()
            loadedSource = source

            val resolved = queueFactory.resolve(source)
            val controller = queueFactory.create(resolved)
            queue = controller
            _uiState.value = ShortsUiState(isLoading = true)

            // Resolving the tapped short's streams starts now rather than after the queue loads, so
            // playback is not gated on whichever network call the source happens to need.
            resolved.openAtVideoId?.let { prefetchPlaybackStreams(listOf(it)) }

            loadJob =
                viewModelScope.launch(PerformanceDispatcher.networkIO) {
                    try {
                        controller.loadInitial(resolved.openAtVideoId)
                        _uiState.value = _uiState.value.copy(isLoading = false)
                        publishQueue()

                        val items = controller.items.value
                        val at = controller.currentIndex.value
                        prefetchPlaybackStreams(listOfNotNull(items.getOrNull(at)?.id, items.getOrNull(at + 1)?.id))
                        onScreenVisible()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading shorts queue", e)
                        queue = null
                        _uiState.value =
                            _uiState.value.copy(
                                isLoading = false,
                                error = context.getString(LoadErrorKind.of(e).messageRes(R.string.error_failed_to_load_shorts)),
                            )
                    }
                }
        }

        fun retry(source: ShortsQueueSource) {
            queue = null
            loadedSource = null
            _uiState.value = _uiState.value.copy(error = null)
            load(source)
        }

        fun loadMoreShorts() {
            val controller = queue ?: return
            if (!controller.hasMore || controller.isLoadingMore.value) return

            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                _uiState.value = _uiState.value.copy(isLoadingMore = true)
                val before = controller.items.value.size
                try {
                    controller.loadMore()
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading more shorts", e)
                } finally {
                    _uiState.value = _uiState.value.copy(isLoadingMore = false)
                    publishQueue()
                }
                val after = controller.items.value.size
                if (after > before && prefetch.currentRequest(after) != null) loadMoreShorts()
            }
        }

        private fun prefetchPlaybackStreams(videoIds: List<String>) {
            if (videoIds.isEmpty()) return
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                val targetHeight =
                    shortsTargetHeight(
                        isWifi = isOnWifi(context),
                        wifiQuality = playerPreferences.shortsQualityWifi.first(),
                        cellularQuality = playerPreferences.shortsQualityCellular.first(),
                    )
                val preferredLang = playerPreferences.preferredAudioLanguage.first()
                videoIds.forEach { id ->
                    launch { runCatching { getPlaybackStreams(id, targetHeight, preferredLang) } }
                }
            }
        }

        /**
         * The pager's single report of where the user is. Also the only place that decides to page
         * ahead.
         */
        fun updateCurrentIndex(index: Int) {
            val controller = queue ?: return
            controller.setCurrentIndex(index)
            _uiState.value = _uiState.value.copy(currentIndex = controller.currentIndex.value)

            prefetch
                .onViewportChanged(currentItemCount = controller.items.value.size, lastVisibleItemIndex = index)
                ?.let { loadMoreShorts() }
        }

        /** A reel counts as seen once it has actually been on screen for a moment, never when fetched. */
        fun onReelShown(videoId: String) {
            viewModelScope.launch(PerformanceDispatcher.diskIO) { feed.recordShown(videoId) }
        }

        /**
         * Streams for [videoId]; the `/player` response that carries them also names the reel, so
         * the queue learns its title, channel and view count from the same request.
         */
        suspend fun getPlaybackStreams(
            videoId: String,
            targetHeight: Int,
            preferredAudioLanguage: String,
        ): ShortPlaybackStreams? {
            val resolved = streams.resolve(videoId, targetHeight, preferredAudioLanguage) ?: return null
            resolved.details?.let { enricher.applyDetails(videoId, it) }
            return resolved
        }

        suspend fun availableQualities(videoId: String): List<ShortVideoQuality> = streams.availableQualities(videoId)

        suspend fun availableAudioTracks(videoId: String): List<ShortAudioTrack> = streams.availableAudioTracks(videoId)

        suspend fun downloadFormats(
            videoId: String,
        ): Pair<List<PlayerResponse.StreamingData.Format>, List<PlayerResponse.StreamingData.Format>> = streams.downloadFormats(videoId)

        /** Total download size per `(resolution, codec)` pair. Pure: nothing here goes to the network. */
        suspend fun streamSizesFor(
            videoId: String,
            videoFormats: List<PlayerResponse.StreamingData.Format>,
            audioFormats: List<PlayerResponse.StreamingData.Format>,
        ): Map<String, Long> = StreamSizeEstimator.fromInnerTubeFormats(videoFormats, audioFormats, streams.durationMs(videoId) ?: 0L)

        fun loadShortDetails(videoId: String) = enricher.loadDetails(videoId)

        fun loadShortDescription(videoId: String) = enricher.loadDescription(videoId)

        val shortDescriptionPages: StateFlow<Map<String, VideoDescriptionPage>> = enricher.descriptionPages

        suspend fun toggleLike(short: ShortVideo) = engagementState.toggleLike(short)

        suspend fun toggleSubscription(
            channelId: String,
            channelName: String,
            channelThumbnail: String,
        ) = engagementState.toggleSubscription(channelId, channelName, channelThumbnail)

        fun toggleSaveShort(short: ShortVideo) = engagementState.toggleSave(short)

        fun recordShortProgress(
            short: ShortVideo,
            positionMs: Long,
            durationMs: Long,
        ) = watchSignals.recordProgress(short, positionMs, durationMs)

        fun recordShortAbandoned(
            short: ShortVideo,
            positionMs: Long,
            durationMs: Long,
        ) = watchSignals.recordAbandoned(short, positionMs, durationMs)

        fun recordShortWatched(
            short: ShortVideo,
            positionMs: Long,
            durationMs: Long,
        ) = watchSignals.recordWatched(short, positionMs, durationMs)

        fun loadComments(videoId: String) = comments.load(videoId)

        fun selectCommentSort(
            videoId: String,
            sort: VideoCommentSort,
            readAhead: Boolean,
        ) = comments.selectSort(videoId, sort, readAhead)

        fun selectTimedComments(
            videoId: String,
            orders: List<VideoCommentSort>,
        ) = comments.selectTimed(videoId, orders)

        fun loadMoreComments(videoId: String) = comments.loadMore(videoId)

        fun loadCommentReplies(comment: Comment) {
            val currentShort = _uiState.value.shorts.getOrNull(_uiState.value.currentIndex) ?: return
            comments.loadReplies(currentShort.id, comment)
        }

        /** Opens a related chain from [short] and interleaves its first page right after the current reel. */
        fun wantMoreLikeThis(short: ShortVideo) {
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                try {
                    FlowNeuroEngine.onVideoInteraction(short.toVideo(), InteractionType.LIKED)
                    val page = feed.chainFrom(short)
                    if (queue?.mergeDiscovery(page) != ShortsQueueChange.None) publishQueue()
                    _snackbarMessage.value = context.getString(R.string.shorts_showing_more_like_this)
                } catch (e: Exception) {
                    Log.e(TAG, "Error signaling want more", e)
                }
            }
        }

        /** A reel the user stayed on becomes a weak seed for the chains that follow this session. */
        fun onReelDwelled(short: ShortVideo) = feed.noteDwell(short)

        fun onScreenVisible() {
            prefetch
                .onVisible(currentItemCount = queue?.items?.value?.size ?: 0, feedReady = queue != null)
                ?.let { loadMoreShorts() }
        }

        fun onScreenHidden() = prefetch.onHidden()

        /** "Don't show this channel": the same permanent block the Home feed's sheet applies. */
        fun blockChannel(short: ShortVideo) {
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                try {
                    // A handle in place of the channel id would block a key no feed matches.
                    val metadata = if (short.channelId.startsWith("UC")) null else repository.getLiveWatchMetadata(short.id)
                    val channelId = metadata?.channelId.orEmpty().ifBlank { short.channelId }
                    check(channelId.isNotBlank()) { context.getString(R.string.channel_metadata_unavailable) }
                    val channelName = metadata?.channelName.orEmpty().ifBlank { short.channelName }
                    FlowNeuroEngine.blockChannel(context, channelId)
                    blockedNames.remember(channelId, channelName)
                    videoStats.onAction(LedgerAction.BLOCK_CHANNEL)
                    dropChannel(channelId)
                    FeedInvalidationBus.emit(FeedInvalidationBus.Event.ChannelBlocked(channelId, short.id))
                    _snackbarMessage.value = context.getString(R.string.channel_blocked_toast, channelName)
                } catch (e: Exception) {
                    Log.e(TAG, "Error blocking channel", e)
                    _snackbarMessage.value = context.getString(R.string.quick_actions_error_template, e.message)
                }
            }
        }

        private fun dropChannel(channelId: String) {
            feed.evictChannel(channelId)
            if (queue?.removeChannel(channelId) != ShortsQueueChange.None) publishQueue()
        }

        fun notInterested(short: ShortVideo) {
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                try {
                    val video = short.toVideo()
                    FlowNeuroEngine.markNotInterested(video)
                    videoStats.onAction(LedgerAction.NOT_INTERESTED)
                    FeedInvalidationBus.emit(FeedInvalidationBus.Event.NotInterested(video.id, video.channelId))

                    queue?.remove(short.id)
                    publishQueue()

                    _snackbarMessage.value = context.getString(R.string.shorts_showing_less_like_this)
                } catch (e: Exception) {
                    Log.e(TAG, "Error marking not interested", e)
                }
            }
        }

        companion object {
            private const val TAG = "ShortsViewModel"

            /** Reels kept loaded past the one on screen, and how few remaining arm the next page. */
            private const val PREFETCH_AHEAD_REELS = 12
            private const val PREFETCH_TRIGGER_REMAINING = 6

            private const val COMMENTS_FETCH_TIMEOUT_MS = 10_000L
        }
    }
