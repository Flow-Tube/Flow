package io.github.aedev.flow.ui.screens.channel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.community.PostCommentsLoader
import io.github.aedev.flow.data.local.ChannelSubscription
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.local.dao.SubscriptionGroupDao
import io.github.aedev.flow.data.local.entity.SubscriptionGroupEntity
import io.github.aedev.flow.data.model.SubscriptionGroup
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.model.distinctByNonBlankKey
import io.github.aedev.flow.data.model.toUiModel
import io.github.aedev.flow.data.notes.NoteKind
import io.github.aedev.flow.data.notes.NoteSubject
import io.github.aedev.flow.data.notes.NotesRepository
import io.github.aedev.flow.data.shorts.withoutShorts
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.channel.ChannelTabKind
import io.github.aedev.flow.innertube.pages.renderer.FeedItemOwner
import io.github.aedev.flow.ui.youtubeChannelBrowseId
import io.github.aedev.flow.ui.youtubeChannelUrl
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChannelViewModel
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val subscriptionRepository: SubscriptionRepository,
        private val subscriptionGroupDao: SubscriptionGroupDao,
        private val notesRepository: NotesRepository,
        private val playerPreferences: PlayerPreferences,
    ) : ViewModel() {
        val subscriptionGroups: StateFlow<List<SubscriptionGroup>> =
            subscriptionGroupDao
                .getAllGroups()
                .map { entities -> entities.map { it.toUiModel() } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(GROUPS_SUBSCRIPTION_TIMEOUT_MS), emptyList())

        fun setChannelInGroup(
            groupName: String,
            channelId: String,
            inGroup: Boolean,
        ) {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                val group = subscriptionGroupDao.getAllGroupsOnce().firstOrNull { it.name == groupName } ?: return@launch
                val members = group.toUiModel().channelIds.toMutableSet()
                if (inGroup) members.add(channelId) else members.remove(channelId)
                subscriptionGroupDao.updateGroup(group.copy(channelIds = members.joinToString(",")))
            }
        }

        fun createGroupWithChannel(
            groupName: String,
            channelId: String,
        ) {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                val name = groupName.trim()
                if (name.isEmpty() || subscriptionGroupDao.exists(name)) return@launch
                val order = subscriptionGroupDao.getAllGroupsOnce().size
                subscriptionGroupDao.insertGroup(
                    SubscriptionGroupEntity(name = name, channelIds = channelId, sortOrder = order),
                )
            }
        }

        /** Which channels the user follows, so a featured-channel row can show its real state. */
        val subscribedChannelIds: StateFlow<Set<String>> =
            subscriptionRepository
                .getAllSubscriptions()
                .map { subscriptions -> subscriptions.map { it.channelId }.toSet() }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(GROUPS_SUBSCRIPTION_TIMEOUT_MS), emptySet())

        val notifyingChannelIds: StateFlow<Set<String>> =
            subscriptionRepository
                .notifyingChannelIds()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(GROUPS_SUBSCRIPTION_TIMEOUT_MS), emptySet())

        fun setChannelNotifications(
            channelId: String,
            enabled: Boolean,
        ) {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                subscriptionRepository.updateNotificationState(channelId, enabled)
            }
        }

        fun setChannelSubscription(
            channel: io.github.aedev.flow.data.model.Channel,
            subscribed: Boolean,
        ) {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                if (subscribed) {
                    subscriptionRepository.subscribe(
                        ChannelSubscription(
                            channelId = channel.id,
                            channelName = channel.name,
                            channelThumbnail = channel.thumbnailUrl,
                            subscribedAt = System.currentTimeMillis(),
                        ),
                    )
                } else {
                    subscriptionRepository.unsubscribe(channel.id)
                }
            }
        }

        val notesEnabled: StateFlow<Boolean> =
            playerPreferences.effectiveChannelNotesEnabled
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(GROUPS_SUBSCRIPTION_TIMEOUT_MS), false)

        val showShortsTab: StateFlow<Boolean> =
            playerPreferences.effectiveChannelShortsTabEnabled
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(GROUPS_SUBSCRIPTION_TIMEOUT_MS), true)

        private val _channelNote = MutableStateFlow<String?>(null)
        val channelNote: StateFlow<String?> = _channelNote.asStateFlow()

        private var noteJob: Job? = null

        private fun observeNote(channelId: String) {
            noteJob?.cancel()
            noteJob =
                viewModelScope.launch(PerformanceDispatcher.diskIO) {
                    notesRepository.observe(NoteKind.Channel, channelId).collect { note ->
                        _channelNote.value = note?.text
                    }
                }
        }

        fun saveChannelNote(text: String) {
            val channelId = _uiState.value.channelId ?: return
            val subject =
                _uiState.value.header
                    ?.takeIf { it.title.isNotBlank() }
                    ?.let { header ->
                        NoteSubject(
                            title = header.title,
                            channelId = header.id,
                            thumbnailUrl = header.avatarUrl,
                            channelAvatarUrl = header.avatarUrl,
                            channelHandle = header.handle.orEmpty(),
                            subscriberCountText = header.subscriberCountText.orEmpty(),
                        )
                    }
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                notesRepository.save(NoteKind.Channel, channelId, text, subject)
            }
        }

        private val _uiState = MutableStateFlow(ChannelUiState())
        val uiState: StateFlow<ChannelUiState> = _uiState.asStateFlow()
        private val communityController = ChannelCommunityController(viewModelScope)
        private var requestedChannelUrl: String? = null
        internal val communityUiState: StateFlow<ChannelCommunityUiState> = communityController.state
        val postComments = PostCommentsLoader(viewModelScope)

        private val tabController = ChannelTabController(viewModelScope)
        internal val tabStates: StateFlow<Map<ChannelTabKind, ChannelTabState>> =
            combine(tabController.states, playerPreferences.shortsContentEnabled) { states, shortsEnabled ->
                if (shortsEnabled) states else states.mapValues { (_, state) -> state.copy(sections = state.sections.withoutShorts()) }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(GROUPS_SUBSCRIPTION_TIMEOUT_MS), emptyMap())

        private fun channelOwner(): FeedItemOwner {
            val state = _uiState.value
            return FeedItemOwner(
                id = state.channelId.orEmpty(),
                name = state.header?.title.orEmpty(),
                avatarUrl = state.header?.avatarUrl.orEmpty(),
            )
        }

        fun selectTabFilter(
            kind: ChannelTabKind,
            groupIndex: Int,
            optionIndex: Int,
        ) = tabController.selectFilter(kind, _uiState.value.tabParams(kind), groupIndex, optionIndex)

        var listScrollIndex: Int = 0
            private set
        var listScrollOffset: Int = 0
            private set

        fun saveScrollPosition(
            index: Int,
            offset: Int,
        ) {
            listScrollIndex = index
            listScrollOffset = offset
        }

        companion object {
            private const val TAG = "ChannelViewModel"
            private const val GROUPS_SUBSCRIPTION_TIMEOUT_MS = 5_000L
        }

        /**
         *  PERFORMANCE OPTIMIZED: Load channel with timeout protection
         */
        fun loadChannel(channelUrl: String) {
            if (!shouldLoadChannel(_uiState.value, requestedChannelUrl, channelUrl)) return
            requestedChannelUrl = channelUrl
            val directId = youtubeChannelBrowseId(channelUrl)
            val vanityUrl = youtubeChannelUrl(channelUrl)
            if (directId == null && vanityUrl == null) {
                _uiState.update { it.copy(error = appContext.getString(R.string.error_invalid_channel_url), isLoading = false) }
                return
            }

            _uiState.update { it.copy(isLoading = true, error = null) }
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                val browseId =
                    directId ?: YouTube.resolveChannelId(checkNotNull(vanityUrl)).getOrElse { error ->
                        Log.e(TAG, "Failed to resolve $vanityUrl", error)
                        _uiState.update { it.copy(error = appContext.getString(R.string.error_channel_link_not_found), isLoading = false) }
                        return@launch
                    }

                YouTube.channel(browseId).fold(
                    onSuccess = { page ->
                        val header = page.header
                        val channelId = header.id.ifBlank { browseId }
                        _uiState.update {
                            it.copy(
                                channelId = channelId,
                                header = header,
                                tabs = page.tabs,
                                isLoading = false,
                            )
                        }
                        communityController.reset(channelId, header.title, header.avatarUrl)
                        postComments.close()
                        loadSubscriptionState(channelId)
                        observeNote(channelId)
                        onTabsResolved()
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to load channel", error)
                        _uiState.update {
                            it.copy(
                                error = error.message ?: appContext.getString(R.string.error_failed_to_load_channel),
                                isLoading = false,
                            )
                        }
                    },
                )
            }
        }

        private fun onTabsResolved() {
            val state = _uiState.value
            val channelId = state.channelId ?: return
            tabController.reset(channelId, channelOwner())
            val first = state.selectedTab ?: state.tabs.firstOrNull()?.kind ?: return
            _uiState.update { it.copy(selectedTab = first) }
            ensureTabLoaded(first)
        }

        private fun ensureTabLoaded(kind: ChannelTabKind) {
            if (kind == ChannelTabKind.Posts) {
                communityController.ensurePostsLoaded()
                return
            }
            if (kind == ChannelTabKind.Shorts) {
                // Read at load time, not once per channel, so turning the tab on while a channel is open still loads it.
                viewModelScope.launch {
                    if (playerPreferences.effectiveChannelShortsTabEnabled.first()) {
                        tabController.ensureLoaded(kind, _uiState.value.tabParams(kind))
                    }
                }
                return
            }
            tabController.ensureLoaded(kind, _uiState.value.tabParams(kind))
        }

        private fun loadSubscriptionState(channelId: String) {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                subscriptionRepository.getSubscription(channelId).collect { subscription ->
                    _uiState.update {
                        it.copy(
                            isSubscribed = subscription != null,
                            isNotificationsEnabled = subscription?.isNotificationEnabled ?: false,
                        )
                    }
                }
            }
        }

        fun toggleSubscription() {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                val state = _uiState.value
                val channelId = state.channelId ?: return@launch
                val header = state.header ?: return@launch
                val channelName = header.title
                val channelThumbnail = header.avatarUrl

                if (state.isSubscribed) {
                    // Unsubscribe
                    subscriptionRepository.unsubscribe(channelId)
                } else {
                    // Subscribe
                    val subscription =
                        ChannelSubscription(
                            channelId = channelId,
                            channelName = channelName,
                            channelThumbnail = channelThumbnail,
                            subscribedAt = System.currentTimeMillis(),
                        )
                    subscriptionRepository.subscribe(subscription)
                }
            }
        }

        fun unsubscribe() {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                val state = _uiState.value
                val channelId = state.channelId ?: return@launch
                subscriptionRepository.unsubscribe(channelId)
            }
        }

        fun setNotificationState(enabled: Boolean) {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                val state = _uiState.value
                val channelId = state.channelId ?: return@launch
                subscriptionRepository.updateNotificationState(channelId, enabled)
            }
        }

        fun selectTab(kind: ChannelTabKind) {
            _uiState.update { it.copy(selectedTab = kind) }
            ensureTabLoaded(kind)
        }

        fun retryCommunityPosts() = communityController.retryPosts()

        fun loadMoreCommunityPosts() = communityController.loadMorePosts()

        // ── Channel search ────────────────────────────────────────────────────────

        fun setSearchActive(active: Boolean) {
            _uiState.update {
                it.copy(
                    searchActive = active,
                    searchQuery = if (!active) "" else it.searchQuery,
                    searchResults = if (!active) emptyList() else it.searchResults,
                    searchErrorLog = null,
                )
            }
        }

        fun searchInChannel(query: String) {
            val channelId = _uiState.value.channelId ?: return
            val header = _uiState.value.header ?: return
            val trimmed = query.trim()

            _uiState.update {
                it.copy(
                    searchQuery = query,
                    searchErrorLog = null,
                )
            }

            if (trimmed.isBlank()) {
                _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
                return
            }

            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                _uiState.update { it.copy(isSearching = true) }
                try {
                    val result =
                        YouTube.channelSearch(
                            channelId = channelId,
                            channelName = header.title,
                            channelThumbnailUrl = header.avatarUrl,
                            query = trimmed,
                        )
                    result.fold(
                        onSuccess = { page ->
                            _uiState.update {
                                it.copy(
                                    searchResults = page.videos.distinctByNonBlankKey(Video::id),
                                    searchContinuation = page.continuation,
                                    isSearching = false,
                                    searchErrorLog = null,
                                )
                            }
                        },
                        onFailure = { e ->
                            Log.e(TAG, "Channel search failed", e)
                            _uiState.update {
                                it.copy(
                                    isSearching = false,
                                    searchErrorLog =
                                        buildChannelRequestErrorLog(
                                            operation = "channel_search",
                                            channelId = channelId,
                                            query = trimmed,
                                            error = e,
                                        ),
                                )
                            }
                        },
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Channel search error", e)
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            searchErrorLog =
                                buildChannelRequestErrorLog(
                                    operation = "channel_search",
                                    channelId = channelId,
                                    query = trimmed,
                                    error = e,
                                ),
                        )
                    }
                }
            }
        }
    }
