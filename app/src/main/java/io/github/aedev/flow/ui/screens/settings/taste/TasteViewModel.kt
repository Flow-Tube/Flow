package io.github.aedev.flow.ui.screens.settings.taste

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.backup.BackupCoordinator
import io.github.aedev.flow.data.contentfilter.aislist.AiListRepository
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.dao.WatchHistoryDao
import io.github.aedev.flow.data.recommendation.BlockedChannelNames
import io.github.aedev.flow.data.recommendation.ChannelMemoryRepository
import io.github.aedev.flow.data.recommendation.ContentVector
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.recommendation.FlowPersona
import io.github.aedev.flow.data.recommendation.NeighbourChannelsRepository
import io.github.aedev.flow.data.recommendation.TimeBucket
import io.github.aedev.flow.data.recommendation.UserBrain
import io.github.aedev.flow.data.recommendation.music.MusicBrainEngine
import io.github.aedev.flow.data.recommendation.snapshot.HomeSnapshotStore
import io.github.aedev.flow.data.stats.VideoStatsRecorder
import io.github.aedev.flow.ui.components.shared.DeepFlowState
import io.github.aedev.flow.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Five traits of a taste vector, each 0..1, in the order the radar draws them. */
@Immutable
data class TasteTraits(
    val pacing: Float,
    val complexity: Float,
    val duration: Float,
    val live: Float,
    val breadth: Float,
) {
    val values: List<Float> get() = listOf(pacing, complexity, duration, live, breadth)
}

/** A group of learned interests that go together, named by its strongest words ([tasteGroups]). */
@Immutable
data class TasteGroup(
    val id: String,
    val label: String,
    /** This group's weight relative to the strongest group, 0..1. */
    val share: Float,
    val evidence: Int,
    val members: List<TasteTopic>,
)

@Immutable
data class TasteTopic(
    val id: String,
    val label: String,
    /** This topic's weight relative to the strongest learned one, 0..1; none for a pick. */
    val share: Float?,
    val preferred: Boolean,
    /** Watches, likes and searches behind it ([evidenceOf]). */
    val evidence: Int = 0,
)

@Immutable
data class TasteChannel(
    val id: String,
    val name: String,
    val score: Float,
)

@Immutable
data class NamedItem(
    val id: String,
    val name: String,
)

@Immutable
data class MusicTaste(
    val maturity: String,
    val appetite: Float,
    val plays: Int,
    val genres: List<Pair<String, Float>>,
)

@Immutable
data class HiddenContent(
    val topics: List<String> = emptyList(),
    /** A channel with a blank name is one the device has no name for yet. */
    val channels: List<NamedItem> = emptyList(),
    val artists: List<NamedItem> = emptyList(),
    /** Whether the names [channels] lack are being fetched. */
    val resolvingNames: Boolean = false,
) {
    val count: Int get() = topics.size + channels.size + artists.size
}

@Immutable
data class TasteState(
    val loading: Boolean = true,
    val persona: FlowPersona? = null,
    val maturity: Float = 0f,
    val profile: TasteTraits? = null,
    val now: TasteTraits? = null,
    val picks: List<TasteTopic> = emptyList(),
    val topics: List<TasteTopic> = emptyList(),
    /** [topics] as the groups the engine reads them in, so an interest shows with the words it goes with (#907). */
    val groups: List<TasteGroup> = emptyList(),
    val channels: List<TasteChannel> = emptyList(),
    val music: MusicTaste? = null,
    val hidden: HiddenContent = HiddenContent(),
    /** Channels watched without subscribing whose uploads Home brings in, best first. */
    val remembered: List<NamedItem> = emptyList(),
    val engine: EngineDetails? = null,
)

/**
 * How the engines see the viewer, and the levers that change it. Everything is read from local
 * state; the one network call is Hidden from you fetching, once, the names of blocked channels the
 * device has never seen ([resolveMissingNames]).
 */
