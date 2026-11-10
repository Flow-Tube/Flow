package io.github.aedev.flow.ui.screens.subscriptions

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.backup.BackupCoordinator
import io.github.aedev.flow.data.engagement.FeedInvalidationBus
import io.github.aedev.flow.data.innertube.SubscriptionRefreshStage
import io.github.aedev.flow.data.local.ChannelSubscription
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.local.dao.SubscriptionGroupDao
import io.github.aedev.flow.data.local.dao.WatchHistoryDao
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.model.toUiModel
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.subscriptions.SubscriptionFeedRepository
import io.github.aedev.flow.data.subscriptions.SubscriptionRefreshPlan
import io.github.aedev.flow.data.subscriptions.SubscriptionWatchedVideos
import io.github.aedev.flow.data.subscriptions.withHighQualityThumbnails
import io.github.aedev.flow.utils.PerformanceDispatcher
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import io.github.aedev.flow.utils.formatYouTubeRelativeTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel as EventChannel

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SubscriptionsViewModel
    @Inject
    constructor(
        private val subscriptionRepository: SubscriptionRepository,
        private val subscriptionFeedRepository: SubscriptionFeedRepository,
        private val playerPreferences: PlayerPreferences,
        private val subscriptionGroupDao: SubscriptionGroupDao,
        private val subscriptionWatchedVideos: SubscriptionWatchedVideos,
        private val neuroEngine: FlowNeuroEngine,
        private val backupCoordinator: BackupCoordinator,
        private val watchHistoryDao: WatchHistoryDao,
    ) : ViewModel() {
        companion object {
            private const val TAG = "SubsViewModel"

            private const val RELATIVE_TIME_TICK_MS = 60L * 1000L
        }

        private val _uiState = MutableStateFlow(SubscriptionsUiState())
        val uiState: StateFlow<SubscriptionsUiState> = _uiState.asStateFlow()

        private var latestFeedVideos: List<Video> = emptyList()
        private var watchedVideoIds: Set<String> = emptySet()
        private var watchedShortIds: Set<String> = emptySet()
        private var unplayableVideoIds: Set<String> = emptySet()
        private var excludedShortsChannelIds: Set<String> = emptySet()
        private var hasStarted = false

        /**
         * Starts the preference/feed collectors. Deliberately not run from `init`: the TV shell
         * hoists this ViewModel at launch (see `FlowTvApp`), so constructing it would kick off the
         * subscription RSS fetch before the user ever opens Subscriptions. Called from the screens
         * instead, so the work still begins exactly when the feed becomes visible.
         *
         * Idempotent, and only ever called from composition (main thread).
         */
        fun ensureStarted() {
            if (hasStarted) return
            hasStarted = true

            val subscriptions =
                subscriptionRepository
                    .getAllSubscriptions()
                    .flowOn(PerformanceDispatcher.diskIO)
                    .conflate()
                    .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                subscriptionGroupDao.getAllGroups().collect { entities ->
                    val groups = entities.map { it.toUiModel() }
                    _uiState.update { it.copy(groups = groups) }
                }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                playerPreferences.effectiveShortsShelfEnabled.collect { enabled ->
                    _uiState.update { it.copy(isShortsShelfEnabled = enabled) }
                }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                combine(
                    playerPreferences.subscriptionShowVideos,
                    playerPreferences.effectiveSubscriptionShowShorts,
                    playerPreferences.subscriptionShowLive,
                    playerPreferences.subscriptionShowUpcoming,
                ) { showVideos, showShorts, showLive, showUpcoming ->
                    listOf(showVideos, showShorts, showLive, showUpcoming)
                }.distinctUntilChanged()
                    .collect { (showVideos, showShorts, showLive, showUpcoming) ->
                        _uiState.update {
                            it.copy(
                                showSubscriptionVideos = showVideos,
                                showSubscriptionShorts = showShorts,
                                showSubscriptionLive = showLive,
                                showSubscriptionUpcoming = showUpcoming,
                            )
                        }
                        refreshVisibleFeed()
                    }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                playerPreferences.subscriptionShortsExcludedChannels
                    .distinctUntilChanged()
                    .collect { ids ->
                        excludedShortsChannelIds = ids
                        _uiState.update { it.copy(excludedShortsChannelIds = ids) }
                        refreshVisibleFeed()
                    }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                playerPreferences.subsFullWidthView.collect { fullWidth ->
                    _uiState.update { it.copy(isFullWidthView = fullWidth) }
                }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                playerPreferences.subsSortMode.collect { stored ->
                    val mode = SubscriptionSortMode.fromStorage(stored)
                    val counts = if (mode == SubscriptionSortMode.MOST_WATCHED) watchHistoryDao.channelWatchCounts() else emptyMap()
                    _uiState.update { it.copy(sortMode = mode, channelWatchCounts = counts) }
                }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                playerPreferences.selectedSubscriptionGroup.collect { groupName ->
                    _uiState.update { it.copy(selectedGroupName = groupName) }
                    refreshVisibleFeed()
                }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                combine(
                    playerPreferences.subscriptionUngroupedEnabled,
                    playerPreferences.subscriptionUngroupedPosition,
                    playerPreferences.subscriptionPostsChip,
                    ::Triple,
                ).distinctUntilChanged()
                    .collect { (enabled, position, postsChip) ->
                        _uiState.update { it.copy(ungroupedEnabled = enabled, ungroupedPosition = position, showPostsChip = postsChip) }
                        refreshVisibleFeed()
                    }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                playerPreferences.subscriptionPinnedChannels.collect { stored ->
                    _uiState.update { it.copy(pinnedChannelIds = parsePinnedChannels(stored)) }
                }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                combine(
                    playerPreferences.subscriptionLastRefreshTime,
                    playerPreferences.subscriptionLastRefreshedCount,
                    playerPreferences.subscriptionShowCheckedVideoCount,
                ) { time, count, showCheckedCount ->
                    Triple(time, count, showCheckedCount)
                }.collect { (time, count, showCheckedCount) ->
                    _uiState.update {
                        it.copy(
                            lastRefreshTime = time,
                            lastRefreshText = if (time > 0L) formatYouTubeRelativeTime(time) else null,
                            lastRefreshVideoCount = count,
                            showLastRefreshVideoCount = showCheckedCount,
                        )
                    }
                }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                combine(subscriptionWatchedVideos.ids, subscriptionWatchedVideos.shortIds, ::Pair).collect { (videoIds, shortIds) ->
                    watchedVideoIds = videoIds
                    watchedShortIds = shortIds
                    refreshVisibleFeed()
                }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                combine(
                    playerPreferences.unplayableVideoIds,
                    playerPreferences.hideUnplayableVideosFromSubscriptions,
                ) { ids, hideUnplayable ->
                    if (hideUnplayable) ids else emptySet()
                }.distinctUntilChanged()
                    .collect { ids ->
                        unplayableVideoIds = ids
                        refreshVisibleFeed()
                    }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                subscriptions.collect { allSubs ->
                    val channels = allSubs.toSubscribedChannels()
                    val notifStates = allSubs.associate { it.channelId to it.isNotificationEnabled }
                    _uiState.update { it.copy(subscribedChannels = channels, notificationStates = notifStates) }
                }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                FeedInvalidationBus.events.collect { event ->
                    if (event is FeedInvalidationBus.Event.ChannelBlocked || event is FeedInvalidationBus.Event.NotInterested) {
                        refreshVisibleFeed()
                    }
                }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                combine(
                    _uiState
                        .map { it.selectedGroupScope() }
                        .distinctUntilChanged()
                        .flatMapLatest { scope -> subscriptionFeedRepository.observeFeed(scope) }
                        .onEach { latestFeedVideos = it },
                    subscriptions.map { it.toSubscribedChannels() }.distinctUntilChanged(),
                    ::Pair,
                ).collect { (_, channels) ->
                    updateVideos(latestFeedVideos, channels)
                }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                _uiState.subscriptionCount
                    .map { observers -> observers > 0 }
                    .distinctUntilChanged()
                    .collectLatest { observed ->
                        if (!observed) return@collectLatest
                        while (true) {
                            delay(RELATIVE_TIME_TICK_MS)
                            refreshVisibleFeed()
                        }
                    }
            }

            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                subscriptions
                    .map { subs -> subs.map { it.channelId }.sorted() }
                    .distinctUntilChanged()
                    .collect { channelIds ->
                        Log.i(TAG, "Channel IDs changed: ${channelIds.size} channels")
                        if (channelIds.isNotEmpty()) {
                            runRefresh(force = false, showLoading = _uiState.value.recentVideos.isEmpty())
                        }
                    }
            }
        }

        /**
         * Fetches only the channels that are actually due, unless [force] (an explicit pull-to-refresh)
         * asks for every channel. With a group selected, only that group's channels (#1099).
         */
        private suspend fun runRefresh(
            force: Boolean,
            showLoading: Boolean,
        ) = runRefresh(subscriptionFeedRepository.planRefresh(force, _uiState.value.selectedGroupScope()), showLoading)

        private suspend fun runRefresh(
            plan: SubscriptionRefreshPlan,
            showLoading: Boolean,
        ) {
            if (plan.isEmpty) {
                Log.i(TAG, "Nothing to refresh — every subscribed channel is still fresh")
                _uiState.update { it.copy(isLoading = false) }
                return
            }
            Log.i(
                TAG,
                "Refreshing ${plan.channelIds.size} channel(s), full=${plan.isFullRefresh}, " +
                    "group=${plan.scope?.size}, allTabs=${plan.readAllTabs}",
            )

            if (showLoading) {
                _uiState.update { it.copy(isLoading = true) }
            }
            try {
                subscriptionFeedRepository.refresh(plan).collect { progress ->
                    latestFeedVideos = progress.videos
                    _uiState.update {
                        it.copy(
                            // The spinner covers the RSS stage only; the checks keep their own counter.
                            isLoading = it.isLoading && progress.stage == SubscriptionRefreshStage.RSS && !progress.finished,
                            failedChannelIds = progress.failedChannelIds,
                            failedChannelReasons = progress.failedChannelReasons,
                            refreshStage = progress.stage,
                            refreshProcessedChannels = progress.processedChannels,
                            refreshTotalChannels = progress.totalChannels,
                        )
                    }
                    updateVideos(progress.videos)
                }
            } finally {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        refreshProcessedChannels = 0,
                        refreshTotalChannels = 0,
                    )
                }
            }
        }

        private suspend fun refreshVisibleFeed() {
            if (latestFeedVideos.isNotEmpty()) {
                updateVideos(latestFeedVideos)
            }
        }

        private suspend fun updateVideos(
            videos: List<Video>,
            channels: List<Channel> = _uiState.value.subscribedChannels,
        ) {
            val state = _uiState.value
            val sections =
                subscriptionFeedSections(
                    videos = videos.withHighQualityThumbnails().withSubscriptionAvatars(channels),
                    filters =
                        SubscriptionFeedFilters(
                            showVideos = state.showSubscriptionVideos,
                            showShorts = state.showSubscriptionShorts,
                            showLive = state.showSubscriptionLive,
                            showUpcoming = state.showSubscriptionUpcoming,
                            watchedVideoIds = watchedVideoIds,
                            watchedShortIds = watchedShortIds,
                            unplayableVideoIds = unplayableVideoIds,
                            allowedChannelIds = state.selectedGroupScope(),
                            excludedShortsChannelIds = excludedShortsChannelIds,
                            exclusions = neuroEngine.feedExclusions(),
                        ),
                    now = System.currentTimeMillis(),
                )
            _uiState.update { it.copy(recentVideos = sections.recentVideos, shorts = sections.shorts) }
        }

        private val durationEnricher =
            SubscriptionDurationEnricher(
                scope = viewModelScope,
                playerPreferences = playerPreferences,
                recentVideos = { _uiState.value.recentVideos },
            ) { enrichedById ->
                val mergedVideos =
                    latestFeedVideos
                        .map { video -> enrichedById[video.id]?.let(video::withPlayerMetadata) ?: video }
                        .withHighQualityThumbnails()
                        .withSubscriptionAvatars(_uiState.value.subscribedChannels)

                latestFeedVideos = mergedVideos
                updateVideos(mergedVideos)
                subscriptionFeedRepository.updateEnrichedMetadata(enrichedById.values)
                Log.d(TAG, "Duration enrichment applied to ${enrichedById.size} subscription videos")
            }

        fun updateVisibleVideoIds(videoIds: Set<String>) = durationEnricher.updateVisibleVideoIds(videoIds)

        /** Shows [groupName]'s channels and tops up the ones gone stale; nothing is fetched while they are fresh. */
        fun selectGroup(groupName: String?) {
            _uiState.update { it.copy(selectedGroupName = groupName) }
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                playerPreferences.setSelectedSubscriptionGroup(groupName)
                refreshVisibleFeed()
            }
            if (groupName != null) refreshIfStaleOrMissedUploads()
        }

        private val groupEditor =
            SubscriptionGroupEditor(
                scope = viewModelScope,
                dao = subscriptionGroupDao,
                preferences = playerPreferences,
                selectedGroup = { _uiState.value.selectedGroupName },
                onSelectedRenamed = { newName -> _uiState.update { it.copy(selectedGroupName = newName) } },
                onSelectedDeleted = { selectGroup(null) },
            )

        fun createGroup(
            name: String,
            channelIds: List<String>,
        ) = groupEditor.create(name, channelIds)

        fun updateGroup(
            oldName: String,
            newName: String,
            channelIds: List<String>,
        ) = groupEditor.update(oldName, newName, channelIds)

        fun deleteGroup(name: String) = groupEditor.delete(name)

        fun setGroupOrder(names: List<String>) = groupEditor.setOrder(names)

        fun togglePin(channelId: String) = savePins(togglePinned(_uiState.value.pinnedChannelIds, channelId))

        /** Saves the order of the pins one list shows, leaving pins it doesn't show where they were. */
        fun setPinnedOrder(shown: List<String>) = savePins(reorderPinnedSubset(_uiState.value.pinnedChannelIds, shown))

        private fun savePins(pins: List<String>) {
            _uiState.update { it.copy(pinnedChannelIds = pins) }
            viewModelScope.launch(PerformanceDispatcher.diskIO) { playerPreferences.setSubscriptionPinnedChannels(pins.toPinnedStorage()) }
        }

        private val _importMessages = EventChannel<String>(EventChannel.BUFFERED)
        val importMessages = _importMessages.receiveAsFlow()

        fun importNewPipeBackup(uri: android.net.Uri) {
            viewModelScope.launch {
                backupCoordinator.importNewPipeForResult(uri).takeIf { it.isNotEmpty() }?.let { _importMessages.send(it) }
            }
        }

        fun selectChannel(channelId: String?) {
            _uiState.update { it.copy(selectedChannelId = channelId) }
        }

        /** Explicit user refresh: every channel shown, regardless of how recently it was fetched. */
        fun refreshFeed() {
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                runRefresh(force = true, showLoading = true)
            }
        }

        /** Background top-up: only the channels that have aged out or have a pending upload signal. */
        fun refreshIfStaleOrMissedUploads() {
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                runRefresh(force = false, showLoading = false)
            }
        }

        /** Re-runs only the channels the last refresh could not reach. */
        fun retryFailedChannels() {
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                val failed = _uiState.value.failedChannelIds
                if (failed.isEmpty()) return@launch
                _uiState.update { it.copy(failedChannelIds = emptySet(), failedChannelReasons = emptyMap()) }
                runRefresh(
                    plan = SubscriptionRefreshPlan(channelIds = failed.toList(), isFullRefresh = false),
                    showLoading = true,
                )
            }
        }

        fun dismissFailedChannels() {
            _uiState.update { it.copy(failedChannelIds = emptySet(), failedChannelReasons = emptyMap()) }
        }

        fun unsubscribe(channelId: String) {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                subscriptionRepository.unsubscribe(channelId)
            }
        }

        fun updateNotificationState(
            channelId: String,
            enabled: Boolean,
        ) {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                subscriptionRepository.updateNotificationState(channelId, enabled)
            }
        }

        fun setShortsChannelExcluded(
            channelId: String,
            excluded: Boolean,
        ) {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                playerPreferences.setSubscriptionShortsChannelExcluded(channelId, excluded)
            }
        }

        fun toggleViewMode() {
            val newValue = !_uiState.value.isFullWidthView
            _uiState.update { it.copy(isFullWidthView = newValue) }
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                playerPreferences.setSubsFullWidthView(newValue)
            }
        }

        fun setSortMode(mode: SubscriptionSortMode) {
            _uiState.update { it.copy(sortMode = mode) }
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                playerPreferences.setSubsSortMode(mode.name)
            }
        }

        /**
         * Get a single subscription snapshot (suspend)
         */
        suspend fun getSubscriptionOnce(channelId: String): ChannelSubscription? =
            subscriptionRepository.getSubscription(channelId).firstOrNull()

        /**
         * Subscribe a channel (used for undo)
         */
        fun subscribeChannel(channel: ChannelSubscription) {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                subscriptionRepository.subscribe(channel)
            }
        }
    }
