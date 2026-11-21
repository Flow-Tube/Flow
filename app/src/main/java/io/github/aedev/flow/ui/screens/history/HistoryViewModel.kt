package io.github.aedev.flow.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.engagement.HiddenContent
import io.github.aedev.flow.data.engagement.HiddenContentStore
import io.github.aedev.flow.data.local.VideoHistoryEntry
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.local.dao.VideoDao
import io.github.aedev.flow.data.local.dao.WatchHistoryDao
import io.github.aedev.flow.data.local.entity.VideoEntity
import io.github.aedev.flow.data.local.entity.WatchHistoryEntity
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.shorts.ShortsContentFilter
import io.github.aedev.flow.data.shorts.queue.ShortsQueueHandoff
import io.github.aedev.flow.data.shorts.queue.ShortsQueueSource
import io.github.aedev.flow.utils.PerformanceDispatcher
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val ENRICHMENT_BATCH_LIMIT = 30
private const val ENRICHMENT_CHUNK_SIZE = 5
private const val ENRICHMENT_CHUNK_DELAY_MS = 300L

enum class HistoryContentFilter {
    All,
    Videos,
    Shorts,
    Music,
    LocalVideos,
    LocalMusic,
    ;

    fun matches(entry: VideoHistoryEntry): Boolean =
        when (this) {
            All -> !entry.isLocal
            Videos -> !entry.isMusic && !entry.isShort && !entry.isLocal
            Shorts -> !entry.isMusic && entry.isShort && !entry.isLocal
            Music -> entry.isMusic && !entry.isLocal
            LocalVideos -> entry.isLocal && !entry.isMusic
            LocalMusic -> entry.isLocal && entry.isMusic
        }
}

enum class HistorySort {
    Newest,
    Oldest,
}