@HiltViewModel
class TasteViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val musicBrain: MusicBrainEngine,
        private val videoStats: VideoStatsRecorder,
        private val watchHistoryDao: WatchHistoryDao,
        private val backup: BackupCoordinator,
        private val channelMemory: ChannelMemoryRepository,
        private val homeSnapshots: HomeSnapshotStore,
        private val neighbours: NeighbourChannelsRepository,
        private val preferences: PlayerPreferences,
        private val blockedNames: BlockedChannelNames,
        private val aiList: AiListRepository,
    ) : SettingsViewModel() {
        private val _state = MutableStateFlow(TasteState())
        private var resolveJob: Job? = null
        val state: StateFlow<TasteState> = _state.asStateFlow()

        /** Whether Deep Flow has learning paused, shown as a status; it is switched in Settings only. */
        val deepFlow: StateFlow<DeepFlowState> =
            combine(
                preferences.deepFlowActive,
                preferences.deepFlowActivatedAt,
                preferences.deepFlowExpireHours,
            ) { active, activatedAt, expireHours ->
                DeepFlowState(active = active, activatedAt = activatedAt, expireHours = expireHours)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DeepFlowState())
        val operation = backup.operation

        /** Channels shown despite the AI list, from the banner on their page. */
        val allowedAiChannels: StateFlow<List<NamedItem>> =
            aiList.allowedChannels
                .map { channels -> channels.map { NamedItem(it.id, it.name) } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

        fun hideAiChannelAgain(channelId: String) = write { aiList.disallow(channelId) }

        init {
            reload()
        }

        fun reload() {
            viewModelScope.launch {
                _state.value = withContext(Dispatchers.Default) { load() }
            }
        }

        fun setTopicPreferred(
            topic: String,
            preferred: Boolean,
        ) = act {
            if (preferred) FlowNeuroEngine.addPreferredTopic(context, topic) else FlowNeuroEngine.removePreferredTopic(context, topic)
        }

        fun blockTopic(topic: String) = act { FlowNeuroEngine.addBlockedTopic(context, topic) }

        fun unblockTopic(topic: String) = act { FlowNeuroEngine.removeBlockedTopic(context, topic) }

        fun blockChannel(channelId: String) =
            act {
                FlowNeuroEngine.blockChannel(context, channelId)
                _state.value.channels
                    .firstOrNull { it.id == channelId && it.name != channelId }
                    ?.let { blockedNames.remember(channelId, it.name) }
            }

        fun unblockChannel(channelId: String) =
            act {
                FlowNeuroEngine.unblockChannel(context, channelId)
                blockedNames.forget(channelId)
            }

        /**
         * Fetches the names of blocked channels the device has no name for. Rows fill in where they
         * stand and the list sorts once at the end, so nothing moves under the viewer's finger.
         */
        fun resolveMissingNames() {
            if (resolveJob?.isActive == true) return
            resolveJob =
                viewModelScope.launch {
                    val loaded = _state.first { !it.loading }
                    val missing =
                        loaded.hidden.channels
                            .filter { it.name.isBlank() }
                            .map { it.id }
                    if (missing.isEmpty()) return@launch
                    _state.update { it.copy(hidden = it.hidden.copy(resolvingNames = true)) }
                    try {
                        val blocked = FlowNeuroEngine.getSavedBrainSnapshot().blockedChannels
                        blockedNames.resolve(missing, blocked) { names ->
                            _state.update { state ->
                                val channels = state.hidden.channels.map { item -> names[item.id]?.let { item.copy(name = it) } ?: item }
                                state.copy(hidden = state.hidden.copy(channels = channels))
                            }
                        }
                    } finally {
                        _state.update {
                            it.copy(
                                hidden = it.hidden.copy(channels = hiddenChannelOrder(it.hidden.channels), resolvingNames = false),
                            )
                        }
                    }
                }
        }

        fun unblockArtist(artistKey: String) = act { musicBrain.unblockArtist(artistKey) }

        fun forgetChannel(channelId: String) = act { channelMemory.forget(channelId) }

        fun clearChannelMemory() = act { channelMemory.clear() }

        fun resetVideoProfile() =
            act {
                FlowNeuroEngine.resetBrain(context)
                preferences.setOnboardingDone(false)
                homeSnapshots.clear()
                neighbours.clear()
            }

        fun resetMusicProfile() = act { musicBrain.resetBrain() }

        fun exportVideoProfile(uri: Uri) = backup.exportEngine(uri)

        fun importVideoProfile(uri: Uri) = backup.importEngine(uri)

        fun exportMusicProfile(uri: Uri) = backup.exportMusicBrain(uri)

        fun importMusicProfile(uri: Uri) = backup.importMusicBrain(uri)

        fun dismissOperation() = backup.dismiss()

        private fun act(block: suspend () -> Unit) {
            viewModelScope.launch {
                runCatching { block() }
                _state.value = withContext(Dispatchers.Default) { load() }
            }
        }

        private suspend fun load(): TasteState {
            FlowNeuroEngine.initialize(context)
            val brain = FlowNeuroEngine.getSavedBrainSnapshot()
            val names = channelNames()
            val profile = runCatching { musicBrain.tasteProfile() }.getOrNull()
            return TasteState(
                loading = false,
                persona = FlowNeuroEngine.getPersona(brain),
                maturity = (brain.totalInteractions / MATURE_INTERACTIONS).coerceIn(0f, 1f),
                profile = brain.globalVector.traits(),
                now = (brain.timeVectors[TimeBucket.current()] ?: ContentVector()).traits(),
                picks = tastePicks(brain, FlowNeuroEngine.pickKeysByLabel(brain)),
                topics = tasteTopics(brain, TOPIC_COUNT, FlowNeuroEngine.pickKeys(brain), FlowNeuroEngine::specificity),
                groups =
                    tasteGroups(
                        brain,
                        runCatching { FlowNeuroEngine.interestClusters(brain) }.getOrDefault(emptyList()),
                        FlowNeuroEngine.pickKeys(brain),
                        FlowNeuroEngine::specificity,
                        GROUP_COUNT,
                    ),
                channels = tasteChannels(brain, names),
                music =
                    profile?.let { taste ->
                        val topGenre = taste.topGenres.maxOfOrNull { it.second }?.takeIf { it > 0.0 } ?: 1.0
                        MusicTaste(
                            maturity = taste.maturity,
                            appetite = taste.discoveryAppetite.toFloat(),
                            plays = taste.totalPlays,
                            genres = taste.topGenres.take(GENRE_COUNT).map { (name, weight) -> name to (weight / topGenre).toFloat() },
                        )
                    },
                hidden = hidden(brain, names),
                remembered =
                    runCatching { channelMemory.remembered() }
                        .getOrDefault(emptyList())
                        .map { NamedItem(it.channelId, it.name.ifBlank { names[it.channelId] ?: it.channelId }) },
                engine = EngineDetails.of(brain),
            )
        }

        private suspend fun tasteChannels(
            brain: UserBrain,
            names: Map<String, String>,
        ): List<TasteChannel> {
            val top =
                brain.channelScores.entries
                    .filter { it.key !in brain.blockedChannels }
                    .sortedByDescending { it.value }
                    .take(CHANNEL_COUNT)
            val known = names + blockedNames.known(top.map { it.key }.filterTo(HashSet()) { it !in names })
            return top.map { TasteChannel(it.key, known[it.key] ?: it.key, it.value.toFloat()) }
        }

        private suspend fun hidden(
            brain: UserBrain,
            names: Map<String, String>,
        ): HiddenContent {
            val known = names + blockedNames.known(brain.blockedChannels)
            return HiddenContent(
                topics = brain.blockedTopics.sorted(),
                channels = hiddenChannelOrder(brain.blockedChannels.map { NamedItem(it, known[it].orEmpty()) }),
                artists =
                    runCatching { musicBrain.getBlockedArtistsWithNames() }
                        .getOrDefault(
                            emptyList(),
                        ).map { NamedItem(it.first, it.second) },
                resolvingNames = resolveJob?.isActive == true,
            )
        }

        /** Channel names the device already knows: the recap ledger first, then watch history. */
        private suspend fun channelNames(): Map<String, String> {
            val names = HashMap<String, String>()
            runCatching { watchHistoryDao.getChannelVideoCounts(NAME_LOOKUP_LIMIT) }
                .getOrDefault(emptyList())
                .forEach { if (it.channelName.isNotBlank()) names[it.channelId] = it.channelName }
            runCatching { videoStats.snapshot() }
                .getOrNull()
                ?.months
                ?.toSortedMap()
                ?.values
                ?.forEach { names.putAll(it.channelNames) }
            return names
        }

        private fun ContentVector.traits() =
            TasteTraits(
                pacing = pacing.toFloat(),
                complexity = complexity.toFloat(),
                duration = duration.toFloat(),
                live = isLive.toFloat(),
                breadth = (topics.size / BREADTH_TOPICS).coerceIn(0f, 1f),
            )

        private companion object {
            const val MATURE_INTERACTIONS = 250f
            const val BREADTH_TOPICS = 50f
            const val TOPIC_COUNT = 12
            const val GROUP_COUNT = 8
            const val CHANNEL_COUNT = 10
            const val GENRE_COUNT = 8
            const val NAME_LOOKUP_LIMIT = 2_000
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

/** Named channels by name, then the ones without a name yet by id. */
internal fun hiddenChannelOrder(channels: List<NamedItem>): List<NamedItem> {
    val (named, unnamed) = channels.partition { it.name.isNotBlank() }
    return named.sortedBy { it.name.lowercase() } + unnamed.sortedBy { it.id }
}
