package io.github.aedev.flow.ui.screens.home

import io.github.aedev.flow.data.local.HomeFeedCacheFilters
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FeedExclusions
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.recommendation.RankTrace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

// Every Home pipeline logs under the view model's tag, so one logcat filter follows a whole load.
internal const val HOME_LOG_TAG = "HomeViewModel"

/**
 * The Home feed state that the loader, wave 2, the late merges and load-more all read and write.
 * [HomeViewModel] makes exactly one and hands it to each of them, so every field keeps one owner.
 */
internal class HomeFeedSession(
    private val subscriptionRepository: SubscriptionRepository,
    private val playerPreferences: PlayerPreferences,
    val searchPages: HomeSearchPages,
) {
    val uiState = MutableStateFlow(HomeUiState())
    val watchedVideoIds = MutableStateFlow<Set<String>>(emptySet())
    val watchedShortIds = MutableStateFlow<Set<String>>(emptySet())

    val relatedPickIds = ConcurrentHashMap.newKeySet<String>()

    var subsBacklog: List<Video> = emptyList()

    var currentQueryIndex = 0
    val discoveryQueries = mutableListOf<String>()

    // Changes with every feed load, so work cancelled by leaving Home can tell it from a reload.
    @Volatile
    var feedGeneration = 0

    // Set while Diagnostics records Home snapshots: collects why each ranked card was admitted.
    var snapshotTrace: RankTrace? = null

    // The memory lane's share of the first page; late uploads and load-more pages stay within it.
    var channelMemoryQuota = 0

    @Volatile
    var lastVisibleVideoIndex = -1

    // The cached cards painted at launch; while set, wave 1 holds its feed back instead of replacing them.
    @Volatile
    var cachedOnScreenIds: Set<String>? = null

    suspend fun subscriptionScope(): HomeSubscriptionScope =
        HomeSubscriptionScope.of(
            subscriptions = subscriptionRepository.getAllSubscriptionIds(),
            showOnHome = playerPreferences.homeSubscriptionsEnabled.first(),
        )

    suspend fun feedExclusions(): FeedExclusions =
        runCatching { FlowNeuroEngine.feedExclusions() }
            .getOrDefault(FeedExclusions.NONE)
            .hidingChannels(subscriptionScope().hidden)

    suspend fun cacheFilters(): HomeFeedCacheFilters =
        HomeFeedCacheFilters(watchedVideoIds = watchedVideoIds.value, exclusions = feedExclusions())
}