@HiltViewModel
class HistoryViewModel
    @Inject
    constructor(
        private val viewHistory: ViewHistory,
        private val youTubeRepository: YouTubeRepository,
        private val videoDao: VideoDao,
        private val watchHistoryDao: WatchHistoryDao,
        private val shortsContentFilter: ShortsContentFilter,
        private val shortsQueueHandoff: ShortsQueueHandoff,
        hiddenContent: HiddenContentStore,
    ) : ViewModel() {
        private val isEnriching = AtomicBoolean(false)
        private val attemptedEnrichment = HashSet<String>()
        private val sharing = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L)

        private val _uiState = MutableStateFlow(HistoryUiState())
        val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

        private val _searchQuery = MutableStateFlow("")
        val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

        private val _contentFilter = MutableStateFlow(HistoryContentFilter.All)
        val contentFilter: StateFlow<HistoryContentFilter> = _contentFilter.asStateFlow()

        private val _sortOrder = MutableStateFlow(HistorySort.Newest)
        val sortOrder: StateFlow<HistorySort> = _sortOrder.asStateFlow()

        private val _yearFilter = MutableStateFlow<Int?>(null)
        val yearFilter: StateFlow<Int?> = _yearFilter.asStateFlow()

        val availableYears: StateFlow<List<Int>> =
            _uiState
                .map { state ->
                    state.historyEntries
                        .map { historyYear(it.timestamp) }
                        .distinct()
                        .sortedDescending()
                }.distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, sharing, emptyList())

        val displayEntries: StateFlow<List<VideoHistoryEntry>> =
            combine(
                _uiState
                    .map { it.historyEntries }
                    .distinctUntilChanged()
                    .combine(hiddenContent.hidden) { entries, hidden -> entries.withoutHidden(hidden) },
                _searchQuery,
                _contentFilter,
                _sortOrder,
                _yearFilter,
            ) { entries, query, filter, sort, year ->
                val trimmed = query.trim()
                entries
                    .asSequence()
                    .filter { filter.matches(it) }
                    .filter { year == null || historyYear(it.timestamp) == year }
                    .filter {
                        trimmed.isBlank() ||
                            it.title.contains(trimmed, ignoreCase = true) ||
                            it.channelName.contains(trimmed, ignoreCase = true)
                    }.let { sequence ->
                        if (sort == HistorySort.Newest) {
                            sequence.sortedByDescending { it.timestamp }
                        } else {
                            sequence.sortedBy { it.timestamp }
                        }
                    }.toList()
            }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, sharing, emptyList())

        init {
            viewModelScope.launch {
                shortsContentFilter.enabled.collect { enabled ->
                    _uiState.update { it.copy(shortsEnabled = enabled) }
                    if (!enabled && _contentFilter.value == HistoryContentFilter.Shorts) {
                        _contentFilter.value = HistoryContentFilter.All
                    }
                }
            }

            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }
                viewHistory
                    .getAllHistory()
                    .combine(shortsContentFilter.enabled) { history, shortsEnabled ->
                        if (shortsEnabled) history else history.filterNot { it.isShort }
                    }.collect { history ->
                        val enriched = enrichFromDatabase(history)
                        val shortVideos = loadShortVideos(enriched)

                        _uiState.update {
                            it.copy(
                                historyEntries = enriched,
                                shortVideos = shortVideos,
                                isLoading = false,
                            )
                        }

                        val stubs =
                            enriched
                                .asSequence()
                                .filter { entry ->
                                    !entry.isLocal &&
                                        entry.videoId !in attemptedEnrichment &&
                                        (
                                            entry.title.isEmpty() ||
                                                entry.channelName.isEmpty() ||
                                                (entry.isShort && !shortVideos.containsKey(entry.videoId))
                                        )
                                }.distinctBy { it.videoId }
                                .take(ENRICHMENT_BATCH_LIMIT)
                                .toList()
                        if (stubs.isNotEmpty()) {
                            enrichFromApi(stubs)
                        }
                    }
            }
        }

        fun setSearchQuery(query: String) {
            _searchQuery.value = query
        }

        fun setContentFilter(filter: HistoryContentFilter) {
            _contentFilter.value = filter
        }

        fun setSortOrder(sort: HistorySort) {
            _sortOrder.value = sort
        }

        fun setYearFilter(year: Int?) {
            _yearFilter.value = year
        }

        fun shortsRowSource(
            row: List<Video>,
            tapped: Video,
        ): ShortsQueueSource = shortsQueueHandoff.sourceForShelf(row, tapped)

        private suspend fun enrichFromDatabase(history: List<VideoHistoryEntry>): List<VideoHistoryEntry> {
            val lookupIds =
                history
                    .filter { it.title.isEmpty() || it.channelName.isEmpty() || it.isShort }
                    .map { it.videoId }
                    .distinct()
            val cached =
                if (lookupIds.isEmpty()) {
                    emptyMap()
                } else {
                    videoDao.getVideosByIds(lookupIds).associateBy { it.id }
                }

            return history.map { entry ->
                var enriched = entry
                val dbVideo = cached[entry.videoId]

                if (enriched.thumbnailUrl.isEmpty()) {
                    enriched =
                        enriched.copy(
                            thumbnailUrl =
                                ThumbnailUrlResolver.normalizeVideoThumbnail(
                                    enriched.videoId,
                                    dbVideo?.thumbnailUrl,
                                ),
                        )
                }

                if (dbVideo != null) {
                    if (enriched.title.isEmpty() && dbVideo.title.isNotEmpty()) {
                        enriched = enriched.copy(title = dbVideo.title)
                    }
                    if (enriched.channelName.isEmpty() && dbVideo.channelName.isNotEmpty()) {
                        enriched = enriched.copy(channelName = dbVideo.channelName, channelId = dbVideo.channelId)
                    }
                    if (dbVideo.thumbnailUrl.isNotEmpty() &&
                        ThumbnailUrlResolver.isYoutubeVideoThumbnail(enriched.thumbnailUrl)
                    ) {
                        enriched = enriched.copy(thumbnailUrl = dbVideo.thumbnailUrl)
                    }
                }
                enriched
            }
        }

        private suspend fun loadShortVideos(entries: List<VideoHistoryEntry>): Map<String, Video> {
            val shorts = entries.filter { it.isShort }
            if (shorts.isEmpty()) return emptyMap()
            val cached = videoDao.getVideosByIds(shorts.map { it.videoId }.distinct()).associateBy { it.id }
            return shorts
                .mapNotNull { entry ->
                    cached[entry.videoId]
                        ?.toDomain()
                        ?.copy(
                            isShort = true,
                            isMusic = entry.isMusic,
                            timestamp = entry.timestamp,
                        )?.let { entry.videoId to it }
                }.toMap()
        }

        private fun enrichFromApi(stubs: List<VideoHistoryEntry>) {
            if (!isEnriching.compareAndSet(false, true)) return
            attemptedEnrichment.addAll(stubs.map { it.videoId })
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                try {
                    stubs.chunked(ENRICHMENT_CHUNK_SIZE).forEach { chunk ->
                        chunk.forEach { stub ->
                            try {
                                val video = youTubeRepository.getVideo(stub.videoId) ?: return@forEach
                                val entity = VideoEntity.fromDomain(video)
                                videoDao.insertVideoOrIgnore(entity)
                                videoDao.updateVideoMetadata(
                                    id = entity.id,
                                    title = entity.title,
                                    channelName = entity.channelName,
                                    channelId = entity.channelId,
                                    thumbnailUrl = entity.thumbnailUrl,
                                    duration = entity.duration,
                                    viewCount = entity.viewCount,
                                    uploadDate = entity.uploadDate,
                                    timestamp = entity.timestamp,
                                    description = entity.description,
                                    channelThumbnailUrl = entity.channelThumbnailUrl,
                                )
                                watchHistoryDao.upsert(
                                    WatchHistoryEntity(
                                        videoId = stub.videoId,
                                        position = stub.position,
                                        duration = video.duration * 1000L,
                                        timestamp = stub.timestamp,
                                        title = video.title,
                                        thumbnailUrl =
                                            ThumbnailUrlResolver.normalizeVideoThumbnail(
                                                stub.videoId,
                                                video.thumbnailUrl,
                                            ),
                                        channelName = video.channelName,
                                        channelId = video.channelId,
                                        isMusic = stub.isMusic,
                                        isShort = stub.isShort || video.isShort,
                                    ),
                                )
                            } catch (_: Exception) {
                            }
                        }
                        delay(ENRICHMENT_CHUNK_DELAY_MS)
                    }
                } finally {
                    isEnriching.set(false)
                }
            }
        }

        fun clearHistory() {
            viewModelScope.launch {
                viewHistory.clearAllHistory()
            }
        }

        fun clearShortsHistory() {
            viewModelScope.launch {
                viewHistory.clearShortsHistory()
            }
        }

        fun removeFromHistory(videoId: String) {
            viewModelScope.launch {
                viewHistory.clearVideoHistory(videoId)
            }
        }

        fun restoreHistoryEntry(entry: VideoHistoryEntry) {
            viewModelScope.launch {
                viewHistory.bulkSaveHistoryEntries(listOf(entry))
            }
        }
    }

internal fun historyYear(timestamp: Long): Int = Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.YEAR)

data class HistoryUiState(
    val historyEntries: List<VideoHistoryEntry> = emptyList(),
    val shortVideos: Map<String, Video> = emptyMap(),
    val isLoading: Boolean = false,
    val shortsEnabled: Boolean = true,
)

/** History without what the viewer marked "Not interested" or blocked since the screen loaded it. */
internal fun List<VideoHistoryEntry>.withoutHidden(hidden: HiddenContent): List<VideoHistoryEntry> =
    if (hidden.isEmpty) this else filterNot { hidden.hides(it.videoId, it.channelId) }
