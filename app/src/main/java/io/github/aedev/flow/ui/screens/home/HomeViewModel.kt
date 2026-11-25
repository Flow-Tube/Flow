package io.github.aedev.flow.ui.screens.home

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.engagement.FeedInvalidationBus
import io.github.aedev.flow.data.feed.FeedPrefetchQueue
import io.github.aedev.flow.data.local.HomeFeedCacheRepository
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.local.WatchProgressStore
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.ChannelMemoryRepository
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.recommendation.NeighbourChannelsRepository
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.shorts.ShortsFeedRepository
import io.github.aedev.flow.data.subscriptions.HomeSubscriptionUploads
import io.github.aedev.flow.platform.memory.DeviceTier
import io.github.aedev.flow.platform.memory.MemoryGauges
import io.github.aedev.flow.ui.screens.home.chips.ChipFeedContext
import io.github.aedev.flow.ui.screens.home.chips.HomeChip
import io.github.aedev.flow.ui.screens.home.chips.HomeChipFeeds
import io.github.aedev.flow.ui.screens.home.chips.HomeChipsState
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val repository: YouTubeRepository,
        private val subscriptionRepository: SubscriptionRepository,
        private val subscriptionFeedRepository: io.github.aedev.flow.data.subscriptions.SubscriptionFeedRepository,
        private val shortsRepository: ShortsFeedRepository,
        private val playerPreferences: io.github.aedev.flow.data.local.PlayerPreferences,
        private val shortsQueueHandoff: io.github.aedev.flow.data.shorts.queue.ShortsQueueHandoff,
        private val feedSources: HomeFeedSources,
        private val homeSubscriptionUploads: HomeSubscriptionUploads,
        private val persistentHomeFeedCache: HomeFeedCacheRepository,
        private val viewHistory: ViewHistory,
        private val watchProgress: WatchProgressStore,
        private val channelMemory: ChannelMemoryRepository,
        private val chipFeeds: HomeChipFeeds,
        private val snapshotRecorder: HomeSnapshotRecorder,
        private val neighbourChannels: NeighbourChannelsRepository,
        private val memoryGauges: MemoryGauges,
        deviceTier: DeviceTier,
        savedStateHandle: SavedStateHandle,
        @ApplicationContext private val appContext: Context,
    ) : ViewModel() {
        fun shortsShelfSource(
            shelf: List<io.github.aedev.flow.data.model.Video>,
            tapped: io.github.aedev.flow.data.model.Video,
        ) = shortsQueueHandoff.sourceForShelf(shelf, tapped)

        companion object {
            private const val UI_STATE_SUBSCRIPTION_TIMEOUT_MS = 5_000L
            private const val HOME_FEED_GAUGE = "home"
        }

        private val session =
            HomeFeedSession(
                subscriptionRepository = subscriptionRepository,
                playerPreferences = playerPreferences,
                searchPages = HomeSearchPages({ query, continuation -> repository.searchVideoPage(query, continuation) }),
            )

        private val _uiState = session.uiState
        val uiState: StateFlow<HomeUiState> =
            _uiState
                .map(HomeUiState::withUniqueLazyContent)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(UI_STATE_SUBSCRIPTION_TIMEOUT_MS),
                    initialValue = _uiState.value.withUniqueLazyContent(),
                )

        private var isInitialized = false

        private val refreshFailureEvents = Channel<Unit>(Channel.CONFLATED)

        /** A refresh that failed while the old feed stayed on screen. */
        internal val refreshFailures: Flow<Unit> = refreshFailureEvents.receiveAsFlow()

        // Neighbour cards already counted as shown; an ignored neighbour is dropped after a few.
        private val shownNeighbourCards = ConcurrentHashMap.newKeySet<String>()

        private val enricher = HomeChannelEnricher(repository, session)

        private val wave2Discovery = HomeWave2Discovery(viewModelScope, session, persistentHomeFeedCache)

        private val lateMerges =
            HomeLateMerges(
                scope = viewModelScope,
                session = session,
                subscriptionRepository = subscriptionRepository,
                subscriptionFeedRepository = subscriptionFeedRepository,
                homeSubscriptionUploads = homeSubscriptionUploads,
                channelMemory = channelMemory,
                neighbourChannels = neighbourChannels,
                feedSources = feedSources,
                persistentHomeFeedCache = persistentHomeFeedCache,
            )

        private val feedLoader =
            HomeFeedLoader(
                scope = viewModelScope,
                session = session,
                appContext = appContext,
                repository = repository,
                subscriptionRepository = subscriptionRepository,
                subscriptionFeedRepository = subscriptionFeedRepository,
                playerPreferences = playerPreferences,
                feedSources = feedSources,
                persistentHomeFeedCache = persistentHomeFeedCache,
                channelMemory = channelMemory,
                neighbourChannels = neighbourChannels,
                chipFeeds = chipFeeds,
                snapshotRecorder = snapshotRecorder,
                enricher = enricher,
                wave2 = wave2Discovery,
                lateMerges = lateMerges,
                firstRound = HomeFirstRound.forHeap(deviceTier.isSmallHeap),
                onRefreshFailed = { refreshFailureEvents.trySend(Unit) },
            )

        private val loadMore: HomeLoadMore =
            FeedPrefetchQueue(
                prefetchAheadItemCount = HOME_PREFETCH_AHEAD_VIDEO_COUNT,
                triggerRemainingItems = HOME_PREFETCH_TRIGGER_REMAINING_VIDEOS,
            ).let { homePrefetchQueue ->
                HomeLoadMore(
                    scope = viewModelScope,
                    session = session,
                    homePrefetchQueue = homePrefetchQueue,
                    pages =
                        HomeLoadMorePages(
                            session = session,
                            homePrefetchQueue = homePrefetchQueue,
                            sources = HomeLoadMoreSources(session, feedSources, persistentHomeFeedCache, channelMemory),
                            enricher = enricher,
                            playerPreferences = playerPreferences,
                            persistentHomeFeedCache = persistentHomeFeedCache,
                        ),
                    wave2 = wave2Discovery,
                )
            }

        /** The chips above the feed and what the selected one shows; All alone hides the row while chips are off. */
        internal val chips: StateFlow<HomeChipsState> =
            combine(chipFeeds.state, playerPreferences.homeChipsEnabled) { state, enabled ->
                if (enabled) state else HomeChipsState(chips = listOf(HomeChip.All))
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(UI_STATE_SUBSCRIPTION_TIMEOUT_MS), chipFeeds.state.value)

        internal val newVideosButtonEnabled: StateFlow<Boolean> =
            playerPreferences.homeNewVideosButtonEnabled
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(UI_STATE_SUBSCRIPTION_TIMEOUT_MS), true)

        fun selectChip(key: String) = chipFeeds.select(key)

        fun refreshSelectedChip() = chipFeeds.refreshSelected()

        fun enrichChipVideo(video: Video) = chipFeeds.enrichVisible(video)

        private val feedSizeGauge = { _uiState.value.videos.size }

        init {
            memoryGauges.register(HOME_FEED_GAUGE, feedSizeGauge)
            viewModelScope.launch(PerformanceDispatcher.networkIO) { enricher.enrichLandedChannels() }
            chipFeeds.attach(
                scope = viewModelScope,
                savedState = savedStateHandle,
                context = ChipFeedContext(session::cacheFilters, session::feedExclusions) { session.watchedVideoIds.value },
            )
            viewModelScope.launch {
                // Turning chips off drops the selection too, so a chip's feed stops loading behind the main one.
                playerPreferences.homeChipsEnabled.distinctUntilChanged().collect { enabled ->
                    if (!enabled && !chipFeeds.state.value.isAll) chipFeeds.select(HomeChip.All.key)
                }
            }
            if (HomeFeedCache.isFresh()) {
                _uiState.update {
                    it.copy(
                        videos = HomeFeedCache.videos,
                        shorts = HomeFeedCache.shorts,
                        isLoading = false,
                        isFlowFeed = true,
                        lastRefreshTime = HomeFeedCache.timestamp,
                    )
                }
            } else {
                feedLoader.hydratePersistentHomeFeed()
                loadFlowFeed(forceRefresh = true)
            }
        }

        fun initialize(context: Context) {
            if (isInitialized) return
            isInitialized = true

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                combine(
                    watchProgress.videoProgress,
                    playerPreferences.hideWatchedVideosFromHome,
                    playerPreferences.watchedThreshold.combine(playerPreferences.countImportedAsWatched, ::Pair),
                    playerPreferences.continueWatchingEnabled,
                    playerPreferences.hideWatchedShorts,
                ) { history, hideWatched, (threshold, countImported), continueWatchingEnabled, hideWatchedShorts ->
                    filterHomeHistory(
                        history = history,
                        hideWatchedVideos = hideWatched,
                        watchedThreshold = threshold,
                        countImported = countImported,
                        continueWatchingEnabled = continueWatchingEnabled,
                        hideWatchedShorts = hideWatchedShorts,
                    )
                }.map { result ->
                    val entries =
                        if (result.continueWatchingIds.isEmpty()) {
                            emptyList()
                        } else {
                            viewHistory.getEntries(
                                result.continueWatchingIds,
                            )
                        }
                    result to orderContinueWatching(result.continueWatchingIds, entries)
                }.collect { (result, continueWatching) ->
                    session.watchedVideoIds.value = result.watchedVideoIds
                    session.watchedShortIds.value = result.watchedShortIds
                    _uiState.update { state ->
                        val videos = state.videos.filterWatched(result.watchedVideoIds)
                        val shorts = state.shorts.filterWatched(result.watchedShortIds)
                        if (videos != state.videos || shorts != state.shorts) {
                            HomeFeedCache.update(videos, shorts)
                        }
                        state.copy(
                            videos = videos,
                            shorts = shorts,
                            continueWatchingVideos = continueWatching,
                        )
                    }
                }
            }

            viewModelScope.launch {
                FlowNeuroEngine.initialize(context)
            }

            viewModelScope.launch {
                FeedInvalidationBus.events.collect { event ->
                    when (event) {
                        is FeedInvalidationBus.Event.ChannelBlocked -> {
                            dropChannelFromFeed(event.channelId, event.videoId)
                        }

                        is FeedInvalidationBus.Event.ChannelUnsubscribed -> {
                            dropChannelFromFeed(event.channelId)
                        }

                        is FeedInvalidationBus.Event.NotInterested -> {
                            HomeFeedCache.filterOut(videoId = event.videoId)
                            session.subsBacklog = session.subsBacklog.filter { it.id != event.videoId && it.channelId != event.channelId }
                            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                                persistentHomeFeedCache.deleteVideo(event.videoId)
                            }
                            _uiState.update { state ->
                                state.copy(
                                    videos = state.videos.filter { it.id != event.videoId },
                                    shorts = state.shorts.filter { it.id != event.videoId },
                                )
                            }
                            // Full clear — topic signals changed, discovery queries will differ
                            shortsRepository.clearCaches()
                        }

                        is FeedInvalidationBus.Event.MarkedWatched -> {
                            HomeFeedCache.filterOut(videoId = event.videoId)
                            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                                persistentHomeFeedCache.deleteVideo(event.videoId)
                            }
                            _uiState.update { state ->
                                state.copy(
                                    videos = state.videos.filter { it.id != event.videoId },
                                    shorts = state.shorts.filter { it.id != event.videoId },
                                )
                            }
                        }
                    }
                }
            }

            viewModelScope.launch {
                playerPreferences.homeSubscriptionsEnabled
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { reloadFeed() }
            }

            viewModelScope.launch {
                playerPreferences.effectiveHomeShortsShelfEnabled.distinctUntilChanged().collect { enabled ->
                    if (!enabled) {
                        _uiState.update { it.copy(shorts = emptyList()) }
                    } else if (_uiState.value.shorts.isEmpty() && !_uiState.value.isLoading) {
                        reloadFeed()
                    }
                }
            }
        }

        fun onHomeVisible() {
            loadMore.onVisible()
        }

        fun onHomeHidden() {
            loadMore.onHidden()

            wave2Discovery.cancel()
            lateMerges.cancelSavedInterest()
            _uiState.update { it.copy(isLoadingMore = false) }
        }

        fun onHomeViewportChanged(lastVisibleVideoIndex: Int) {
            session.lastVisibleVideoIndex = lastVisibleVideoIndex
            val state = _uiState.value
            if (state.pendingFeed != null) {
                // The held feed is the next page: it was already fetched, so nothing more is.
                if (nearsEndOfFeed(lastVisibleVideoIndex, state.videos.size)) settlePendingFeed(append = true)
                return
            }
            loadMore.onViewportChanged(state, lastVisibleVideoIndex)
        }

        /** The viewer's retry after a load-more that failed; the next scroll retries too. */
        fun retryLoadMore() = loadMore.retryLoadMore()

        fun removeContinueWatchingEntry(videoId: String) {
            viewModelScope.launch {
                viewHistory.clearVideoHistory(videoId)
            }
        }

        private fun dropChannelFromFeed(
            channelId: String,
            videoId: String? = null,
        ) {
            HomeFeedCache.filterOut(channelId = channelId, videoId = videoId)
            session.subsBacklog = session.subsBacklog.filter { it.channelId != channelId }
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                persistentHomeFeedCache.deleteChannel(channelId)
                videoId?.let { persistentHomeFeedCache.deleteVideo(it) }
            }
            _uiState.update { state ->
                state.copy(
                    videos = state.videos.filter { it.id != videoId && it.channelId != channelId },
                    shorts = state.shorts.filter { it.id != videoId && it.channelId != channelId },
                )
            }
            // Targeted eviction — preserves other channel caches in discovery engine
            shortsRepository.evictChannel(channelId)
        }

        fun loadFlowFeed(forceRefresh: Boolean = false) = feedLoader.loadFlowFeed(forceRefresh)

        /**
         * The viewer's refresh. A load already running is the refresh: the indicator stays until it
         * lands, and repeated pulls cannot stack pipelines (#1171).
         */
        fun refreshFeed() {
            if (_uiState.value.pendingFeed != null) {
                // A feed newer than the one on screen is already here: the refresh is showing it.
                settlePendingFeed(append = false)
                return
            }
            // A pull while the cached cards wait on wave 1 asks for them to be replaced after all.
            session.cachedOnScreenIds = null
            if (feedLoader.isLoadRunning) {
                _uiState.update { it.copy(isRefreshing = true) }
                return
            }
            reloadFeed()
        }

        /** The New videos button: the held feed replaces the cached cards. */
        fun showNewVideos() = settlePendingFeed(append = false)

        /**
         * Puts the held feed on screen, replacing the cached cards or, with [append], after them, and
         * ends the hold. Late merges already added to the cached cards are kept either way.
         */
        private fun settlePendingFeed(append: Boolean) {
            val cachedIds = session.cachedOnScreenIds.orEmpty()
            var settled: List<Video>? = null
            _uiState.update { state ->
                val pending = state.pendingFeed ?: return@update state
                val videos =
                    if (append) {
                        feedWithPendingAppended(state.videos, pending.videos)
                    } else {
                        feedWithPendingApplied(state.videos, cachedIds, pending.videos)
                    }.filterWatched(session.watchedVideoIds.value)
                settled = videos
                state.copy(videos = videos, shorts = pending.shorts, origins = state.origins + pending.origins, pendingFeed = null)
            }
            session.cachedOnScreenIds = null
            val videos = settled ?: return
            HomeFeedCache.update(videos, _uiState.value.shorts)
            viewModelScope.launch(PerformanceDispatcher.diskIO) { persistentHomeFeedCache.saveLastFeed(videos) }
        }

        /** Starts over, replacing a load already running: a changed setting must apply to the next feed. */
        private fun reloadFeed() {
            session.cachedOnScreenIds = null
            _uiState.update { it.copy(pendingFeed = null) }
            loadMore.resetHomePrefetch()
            wave2Discovery.cancel()
            HomeFeedCache.clear()
            _uiState.update { it.copy(isRefreshing = true) }
            loadFlowFeed(forceRefresh = true)
        }

        fun retry() {
            session.cachedOnScreenIds = null
            _uiState.update { it.copy(pendingFeed = null) }
            loadMore.resetHomePrefetch()
            wave2Discovery.cancel()
            loadFlowFeed(forceRefresh = true)
        }

        fun recordShelfImpressions(visibleIds: List<String>) {
            val knownIds = _uiState.value.shorts.mapTo(HashSet()) { it.id }
            val ids = feedImpressionIds(visibleIds, knownIds)
            if (ids.isEmpty()) return
            viewModelScope.launch { FlowNeuroEngine.recordFeedImpressions(ids) }
        }

        // Viewport impressions: count only items actually scrolled into view.
        fun recordImpressions(visibleKeys: List<String>) {
            if (visibleKeys.isEmpty()) return
            val knownIds = _uiState.value.videos.mapTo(HashSet()) { it.id }
            val ids = feedImpressionIds(visibleKeys, knownIds)
            if (ids.isEmpty()) return
            viewModelScope.launch { FlowNeuroEngine.recordFeedImpressions(ids) }
            val state = _uiState.value
            val neighbourChannelIds =
                ids
                    .filter { state.origins[it]?.kind == HomeCardOrigin.Kind.NEIGHBOUR && shownNeighbourCards.add(it) }
                    .mapNotNull { id -> state.videos.firstOrNull { it.id == id }?.channelId }
            if (neighbourChannelIds.isNotEmpty()) viewModelScope.launch { neighbourChannels.noteShown(neighbourChannelIds) }
        }

        override fun onCleared() {
            memoryGauges.unregister(HOME_FEED_GAUGE, feedSizeGauge)
        }
    }
