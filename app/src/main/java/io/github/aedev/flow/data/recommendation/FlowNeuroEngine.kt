/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 *
 * Flow is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This recommendation algorithm (FlowNeuroEngine) is the intellectual property
 * of the Flow project. Any use of this code in other projects must
 * explicitly credit "Flow Android Client" and link back to the original repository.
 */

package io.github.aedev.flow.data.recommendation

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.utils.traced
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.math.ln
import kotlin.math.log10

/**
 * Flow Neuro Engine (V10.0 — Channel Intelligence + Shorts Vector + Anti-Rec + Momentum)
 *
 * Client-side hybrid recommendation: Vector Space Model + Heuristic Rules.
 *
 * This file is the thin orchestrator that delegates all heavy logic to:
 * - NeuroModels.kt    — data classes and enums
 * - NeuroScoring.kt   — scoring factor calculators and constants
 * - NeuroVectorMath.kt — vector algebra (cosine similarity, vector adjustment)
 * - NeuroTokenizer.kt  — tokenization, IDF, feature extraction
 * - NeuroStorage.kt    — DataStore persistence, export/import, migration
 * - NeuroDiscovery.kt  — smart query generation (V2)
 */
class FlowNeuroEngine internal constructor(
    private val appContext: Context,
    private val storage: NeuroStorage,
    private val contentStore: NeuroContentStore,
    private val categoryStore: NeuroCategoryStore = NeuroCategoryStore(appContext),
    private val coWatchStore: NeuroCoWatchStore = NeuroCoWatchStore(appContext),
    learningPaused: (suspend () -> Boolean)? = null,
    private val rankRandom: java.util.Random? = null,
) {
    constructor(appContext: Context) : this(appContext, NeuroStorage(appContext), NeuroContentStore(appContext))

    companion object {
        private const val TAG = "FlowNeuroEngine"

        // ── Orchestrator-only constants ──
        // Trace sections the startup and Home benchmarks read (#1301).
        const val TRACE_INIT = "neuro.init"
        const val TRACE_RANK = "neuro.rank"

        // A refresh ranks about 600 candidates; a smaller cache missed on every one of them.
        private const val FEATURE_CACHE_MAX = 700
        private const val SESSION_TOPIC_HISTORY_MAX = 50
        private const val SAVE_DEBOUNCE_MS = 5000L

        /** Impressions, rotation and cooldown state change on every scroll and feed load; none needs a fast save. */
        private const val BOOKKEEPING_SAVE_DEBOUNCE_MS = 30_000L

        // ── Suppression constants ──

        /** How long a channel stays hard-suppressed before escalating to a full block */
        private const val CHANNEL_SUPPRESSION_DAYS = 14L

        /** "Not interested" on a video never expires; past this many marks the oldest are dropped. */
        internal const val MAX_SUPPRESSED_VIDEOS = 5_000

        /** Max suppressed channel entries to prevent unbounded growth */
        private const val MAX_SUPPRESSED_CHANNELS = 100

        private const val TOPIC_EVIDENCE_MAX_ENTRIES = 500

        private const val COUNTED_DOCUMENTS_MAX = 500

        /** Opened videos whose related lists wait for a real watch or a like ([NeuroCoWatchStore]). */
        private const val PENDING_RELATED_MAX = 30

        /** The most a channel watched alongside the viewer's own adds to a score. */
        internal const val COWATCH_MAX_PRIOR = 0.04

        /** Where a channel's score starts; a watch moves it above, a rejection below. */
        private const val NEUTRAL_CHANNEL_SCORE = 0.5

        /** A subscription teaches its channel name about half what a full watch teaches. */
        private const val SUBSCRIPTION_RATE = 0.08

        // ── Import bootstrap constants ──
        private const val SUBSCRIPTION_SEED_WEIGHT = 0.25

        /**
         * A subscription list carries no dates, so a channel followed years ago seeds as strongly as
         * one followed last week. Seeds stay below the established tier so watching decides which
         * of them last (#1030).
         */
        internal const val SUBSCRIPTION_SEED_CAP = NeuroVectorMath.ESTABLISHED_TOPIC_THRESHOLD - 0.01

        /**
         * An imported history ends cold start and warm-up but no more: counting every imported
         * video as an interaction damps all later learning as if the person had used Flow for
         * months (#1030).
         */
        internal const val HISTORY_BOOTSTRAP_MAX_INTERACTIONS = NeuroScoring.ONBOARDING_WARMUP_INTERACTIONS

        @Volatile
        private var instance: FlowNeuroEngine? = null

        fun getInstance(context: Context): FlowNeuroEngine =
            instance ?: synchronized(this) {
                instance ?: FlowNeuroEngine(context.applicationContext).also {
                    instance = it
                }
            }

        private fun requireInstance(): FlowNeuroEngine =
            instance ?: error("FlowNeuroEngine not initialized. Call initialize(context) first.")

        // ── Backward-compatible forwarding API ──

        suspend fun initialize(context: Context) = getInstance(context).initialize()

        suspend fun rank(
            candidates: List<Video>,
            userSubs: Set<String>,
            minRelevance: Double = 0.0,
            trace: RankTrace? = null,
        ): List<Video> = requireInstance().rank(candidates, userSubs, minRelevance, trace)

        suspend fun recordFeedImpressions(ids: List<String>) = requireInstance().recordFeedImpressions(ids)

        suspend fun generateDiscoveryQueries(resetDepth: Boolean = false): List<String> =
            requireInstance().generateDiscoveryQueries(resetDepth)

        suspend fun selectRelatedSeeds(
            candidates: List<GraphSeedInput>,
            maxSeeds: Int = 4,
            longTermCandidates: List<GraphSeedInput> = emptyList(),
        ): List<String> = requireInstance().selectRelatedSeeds(candidates, maxSeeds, longTermCandidates)

        suspend fun needsOnboarding(): Boolean = requireInstance().needsOnboarding()

        suspend fun topTopicsFor(
            video: Video,
            limit: Int,
        ): List<String> = requireInstance().topTopicsFor(video, limit)

        suspend fun getBrainSnapshot(): UserBrain = requireInstance().getBrainSnapshot()

        internal suspend fun diagnosticState(): NeuroDiagnosticState = requireInstance().diagnosticState()

        internal suspend fun neighbourChannels(subscribed: Set<String>): List<NeighbourChannels.Neighbour> =
            requireInstance().neighbourChannels(subscribed)

        internal suspend fun coWatchChannelNames(): Map<String, String> = requireInstance().coWatchChannelNames()

        internal suspend fun interestClusters(brain: UserBrain): List<NeuroClusters.TopicCluster> =
            requireInstance().interestClusters(brain)

        /** How specific [key] is in general language ([NeuroSpecificity]); 1 before the engine starts. */
        fun specificity(key: String): Double = instance?.specificity(key) ?: 1.0

        suspend fun getSavedBrainSnapshot(): UserBrain = requireInstance().getSavedBrainSnapshot()

        suspend fun updateChannelMemory(
            bookkeeping: Boolean,
            transform: (ChannelMemoryState) -> ChannelMemoryState,
        ) = requireInstance().updateChannelMemory(bookkeeping, transform)

        suspend fun interestChips(now: Long = System.currentTimeMillis()): List<InterestChip> = requireInstance().interestChips(now)

        fun outsideCategory(
            videos: List<Video>,
            category: String,
        ): Set<String> = requireInstance().outsideCategory(videos, category)

        suspend fun noteRelated(
            context: Context,
            videoId: String,
            related: List<Video>,
        ) = getInstance(context).noteRelated(videoId, related)

        suspend fun explorationQueries(limit: Int): List<String> = requireInstance().explorationQueries(limit)

        suspend fun rejectedByPattern(videos: List<Video>): Set<String> = requireInstance().rejectedByPattern(videos)

        suspend fun tasteAffinity(videos: List<Video>): Map<String, Double> = requireInstance().tasteAffinity(videos)

        suspend fun noteSessionTopics(topics: List<String>) = requireInstance().noteSessionTopics(topics)

        suspend fun clusterKeys(videos: List<Video>): Map<String, String> = requireInstance().clusterKeys(videos)

        suspend fun shareByInterest(ranked: List<Video>): List<Video> = requireInstance().shareByInterest(ranked)

        fun getPersona(brain: UserBrain): FlowPersona = requireInstance().getPersona(brain)

        suspend fun markNotInterested(video: Video) = requireInstance().markNotInterested(video)

        suspend fun markNotInterested(
            context: Context,
            video: Video,
        ) = getInstance(context).markNotInterested(video)

        suspend fun onVideoInteraction(
            video: Video,
            interactionType: InteractionType,
            percentWatched: Float = 0f,
        ) = requireInstance().onVideoInteraction(video, interactionType, percentWatched)

        suspend fun onVideoInteraction(
            context: Context,
            video: Video,
            interactionType: InteractionType,
            percentWatched: Float = 0f,
        ) = getInstance(context).onVideoInteraction(video, interactionType, percentWatched)

        /**
         * Fire-and-forget variant on the engine's own scope. Safe to call from
         * lifecycle teardown (e.g. ViewModel.onCleared) where viewModelScope is dead.
         */
        fun onVideoInteractionAsync(
            context: Context,
            video: Video,
            interactionType: InteractionType,
            percentWatched: Float = 0f,
        ) = getInstance(context).onVideoInteractionAsync(video, interactionType, percentWatched)

        suspend fun onChannelSubscriptionChanged(
            context: Context,
            channelId: String,
            channelName: String,
            subscribed: Boolean,
        ) = getInstance(context).onChannelSubscriptionChanged(channelId, channelName, subscribed)

        suspend fun onSearchQuery(
            context: Context,
            query: String,
        ) = getInstance(context).onSearchQuery(query)

        suspend fun reportQueryResultNovelty(
            query: String,
            novelRatio: Double,
        ) = requireInstance().reportQueryResultNovelty(query, novelRatio)

        suspend fun getRecentlyShownVideoIds(withinHours: Long = 48L): Set<String> = requireInstance().getRecentlyShownVideoIds(withinHours)

        suspend fun getExcludedChannelIds(): Set<String> = requireInstance().getExcludedChannelIds()

        suspend fun feedExclusions(): FeedExclusions = requireInstance().feedExclusions()

        suspend fun blockedContentMatcher(): (title: String, channelName: String) -> Boolean = requireInstance().blockedContentMatcher()

        suspend fun selectShortsSeeds(
            candidates: List<ShortsSeedInput>,
            maxSeeds: Int = 2,
        ): List<String> = requireInstance().selectShortsSeeds(candidates, maxSeeds)

        suspend fun onChannelTagsLearned(
            context: Context,
            channelId: String,
            tags: List<String>,
            description: String?,
        ) = getInstance(context).onChannelTagsLearned(channelId, tags, description)

        suspend fun onChannelUploadsObserved(uploads: List<Video>) = requireInstance().onChannelUploadsObserved(uploads)

        suspend fun completeOnboarding(selectedTopics: Set<String>) = requireInstance().completeOnboarding(selectedTopics)

        suspend fun completeOnboarding(
            context: Context,
            selectedTopics: Set<String>,
        ) = getInstance(context).completeOnboarding(selectedTopics)

        suspend fun exportBrainToStream(output: OutputStream): Boolean = requireInstance().exportBrainToStream(output)

        suspend fun importBrainFromStream(input: InputStream): Boolean = requireInstance().importBrainFromStream(input)

        suspend fun importBrainFromStream(
            context: Context,
            input: InputStream,
        ): Boolean = getInstance(context).importBrainFromStream(input)

        suspend fun bootstrapFromSubscriptions(channelNames: List<String>) = requireInstance().bootstrapFromSubscriptions(channelNames)

        suspend fun bootstrapFromSubscriptions(
            context: Context,
            channelNames: List<String>,
        ) = getInstance(context).bootstrapFromSubscriptions(channelNames)

        suspend fun bootstrapFromWatchHistory(videos: List<Video>) = requireInstance().bootstrapFromWatchHistory(videos)

        suspend fun bootstrapFromWatchHistory(
            context: Context,
            videos: List<Video>,
        ) = getInstance(context).bootstrapFromWatchHistory(videos)

        suspend fun resetBrain() = requireInstance().resetBrain()

        suspend fun resetBrain(context: Context) = getInstance(context).resetBrain()

        suspend fun recordSeenShorts(shortIds: List<String>) = requireInstance().recordSeenShorts(shortIds)

        suspend fun getRecentlySeenShorts(): Set<String> = requireInstance().getRecentlySeenShorts()

        suspend fun getPreferredTopics(): Set<String> = requireInstance().getPreferredTopics()

        suspend fun getBlockedTopics(): Set<String> = requireInstance().getBlockedTopics()

        suspend fun addPreferredTopic(topic: String) = requireInstance().addPreferredTopic(topic)

        suspend fun addPreferredTopic(
            context: Context,
            topic: String,
        ) = getInstance(context).addPreferredTopic(topic)

        suspend fun removePreferredTopic(topic: String) = requireInstance().removePreferredTopic(topic)

        fun pickKeys(brain: UserBrain): Set<String> = requireInstance().pickKeys(brain)

        fun pickKeysByLabel(brain: UserBrain): Map<String, List<String>> = requireInstance().pickKeysByLabel(brain)

        suspend fun removePreferredTopic(
            context: Context,
            topic: String,
        ) = getInstance(context).removePreferredTopic(topic)

        suspend fun addBlockedTopic(topic: String) = requireInstance().addBlockedTopic(topic)

        suspend fun addBlockedTopic(
            context: Context,
            topic: String,
        ) = getInstance(context).addBlockedTopic(topic)

        suspend fun removeBlockedTopic(topic: String) = requireInstance().removeBlockedTopic(topic)

        suspend fun removeBlockedTopic(
            context: Context,
            topic: String,
        ) = getInstance(context).removeBlockedTopic(topic)

        suspend fun restoreContentPreferences(
            context: Context,
            preferredTopics: Set<String>,
            blockedTopics: Set<String>,
            blockedChannels: Set<String>,
        ) = getInstance(context).restoreContentPreferences(
            preferredTopics,
            blockedTopics,
            blockedChannels,
        )

        suspend fun unblockChannel(channelId: String) = requireInstance().unblockChannel(channelId)

        suspend fun unblockChannel(
            context: Context,
            channelId: String,
        ) = getInstance(context).unblockChannel(channelId)

        suspend fun blockChannel(channelId: String) = requireInstance().blockChannel(channelId)

        suspend fun blockChannel(
            context: Context,
            channelId: String,
        ) = getInstance(context).blockChannel(channelId)

        val TOPIC_CATEGORIES: List<TopicCategory>
            get() = NeuroTopicCatalog.TOPIC_CATEGORIES
    }

    // ── Module Instances ──
    private val tokenizer = NeuroTokenizer()
    private val playerPreferences by lazy { PlayerPreferences(appContext) }
    private val isLearningPaused: suspend () -> Boolean = learningPaused ?: { playerPreferences.isDeepFlowCurrentlyActive() }
    private val discovery by lazy {
        NeuroDiscovery(NeuroTopicCatalog.TOPIC_CATEGORIES, tokenizer)
    }

    private val deepFlowBookkeeping = NeuroDeepFlowHousekeeping()

    // ── Concurrency ──
    private val brainMutex = Mutex()
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingSaveJob: Job? = null

    // Feature vector cache (LRU)
    private val featureCache =
        object : LinkedHashMap<String, ContentVector>(
            200,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ContentVector>?): Boolean = size > FEATURE_CACHE_MAX
        }

    // Session tracking
    private var sessionStartTime: Long = System.currentTimeMillis()
    private var sessionVideoCount: Int = 0

    // Discovery tree depth: 0 on refresh, +1 per load-more regeneration —
    // each round digs one level deeper into every cluster's branches.
    private var discoveryRound: Int = 0

    // Channels passively profiled from upload titles this session (once each).
    private val passiveProfiledChannels = HashSet<String>()
    private val sessionTopicHistory = mutableListOf<String>()
    private val recentInteractions = mutableListOf<MomentumEntry>()

    // IDF tracking — persisted in brain state
    private var idfWordFrequency = mutableMapOf<String, Int>()
    private var idfTotalDocuments = 0

    // Videos counted as documents this session. With the persisted watch history, one video is one
    // document however often it is opened, watched and liked, so it cannot promote its own pairs (#907).
    private val countedDocuments =
        object : LinkedHashMap<String, Unit>(COUNTED_DOCUMENTS_MAX + 50, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > COUNTED_DOCUMENTS_MAX
        }

    // Impression cache
    private val impressionCache =
        object : LinkedHashMap<String, ImpressionEntry>(
            NeuroScoring.IMPRESSION_CACHE_MAX + 50,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImpressionEntry>?): Boolean =
                size > NeuroScoring.IMPRESSION_CACHE_MAX
        }

    // Watch history
    private val watchHistory =
        LinkedHashMap<String, WatchEntry>(
            NeuroScoring.WATCH_HISTORY_MAX + 50,
            0.75f,
            true,
        )

    private var currentUserBrain: UserBrain = UserBrain()

    @Volatile
    private var isInitialized = false

    /**
     * Every read and write of the brain goes through here. A caller that reaches the engine before
     * [initialize] would otherwise read the empty default brain, or save it over the persisted one.
     */
    @OptIn(ExperimentalContracts::class)
    private suspend inline fun <T> withBrainLock(action: () -> T): T {
        contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
        if (!isInitialized) initialize()
        return brainMutex.withLock(action = action)
    }

    // =================================================
    // PUBLIC API
    // =================================================

    suspend fun initialize() {
        brainMutex.withLock {
            if (isInitialized) return

            // Off the caller's thread: a cold start calls this from the main thread too (#1301).
            traced(TRACE_INIT) {
                withContext(Dispatchers.Default) {
                    val stores = listOf(async { contentStore.load() }, async { categoryStore.load() }, async { coWatchStore.load() })
                    val loaded = storage.load()
                    if (loaded != null) {
                        currentUserBrain = loaded
                    } else if (!storage.hasSavedBrain()) {
                        // Only a first start migrates: once a brain is saved, an empty one is a reset one (#907).
                        storage.migrateLegacy()?.let { migrated ->
                            currentUserBrain = migrated
                            Log.i(TAG, "Migrated an earlier brain")
                        }
                        storage.save(deepFlowBookkeeping.persistable(currentUserBrain))
                    }
                    storage.deleteOldBrainFiles()

                    currentUserBrain = withLanguageProfile(currentUserBrain)
                    val maintained = runMaintenanceIfNeeded(currentUserBrain)
                    if (maintained !== currentUserBrain) {
                        if (currentUserBrain.schemaVersion < NeuroMaintenance.KEY_SPEC_2_SCHEMA_VERSION) {
                            storage.keepCopyBeforeMigration(currentUserBrain, NeuroMaintenance.KEY_SPEC_2_SCHEMA_VERSION)
                        } else if (currentUserBrain.schemaVersion < NeuroMaintenance.KEY_SPEC_3_SCHEMA_VERSION) {
                            storage.keepCopyBeforeMigration(currentUserBrain, NeuroMaintenance.KEY_SPEC_3_SCHEMA_VERSION)
                        }
                        currentUserBrain = maintained
                        storage.save(deepFlowBookkeeping.persistable(currentUserBrain))
                    }

                    idfWordFrequency = currentUserBrain.idfWordFrequency.toMutableMap()
                    idfTotalDocuments = currentUserBrain.idfTotalDocuments

                    // Picks made before spellings were kept still read as chosen ("R&B", never "Rnb").
                    val unspelled =
                        currentUserBrain.preferredTopics.filter { label ->
                            tokenizer.keysOf(label).any {
                                it !in
                                    currentUserBrain.displayForms
                            }
                        }
                    if (unspelled.isNotEmpty()) {
                        currentUserBrain = currentUserBrain.copy(displayForms = withPickSpellings(currentUserBrain.displayForms, unspelled))
                    }

                    currentUserBrain.watchHistoryMap.forEach { (id, pct) ->
                        watchHistory[id] = WatchEntry(pct, System.currentTimeMillis())
                    }
                    stores.awaitAll()
                }
            }
            appContext.registerComponentCallbacks(trimCallbacks)

            resetSessionInternal()
            isInitialized = true
        }
    }

    fun shutdown() {
        pendingSaveJob?.cancel()
        saveScope.cancel()
        appContext.unregisterComponentCallbacks(trimCallbacks)
    }

    /** [brain] with a language profile and key language, the app language the first time; the tokenizer follows them. */
    private fun withLanguageProfile(brain: UserBrain): UserBrain {
        val profile = brain.languageProfile ?: NeuroLanguages.normalize(Locale.getDefault().language)
        val keyLanguage = brain.keyLanguage ?: profile
        tokenizer.languageProfile = profile
        tokenizer.keyLanguage = keyLanguage
        return if (brain.languageProfile == profile && brain.keyLanguage == keyLanguage) {
            brain
        } else {
            brain.copy(languageProfile = profile, keyLanguage = keyLanguage)
        }
    }

    /** The language this brain's keys are stemmed for ([UserBrain.keyLanguage]). */
    internal suspend fun keyLanguage(): String {
        initialize()
        return tokenizer.keyLanguage
    }

    /** How this brain reads a stored key, so sync reads a peer's keys the same way ([NeuroKeyMigration.keyFunction]). */
    internal suspend fun topicKeyFunction(): (String) -> List<String> {
        initialize()
        return NeuroKeyMigration.keyFunction(tokenizer)
    }

    /** One-time brain maintenance, see NeuroMaintenance for the rationale. */
    private fun runMaintenanceIfNeeded(brain: UserBrain): UserBrain {
        val updated = NeuroMaintenance.runIfNeeded(brain, tokenizer)
        if (updated !== brain) {
            Log.i(
                TAG,
                "Brain maintenance: topics ${brain.globalVector.topics.size} → " +
                    "${updated.globalVector.topics.size}, affinities ${brain.topicAffinities.size} → " +
                    "${updated.topicAffinities.size}",
            )
        }
        return updated
    }

    suspend fun getBrainSnapshot(): UserBrain = withBrainLock { currentUserBrain }

    /** The brain as its export writes it, plus the device-local stores ranking reads. */
    internal suspend fun diagnosticState(): NeuroDiagnosticState {
        val brain = withBrainLock { deepFlowBookkeeping.persistable(currentUserBrain) }
        return NeuroDiagnosticState(
            brain = Json.encodeToJsonElement(with(storage) { brain.toSerializable() }),
            categories = categoryStore.index(),
            coWatch = coWatchStore.index(),
        )
    }

    /** The brain as saved: during Deep Flow, without that session's bookkeeping. */
    suspend fun getSavedBrainSnapshot(): UserBrain = withBrainLock { deepFlowBookkeeping.persistable(currentUserBrain) }

    /** Lines the bookkeeping up with Deep Flow before it is written. Call under brainMutex. */
    private fun syncBookkeeping(deepFlowActive: Boolean) {
        currentUserBrain = deepFlowBookkeeping.beforeWrite(currentUserBrain, deepFlowActive)
    }

    private fun clustersOf(brain: UserBrain): List<NeuroClusters.TopicCluster> =
        NeuroClusters.buildClusters(
            topicScores = brain.interests.topics,
            affinities = brain.topicAffinities,
            channelTopicProfiles = brain.channelTopicProfiles,
            categories = NeuroTopicCatalog.TOPIC_CATEGORIES,
            normalizeLemma = tokenizer::keyOf,
            tagAffinities = brain.tagAffinities,
            channelEdges = coWatchEdges(brain),
        )

    /** Home's interest chips, kept stable across loads by [InterestChips.stable]. */
    suspend fun interestChips(now: Long): List<InterestChip> =
        withContext(Dispatchers.Default) {
            val brain = getBrainSnapshot()
            val clusters = clustersOf(brain)
            val picks = pickKeys(brain)
            val hasEvidence = { cluster: NeuroClusters.TopicCluster ->
                cluster.topics.size >= 2 ||
                    cluster.topics.any { it in picks } ||
                    brain.topicEvidence[cluster.representative]?.let {
                        it.watchSignals >= 2 || it.explicitSignals > 0 ||
                            it.positiveScore >= 1.2
                    } ==
                    true
            }
            val categoryOf = { cluster: NeuroClusters.TopicCluster ->
                NeuroCategories.clusterCategory(cluster.topics, brain.channelTopicProfiles, categoryStore::categoryOf)
            }
            val groups = InterestChips.groups(clusters, hasEvidence, categoryOf)
            val set = InterestChips.stable(brain.interestChips, groups, now)
            if (set != brain.interestChips) {
                val deepFlow = isLearningPaused()
                withBrainLock {
                    syncBookkeeping(deepFlow)
                    currentUserBrain = currentUserBrain.copy(interestChips = set)
                    scheduleDebouncedSave(bookkeeping = true)
                }
            }
            InterestChips.chips(set, groups, brain.displayForms, tokenizer::specificity) { key ->
                brain.topicEvidence[key]?.let { it.watchSignals + it.explicitSignals } ?: 0
            }
        }

    private val pendingRelated =
        object : LinkedHashMap<String, List<Video>>(PENDING_RELATED_MAX, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Video>>?): Boolean = size > PENDING_RELATED_MAX
        }

    /** Keeps [related], the list shown beside [videoId], until the viewer really watches or likes the video. */
    suspend fun noteRelated(
        videoId: String,
        related: List<Video>,
    ) {
        if (videoId.isBlank() || related.isEmpty()) return
        withBrainLock { pendingRelated[videoId] = related }
    }

    /** Records [related] for a [seed] from the viewer's history or likes; feed seeds teach nothing. */
    suspend fun noteRelatedOfWatched(
        seed: GraphSeedInput,
        related: List<Video>,
    ) {
        if (seed.source != GraphSeedSource.WATCH_HISTORY && seed.source != GraphSeedSource.LIKED) return
        if (isLearningPaused()) return
        withBrainLock { coWatchStore.record(seed.id, seed.channelId, related) }
    }

    /**
     * The related list of [video], an upload of a channel the viewer follows: which channels sit next
     * to the viewer's own, for [neighbourChannels]. Nothing is learned about the viewer's taste.
     */
    suspend fun noteRelatedOfFollowed(
        video: Video,
        related: List<Video>,
    ) {
        if (isLearningPaused()) return
        withBrainLock { coWatchStore.record(video.id, video.channelId, related) }
    }

    fun specificity(key: String): Double = tokenizer.specificity(key)

    /** The viewer's interests grouped as Home's chips and searches group them ([NeuroClusters]). */
    internal suspend fun interestClusters(brain: UserBrain): List<NeuroClusters.TopicCluster> =
        withContext(Dispatchers.Default) { clustersOf(brain) }

    /** The names co-watch has seen the channels of its related lists under. */
    suspend fun coWatchChannelNames(): Map<String, String> = withBrainLock { coWatchStore.channelNames() }

    /** Videos whose related list co-watch already holds. */
    suspend fun coWatchSourceIds(): Set<String> = withBrainLock { coWatchStore.index().sources.keys }

    /** Channels several of the viewer's followed or watched channels sit next to ([NeighbourChannels]). */
    internal suspend fun neighbourChannels(
        subscribed: Set<String>,
        now: Long = System.currentTimeMillis(),
    ): List<NeighbourChannels.Neighbour> =
        withBrainLock {
            val brain = currentUserBrain
            val suppressionCutoff = now - (CHANNEL_SUPPRESSION_DAYS * 24 * 60 * 60 * 1000L)
            val anchors = subscribed + brain.channelScores.keys.filter { isWatchedChannel(brain, it) }
            NeighbourChannels.neighbours(
                coWatchStore.sources(),
                anchors,
                excluded = { channel ->
                    channel in brain.blockedChannels ||
                        (brain.suppressedChannels[channel] ?: 0L) > suppressionCutoff ||
                        (brain.channelScores[channel] ?: NEUTRAL_CHANNEL_SCORE) < NEUTRAL_CHANNEL_SCORE
                },
                now,
            )
        }

    /** Confirmed pairs of channels watched together, a channel counting as watched once a watch raised its score. */
    private fun coWatchEdges(
        brain: UserBrain,
        now: Long = System.currentTimeMillis(),
    ): Map<String, Double> = NeuroCoWatchStore.edges(coWatchStore.sources(), { isWatchedChannel(brain, it) }, now)

    private fun isWatchedChannel(
        brain: UserBrain,
        channelId: String,
    ) = (brain.channelScores[channelId] ?: 0.0) > NEUTRAL_CHANNEL_SCORE

    /** Ids of [videos] from channels known to post in a category other than [category]. */
    fun outsideCategory(
        videos: List<Video>,
        category: String,
    ): Set<String> =
        videos
            .filter { video ->
                val known = NeuroCategories.idOf(video.category) ?: categoryStore.categoryOf(video.channelId)?.id
                known != null && known != category
            }.mapTo(HashSet()) { it.id }

    suspend fun explorationQueries(limit: Int): List<String> =
        withContext(Dispatchers.Default) { discovery.explorationQueries(getBrainSnapshot(), limit) }

    /** Ids of [videos] that match something the viewer has rejected repeatedly. */
    suspend fun rejectedByPattern(videos: List<Video>): Set<String> =
        withContext(Dispatchers.Default) {
            val brain = getBrainSnapshot()
            if (brain.rejectionPatterns.isEmpty()) return@withContext emptySet()
            val idf = takeIdfSnapshotSafe()
            val now = System.currentTimeMillis()
            videos
                .filter { NeuroScoring.calculateRejectionPatternPenalty(getOrExtractFeatures(it, idf), brain.rejectionPatterns, now) < 1.0 }
                .mapTo(HashSet()) { it.id }
        }

    /** How close each video is to the viewer's long-term taste, 0..1, without the feed's penalties. */
    suspend fun tasteAffinity(videos: List<Video>): Map<String, Double> =
        withContext(Dispatchers.Default) {
            val global = NeuroVectorMath.PreparedVector(getBrainSnapshot().globalVector)
            val idf = takeIdfSnapshotSafe()
            videos.associate { it.id to NeuroVectorMath.calculateCosineSimilarity(global, getOrExtractFeatures(it, idf)) }
        }

    /** The interest cluster each video belongs to, by its strongest topic; unknown topics are their own key. */
    suspend fun clusterKeys(videos: List<Video>): Map<String, String> =
        withContext(Dispatchers.Default) {
            val brain = getBrainSnapshot()
            val communityOf =
                clustersOf(brain)
                    .flatMap { cluster -> cluster.topics.map { it to cluster.representative } }
                    .toMap()
            val idf = takeIdfSnapshotSafe()
            videos.associate { video ->
                val primary =
                    getOrExtractFeatures(video, idf)
                        .topics
                        .maxByOrNull { it.value }
                        ?.key
                        ?.let(NeuroScoring::stripDomainTag)
                        .orEmpty()
                video.id to (communityOf[primary] ?: primary.ifEmpty { video.channelId })
            }
        }

    /** [ranked] with its places shared between the viewer's lasting interests ([NeuroInterestShare]). */
    suspend fun shareByInterest(ranked: List<Video>): List<Video> =
        withContext(Dispatchers.Default) {
            val clusters = clustersOf(getBrainSnapshot())
            val weights = NeuroInterestShare.weights(clusters)
            if (weights.size < 2) return@withContext ranked
            val groupByTopic =
                clusters
                    .filter { it.representative in weights }
                    .flatMap { c ->
                        c.topics.map { it to c.representative }
                    }.toMap()
            val idf = takeIdfSnapshotSafe()
            NeuroInterestShare.interleave(
                ranked,
                weights,
            ) { NeuroInterestShare.groupOf(getOrExtractFeatures(it, idf).topics, groupByTopic) }
        }

    /** A short-lived nudge, such as opening an interest chip: session topics only, never long-term taste. */
    suspend fun noteSessionTopics(topics: List<String>) {
        withBrainLock {
            sessionTopicHistory += topics
            while (sessionTopicHistory.size > SESSION_TOPIC_HISTORY_MAX) sessionTopicHistory.removeAt(0)
        }
    }

    /**
     * Updates the channel memory, saving on the [bookkeeping] delay or as promptly as learning.
     */
    suspend fun updateChannelMemory(
        bookkeeping: Boolean,
        transform: (ChannelMemoryState) -> ChannelMemoryState,
    ) {
        withBrainLock {
            currentUserBrain = currentUserBrain.copy(channelMemory = transform(currentUserBrain.channelMemory))
            scheduleDebouncedSave(bookkeeping = bookkeeping)
        }
    }

    /** Forgets what was learned; what the viewer chose to hide stays hidden. */
    suspend fun resetBrain() {
        withBrainLock {
            currentUserBrain = withLanguageProfile(currentUserBrain.keepingHiddenContent().copy(languageProfile = null, keyLanguage = null))
            deepFlowBookkeeping.forget()
            featureCache.clear()
            idfWordFrequency.clear()
            idfTotalDocuments = 0
            countedDocuments.clear()
            impressionCache.clear()
            watchHistory.clear()
            resetSessionInternal()
            storage.save(deepFlowBookkeeping.persistable(currentUserBrain))
            storage.deleteOldBrainFiles()
            storage.deleteCopiesBeforeMigration()
            contentStore.clear()
            categoryStore.clear()
            coWatchStore.clear()
            pendingRelated.clear()
        }
    }

    suspend fun resetSession() {
        withBrainLock {
            resetSessionInternal()
        }
    }

    private fun resetSessionInternal() {
        sessionStartTime = System.currentTimeMillis()
        sessionVideoCount = 0
        sessionTopicHistory.clear()
        impressionCache.clear()
        recentInteractions.clear()
        discoveryRound = 0
        passiveProfiledChannels.clear()
    }

    fun getSessionDurationMinutes(): Long = (System.currentTimeMillis() - sessionStartTime) / 60_000L

    private val saveLock = Any()
    private var pendingSaveIsLearning = false

    /**
     * Saves the brain once events stop arriving. A learning event saves after [SAVE_DEBOUNCE_MS];
     * bookkeeping waits [BOOKKEEPING_SAVE_DEBOUNCE_MS] and never postpones a learning save already
     * pending, which writes the bookkeeping too. The whole brain is re-encoded on every save.
     */
    private fun scheduleDebouncedSave(bookkeeping: Boolean = false) {
        synchronized(saveLock) {
            val pending = pendingSaveJob
            if (bookkeeping && pending?.isActive == true && pendingSaveIsLearning) return
            pending?.cancel()
            pendingSaveIsLearning = !bookkeeping
            pendingSaveJob = launchSave(if (bookkeeping) BOOKKEEPING_SAVE_DEBOUNCE_MS else SAVE_DEBOUNCE_MS)
        }
    }

    /** Writes a pending save now: the app is leaving the screen and may not come back to finish it. */
    private fun flushPendingSave() {
        synchronized(saveLock) {
            val pending = pendingSaveJob ?: return
            if (!pending.isActive) return
            pending.cancel()
            pendingSaveJob = launchSave(0L)
        }
    }

    /**
     * Waits for interactions still being learned, then writes a pending save now, before a profile switch restarts
     * the process. Interactions are learned first because each one can schedule its own save.
     */
    suspend fun awaitWrites() {
        saveScope.coroutineContext.job.children
            .filter { it !== pendingSaveJob }
            .toList()
            .joinAll()
        flushPendingSave()
        pendingSaveJob?.join()
    }

    private fun launchSave(delayMs: Long): Job =
        saveScope.launch {
            delay(delayMs)
            brainMutex.withLock {
                storage.save(deepFlowBookkeeping.persistable(currentUserBrain))
            }
            contentStore.persistIfDirty()
            categoryStore.persistIfDirty()
            coWatchStore.persistIfDirty()
        }

    private val trimCallbacks =
        object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) flushPendingSave()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = flushPendingSave()
        }

    // =================================================
    // BLOCKED TOPICS & CHANNELS API
    // =================================================

    suspend fun getBlockedTopics(): Set<String> = withBrainLock { currentUserBrain.blockedTopics }

    suspend fun addBlockedTopic(topic: String) {
        val normalized = tokenizer.fold(topic).trim()
        if (normalized.isBlank()) return
        withBrainLock {
            val lemma = tokenizer.keyOf(normalized)

            val scrubbed =
                scrubTopicFromVector(
                    currentUserBrain.globalVector,
                    lemma,
                    normalized,
                )
            val scrubbedTimeVectors =
                currentUserBrain.timeVectors
                    .mapValues { (_, vector) ->
                        scrubTopicFromVector(vector, lemma, normalized)
                    }

            val cleanedPreferred =
                currentUserBrain.preferredTopics
                    .filter { it.lowercase() != normalized }
                    .toSet()

            currentUserBrain =
                currentUserBrain.copy(
                    blockedTopics = currentUserBrain.blockedTopics + normalized,
                    globalVector = scrubbed,
                    recentVector = scrubTopicFromVector(currentUserBrain.recentVector, lemma, normalized),
                    timeVectors = scrubbedTimeVectors,
                    preferredTopics = cleanedPreferred,
                )
            storage.save(deepFlowBookkeeping.persistable(currentUserBrain))
        }
    }

    private fun scrubTopicFromVector(
        vector: ContentVector,
        lemma: String,
        raw: String,
    ): ContentVector {
        val cleaned =
            vector.topics.filter { (key, _) ->
                !key.contains(lemma) && !key.contains(raw)
            }
        return vector.copy(topics = cleaned)
    }

    suspend fun removeBlockedTopic(topic: String) {
        withBrainLock {
            currentUserBrain =
                currentUserBrain.copy(
                    blockedTopics =
                        currentUserBrain.blockedTopics -
                            topic.lowercase(),
                )
            storage.save(deepFlowBookkeeping.persistable(currentUserBrain))
        }
    }

    suspend fun getBlockedChannels(): Set<String> = withBrainLock { currentUserBrain.blockedChannels }

    suspend fun blockChannel(channelId: String) {
        if (channelId.isBlank()) return
        withBrainLock {
            val cleanedScores =
                currentUserBrain.channelScores
                    .toMutableMap()
            cleanedScores.remove(channelId)

            currentUserBrain =
                currentUserBrain.copy(
                    blockedChannels =
                        currentUserBrain.blockedChannels +
                            channelId,
                    channelScores = cleanedScores,
                )
            storage.save(deepFlowBookkeeping.persistable(currentUserBrain))
        }
    }

    suspend fun unblockChannel(channelId: String) {
        withBrainLock {
            currentUserBrain =
                currentUserBrain.copy(
                    blockedChannels = currentUserBrain.blockedChannels - channelId,
                )
            storage.save(deepFlowBookkeeping.persistable(currentUserBrain))
        }
    }

    // =================================================
    // ONBOARDING & PREFERRED TOPICS
    // =================================================

    suspend fun needsOnboarding(): Boolean =
        withBrainLock {
            !currentUserBrain.hasCompletedOnboarding &&
                currentUserBrain.totalInteractions < 5 &&
                currentUserBrain.preferredTopics.isEmpty()
        }

    suspend fun hasCompletedOnboarding(): Boolean = withBrainLock { currentUserBrain.hasCompletedOnboarding }

    suspend fun getPreferredTopics(): Set<String> = withBrainLock { currentUserBrain.preferredTopics }

    suspend fun setPreferredTopics(topics: Set<String>) {
        withBrainLock {
            val newTopics = currentUserBrain.globalVector.topics.toMutableMap()
            topics.forEach { topic ->
                newTopics[tokenizer.keyOf(topic)] = 0.5
            }
            currentUserBrain =
                currentUserBrain.copy(
                    preferredTopics = topics,
                    globalVector =
                        currentUserBrain.globalVector.copy(
                            topics = newTopics,
                        ),
                )
            storage.save(deepFlowBookkeeping.persistable(currentUserBrain))
        }
    }

    suspend fun restoreContentPreferences(
        preferredTopics: Set<String>,
        blockedTopics: Set<String>,
        blockedChannels: Set<String>,
    ) {
        initialize()
        withBrainLock {
            val normalizedPreferred =
                preferredTopics
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
            val normalizedBlocked =
                blockedTopics
                    .map { tokenizer.keyOf(it) }
                    .filter { it.isNotBlank() }
                    .toSet()
            val newTopics = currentUserBrain.globalVector.topics.toMutableMap()
            normalizedPreferred.forEach { topic ->
                tokenizer.keysOf(topic).forEach { key -> newTopics[key] = maxOf(newTopics[key] ?: 0.0, 0.5) }
            }
            normalizedBlocked.forEach { topic -> newTopics.remove(topic) }

            currentUserBrain =
                currentUserBrain.copy(
                    preferredTopics =
                        normalizedPreferred
                            .filterNot {
                                tokenizer.keyOf(it) in normalizedBlocked
                            }.toSet(),
                    blockedTopics = normalizedBlocked,
                    blockedChannels = blockedChannels.filter { it.isNotBlank() }.toSet(),
                    globalVector = currentUserBrain.globalVector.copy(topics = newTopics),
                    hasCompletedOnboarding =
                        currentUserBrain.hasCompletedOnboarding ||
                            normalizedPreferred.isNotEmpty(),
                )
            storage.save(deepFlowBookkeeping.persistable(currentUserBrain))
        }
    }

    suspend fun addPreferredTopic(topic: String) {
        val normalized = topic.trim()
        if (normalized.isBlank()) return
        withBrainLock {
            val newTopics = currentUserBrain.globalVector.topics.toMutableMap()
            tokenizer.keysOf(normalized).forEach { key -> newTopics[key] = maxOf(newTopics[key] ?: 0.0, 0.5) }
            currentUserBrain =
                currentUserBrain.copy(
                    displayForms = withPickSpellings(currentUserBrain.displayForms, listOf(normalized)),
                    preferredTopics = currentUserBrain.preferredTopics + normalized,
                    globalVector =
                        currentUserBrain.globalVector.copy(
                            topics = newTopics,
                        ),
                )
            storage.save(deepFlowBookkeeping.persistable(currentUserBrain))
        }
    }

    /** A pick is written as the viewer chose it ("Cooking"), even before any title writes it. */
    private fun withPickSpellings(
        forms: Map<String, DisplayForm>,
        labels: Collection<String>,
    ): Map<String, DisplayForm> =
        labels.fold(forms) { current, label ->
            val keys = tokenizer.keysOf(label)
            // A label that is one key is that key's spelling, words like "of" included ("Call of Duty").
            val spellings =
                keys.singleOrNull()?.let { key -> mapOf(key to label.trim()) } ?: NeuroDisplayForms.spellingsIn(label, keys, tokenizer)
            NeuroDisplayForms.record(current, spellings.filter { (key, spelling) -> key != spelling }) { true }
        }

    /** The keys the viewer's picks stand for, as titles key them. */
    fun pickKeys(brain: UserBrain): Set<String> = brain.preferredTopics.flatMapTo(HashSet(), tokenizer::keysOf)

    /** Each pick as the viewer chose it, with the keys titles give it. */
    fun pickKeysByLabel(brain: UserBrain): Map<String, List<String>> = brain.preferredTopics.associateWith(tokenizer::keysOf)

    /** [topic] is a pick as the viewer typed it, or the key Your taste lists for it. */
    suspend fun removePreferredTopic(topic: String) {
        withBrainLock {
            currentUserBrain =
                currentUserBrain.copy(
                    preferredTopics =
                        currentUserBrain.preferredTopics.filterNot { label -> label == topic || topic in tokenizer.keysOf(label) }.toSet(),
                )
            storage.save(deepFlowBookkeeping.persistable(currentUserBrain))
        }
    }

    suspend fun completeOnboarding(selectedTopics: Set<String>) {
        withBrainLock {
            if (selectedTopics.isEmpty()) {
                currentUserBrain =
                    currentUserBrain.copy(
                        hasCompletedOnboarding = true,
                    )
                storage.save(deepFlowBookkeeping.persistable(currentUserBrain))
                Log.i(TAG, "Onboarding completed without replacing existing topics")
                return
            }

            val topicList = selectedTopics.toList()
            val newTopics = mutableMapOf<String, Double>()

            topicList.forEachIndexed { index, topic ->
                val weight =
                    when {
                        index < 3 -> 0.55
                        index < 6 -> 0.40
                        else -> 0.30
                    }
                tokenizer.keysOf(topic).forEach { key -> newTopics.merge(key, weight, ::maxOf) }
            }

            // Picks are not linked to each other: unrelated picks must stay separate interests (#907).
            currentUserBrain =
                currentUserBrain.copy(
                    displayForms = withPickSpellings(currentUserBrain.displayForms, selectedTopics),
                    preferredTopics = selectedTopics,
                    globalVector =
                        currentUserBrain.globalVector.copy(
                            topics = newTopics,
                        ),
                    hasCompletedOnboarding = true,
                )
            storage.save(deepFlowBookkeeping.persistable(currentUserBrain))
            Log.i(TAG, "Onboarding: ${selectedTopics.size} topics")
        }
    }

    private fun calculateTopicEvidenceSignal(
        interactionType: InteractionType,
        percentWatched: Float,
        isShort: Boolean,
    ): Double {
        val base =
            when (interactionType) {
                InteractionType.CLICK -> {
                    0.20
                }

                InteractionType.LIKED -> {
                    2.0
                }

                InteractionType.SAVED -> {
                    1.2
                }

                InteractionType.WATCHED -> {
                    when {
                        percentWatched >= NeuroScoring.WATCHED_THRESHOLD_FULL -> 1.5
                        percentWatched >= 0.40f -> 1.0
                        percentWatched >= NeuroScoring.WATCHED_THRESHOLD_SAMPLED -> 0.35
                        else -> 0.0
                    }
                }

                InteractionType.SKIPPED,
                InteractionType.DISLIKED,
                -> {
                    0.0
                }
            }
        return if (isShort) base * 0.35 else base
    }

    private fun updateTopicEvidence(
        current: Map<String, TopicEvidence>,
        videoVector: ContentVector,
        video: Video,
        signalScore: Double,
        isWatchSignal: Boolean,
        isExplicitSignal: Boolean,
    ): Map<String, TopicEvidence> {
        if (signalScore <= 0.0) return current

        val now = System.currentTimeMillis()
        val topics =
            videoVector.topics.entries
                .sortedByDescending { it.value }
                .take(5)
                .map { NeuroScoring.stripDomainTag(it.key) }
                .filter(NeuroText::isTopicSized)
                .distinct()

        if (topics.isEmpty()) return current

        val updated = current.toMutableMap()
        topics.forEach { topic ->
            val existing = updated[topic]
            val nextVideoIds = cappedSet(existing?.videoIds.orEmpty(), video.id)
            val nextChannelIds = cappedSet(existing?.channelIds.orEmpty(), video.channelId)
            updated[topic] =
                TopicEvidence(
                    positiveSignals = (existing?.positiveSignals ?: 0) + 1,
                    watchSignals = (existing?.watchSignals ?: 0) + if (isWatchSignal) 1 else 0,
                    explicitSignals = (existing?.explicitSignals ?: 0) + if (isExplicitSignal) 1 else 0,
                    positiveScore = ((existing?.positiveScore ?: 0.0) + signalScore).coerceAtMost(TopicEvidence.MAX_SCORE),
                    videoIds = nextVideoIds,
                    channelIds = nextChannelIds,
                    firstSeenAt = existing?.firstSeenAt?.takeIf { it > 0L } ?: now,
                    lastSeenAt = now,
                )
        }

        return capEvidence(updated)
    }

    // Cap evidence entries, keeping the strongest signal of either valence.
    private fun capEvidence(map: MutableMap<String, TopicEvidence>): Map<String, TopicEvidence> =
        if (map.size <= TOPIC_EVIDENCE_MAX_ENTRIES) {
            map
        } else {
            map.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, TopicEvidence>> {
                        it.value.positiveScore + it.value.negativeSignals
                    }.thenByDescending { it.value.lastSeenAt },
                ).take(TOPIC_EVIDENCE_MAX_ENTRIES)
                .associate { it.key to it.value }
        }

    // Records a negative signal against a video's primary topics (skip/dislike/not-interested).
    private fun bumpNegativeEvidence(
        current: Map<String, TopicEvidence>,
        videoVector: ContentVector,
    ): Map<String, TopicEvidence> {
        val topics =
            videoVector.topics.entries
                .sortedByDescending { it.value }
                .take(5)
                .map { NeuroScoring.stripDomainTag(it.key) }
                .filter(NeuroText::isTopicSized)
                .distinct()
        if (topics.isEmpty()) return current

        val now = System.currentTimeMillis()
        val updated = current.toMutableMap()
        topics.forEach { topic ->
            val existing = updated[topic]
            updated[topic] =
                (existing ?: TopicEvidence(firstSeenAt = now)).copy(
                    negativeSignals = (existing?.negativeSignals ?: 0) + 1,
                    lastSeenAt = now,
                )
        }
        return capEvidence(updated)
    }

    private fun cappedSet(
        existing: Set<String>,
        value: String,
    ): Set<String> {
        if (value.isBlank()) return existing
        if (value in existing) return existing
        return (existing.toList() + value).takeLast(TopicEvidence.MAX_IDS).toSet()
    }

    // =================================================
    // SUBSCRIPTION BOOTSTRAP
    // =================================================

    suspend fun bootstrapFromSubscriptions(channelNames: List<String>) {
        if (channelNames.isEmpty()) return

        withBrainLock {
            if (currentUserBrain.totalInteractions > 5 &&
                currentUserBrain.globalVector.topics.isNotEmpty()
            ) {
                Log.i(TAG, "Bootstrap skipped: brain already has learned data")
                return
            }

            val mentions = mutableMapOf<String, Int>()
            channelNames.forEach { name ->
                tokenizer.tokenize(name).forEach { token ->
                    mentions[token] = (mentions[token] ?: 0) + 1
                }
            }

            if (mentions.isEmpty()) {
                Log.i(TAG, "Bootstrap: no usable keywords from ${channelNames.size} channels")
                return
            }

            val topicWeights =
                mentions.mapValues { (_, count) ->
                    (count * SUBSCRIPTION_SEED_WEIGHT).coerceAtMost(SUBSCRIPTION_SEED_CAP)
                }

            val mergedTopics = currentUserBrain.globalVector.topics.toMutableMap()
            topicWeights.forEach { (key, weight) ->
                val existing = mergedTopics[key] ?: 0.0
                mergedTopics[key] = maxOf(existing, weight)
            }

            val topKeywords =
                mentions.entries
                    .sortedByDescending { it.value }
                    .take(15)
                    .map { it.key }

            val newAffinities = currentUserBrain.topicAffinities.toMutableMap()
            for (i in topKeywords.indices) {
                for (j in i + 1 until topKeywords.size) {
                    val key = NeuroScoring.makeAffinityKey(topKeywords[i], topKeywords[j])
                    val current = newAffinities[key] ?: 0.0
                    newAffinities[key] =
                        (current + 0.15)
                            .coerceAtMost(NeuroScoring.AFFINITY_MAX)
                }
            }

            // Channel-name words are inferred, so they never join preferredTopics: that set is the
            // person's own choice, never decays and anchors every refresh.
            currentUserBrain =
                currentUserBrain.copy(
                    globalVector =
                        currentUserBrain.globalVector.copy(
                            topics = mergedTopics,
                        ),
                    topicAffinities = newAffinities,
                    hasCompletedOnboarding = true,
                )

            storage.save(deepFlowBookkeeping.persistable(currentUserBrain))
            Log.i(
                TAG,
                "Bootstrap: seeded ${topicWeights.size} topics from " +
                    "${channelNames.size} subscriptions " +
                    "(top: ${topKeywords.take(5).joinToString()})",
            )
        }
    }

    suspend fun bootstrapFromWatchHistory(videos: List<Video>) {
        if (videos.isEmpty()) return

        withBrainLock {
            val isMatureBrain =
                currentUserBrain.totalInteractions > 50 &&
                    currentUserBrain.globalVector.topics.size > 10
            val historyLearningRate = if (isMatureBrain) 0.015 else 0.035

            val idfSnapshot = takeIdfSnapshot()
            var updatedBrain = currentUserBrain

            val maxToProcess = 500

            val perChannelCounts = mutableMapOf<String, Int>()
            val toProcess =
                videos
                    .asSequence()
                    .filter { it.id.isNotBlank() && it.title.isNotBlank() }
                    .distinctBy { it.id }
                    .sortedByDescending { it.timestamp }
                    .filter { video ->
                        val channelKey = video.channelId.ifBlank { video.channelName }
                        if (channelKey.isBlank()) return@filter true
                        val count = perChannelCounts[channelKey] ?: 0
                        if (count >= 20) {
                            false
                        } else {
                            perChannelCounts[channelKey] = count + 1
                            true
                        }
                    }.take(maxToProcess)
                    .toList()

            toProcess.forEachIndexed { index, video ->
                val videoVector = tokenizer.extractFeatures(video, idfSnapshot)
                val indexDecay = 1.0 - (index.toDouble() / maxToProcess * 0.4)
                val effectiveRate = historyLearningRate * indexDecay.coerceIn(0.6, 1.0)

                val newGlobal =
                    NeuroVectorMath.learn(
                        updatedBrain.globalVector,
                        videoVector,
                        effectiveRate / NeuroVectorMath.DECAY_REFERENCE_RATE,
                        NeuroVectorMath.LONG_TERM,
                        effectiveRate,
                        pickKeys(updatedBrain),
                    )

                val currentChScore = updatedBrain.channelScores[video.channelId] ?: 0.5
                val newChScore = (currentChScore * 0.95) + (1.0 * 0.05)
                val newChannelScores =
                    updatedBrain.channelScores +
                        (video.channelId to newChScore)

                var newAffinities = updatedBrain.topicAffinities
                val topTopics =
                    videoVector.topics.entries
                        .sortedByDescending { it.value }
                        .take(3)
                        .map { it.key }
                if (topTopics.size >= 2) {
                    val mutableAffinities = newAffinities.toMutableMap()
                    for (i in topTopics.indices) {
                        for (j in i + 1 until topTopics.size) {
                            val key = NeuroScoring.makeAffinityKey(topTopics[i], topTopics[j])
                            val current = mutableAffinities[key] ?: 0.0
                            mutableAffinities[key] =
                                (current + 0.01)
                                    .coerceAtMost(NeuroScoring.AFFINITY_MAX)
                        }
                    }
                    newAffinities = mutableAffinities
                }

                val newTopicEvidence =
                    updateTopicEvidence(
                        updatedBrain.topicEvidence,
                        videoVector,
                        video,
                        signalScore = 0.25,
                        isWatchSignal = false,
                        isExplicitSignal = false,
                    )

                countDocument(video.id) { tokenizer.documentTerms(video, videoVector) }

                updatedBrain =
                    updatedBrain.copy(
                        globalVector = newGlobal,
                        channelScores = newChannelScores,
                        topicAffinities = newAffinities,
                        topicEvidence = newTopicEvidence,
                    )
            }

            toProcess.forEach { video ->
                watchHistory[video.id] = WatchEntry(0.5f, System.currentTimeMillis())
            }

            currentUserBrain =
                updatedBrain.copy(
                    totalInteractions =
                        updatedBrain.totalInteractions +
                            toProcess.size.coerceAtMost(HISTORY_BOOTSTRAP_MAX_INTERACTIONS),
                    idfWordFrequency = idfWordFrequency.toMap(),
                    idfTotalDocuments = idfTotalDocuments,
                    watchHistoryMap = watchHistory.mapValues { it.value.percentWatched },
                    hasCompletedOnboarding = true,
                )

            compactIdfIfNeeded()

            storage.save(deepFlowBookkeeping.persistable(currentUserBrain))
            featureCache.clear()

            Log.i(
                TAG,
                "History bootstrap: processed ${toProcess.size} videos, " +
                    "${updatedBrain.globalVector.topics.size} topics learned",
            )
        }
    }

    // Periodic IDF maintenance: halve aged counts and cap the distinct vocabulary.

    private fun isDocumentCounted(videoId: String): Boolean = videoId in watchHistory || videoId in countedDocuments

    /** Counts [terms] as one more document, unless [videoId] already counted as one. */
    private inline fun countDocument(
        videoId: String,
        terms: () -> Collection<String>,
    ): Boolean {
        if (videoId.isNotBlank()) {
            if (isDocumentCounted(videoId)) return false
            countedDocuments[videoId] = Unit
        }
        terms().forEach { term -> idfWordFrequency[term] = (idfWordFrequency[term] ?: 0) + 1 }
        idfTotalDocuments++
        return true
    }

    private fun compactIdfIfNeeded() {
        if (idfTotalDocuments > 10000) {
            idfWordFrequency.replaceAll { _, v -> v / 2 }
            idfWordFrequency.entries.removeAll { it.value <= 0 }
            idfTotalDocuments /= 2
        }
        NeuroScoring.capIdfVocabulary(idfWordFrequency)
    }

    suspend fun markNotInterested(video: Video) {
        val videoVector = getOrExtractFeatures(video, takeIdfSnapshotSafe())

        withBrainLock {
            val now = System.currentTimeMillis()

            // 1. Hard-suppress this specific video
            val newSuppressedVideos = suppressVideo(currentUserBrain.suppressedVideoIds, video.id, now)

            // 2. Channel suppression — rolling, self-healing window. Inferred dislikes
            // never become a permanent block; only explicit blockChannel() does that,
            // so two mis-taps can no longer kill a channel forever.
            val newSuppressedChannels = currentUserBrain.suppressedChannels.toMutableMap()
            if (video.channelId.isNotBlank()) {
                newSuppressedChannels[video.channelId] = now
                if (newSuppressedChannels.size > MAX_SUPPRESSED_CHANNELS) {
                    val cutoff = now - (CHANNEL_SUPPRESSION_DAYS * 86_400_000L)
                    newSuppressedChannels.entries.removeAll { it.value < cutoff }
                }
            }

            // 3. Update rejection pattern memory BEFORE vector adjustment
            val updatedPatterns = NeuroScoring.recordRejection(currentUserBrain.rejectionPatterns, videoVector, now)

            // 4. Aggressive vector adjustment — scales with rejection count
            val aggressionFactor =
                NeuroScoring.getRejectionAggressionFactor(
                    videoVector,
                    updatedPatterns,
                    now,
                )
            val newGlobal = adjustVectorByRejection(currentUserBrain.globalVector, videoVector, aggressionFactor)
            val newRecent = adjustVectorByRejection(currentUserBrain.recentVector, videoVector, aggressionFactor)

            // 5. Channel score — scales with rejection aggression
            val newChannelScores = currentUserBrain.channelScores.toMutableMap()
            if (video.channelId.isNotBlank()) {
                val currentScore = newChannelScores[video.channelId] ?: 0.5
                newChannelScores[video.channelId] =
                    (currentScore * aggressionFactor).coerceAtLeast(0.01)
            }

            // 6. Time bucket adjustment
            val bucket = TimeBucket.current()
            val currentBucketVec = currentUserBrain.timeVectors[bucket] ?: ContentVector()
            val newBucketVec =
                NeuroVectorMath.adjustVector(
                    currentBucketVec,
                    videoVector,
                    NeuroScoring.NOT_INTERESTED_TIME_RATE,
                    pruneBelow = NeuroVectorMath.EVIDENCE_PRUNE_THRESHOLD,
                )

            currentUserBrain =
                currentUserBrain.copy(
                    globalVector = newGlobal,
                    recentVector = newRecent,
                    timeVectors = currentUserBrain.timeVectors + (bucket to newBucketVec),
                    channelScores = newChannelScores,
                    totalInteractions = currentUserBrain.totalInteractions + 1,
                    suppressedVideoIds = newSuppressedVideos,
                    suppressedChannels = newSuppressedChannels,
                    channelMemory = ChannelMemory.recordRejection(currentUserBrain.channelMemory, video.channelId, video.channelName, now),
                    rejectionPatterns = updatedPatterns,
                    topicEvidence = bumpNegativeEvidence(currentUserBrain.topicEvidence, videoVector),
                )
            storage.save(deepFlowBookkeeping.persistable(currentUserBrain))
        }
    }

    private fun adjustVectorByRejection(
        current: ContentVector,
        target: ContentVector,
        aggressionFactor: Double,
    ): ContentVector {
        val newTopics = current.topics.toMutableMap()
        target.topics.forEach { (key, _) ->
            val currentVal = newTopics[key] ?: 0.0
            if (currentVal > 0) {
                newTopics[key] =
                    (currentVal * aggressionFactor)
                        .coerceAtMost(0.3)
            }
        }
        newTopics.entries.removeAll { (key, weight) -> key in target.topics && weight < NeuroVectorMath.TOPIC_PRUNE_THRESHOLD }
        return current.copy(topics = newTopics)
    }

    // =================================================
    // DISCOVERY QUERY GENERATION
    // =================================================

    suspend fun generateDiscoveryQueries(resetDepth: Boolean = false): List<String> =
        withContext(Dispatchers.Default) {
            val deepFlow = isLearningPaused()
            withBrainLock {
                syncBookkeeping(deepFlow)
                val brain = currentUserBrain
                val blocked = brain.blockedTopics

                // Tree depth: a refresh starts broad (depth 0); each load-more
                // regeneration digs one level deeper into every cluster's branches.
                if (resetDepth) discoveryRound = 0
                val depth = discoveryRound
                discoveryRound++

                val discoveryQueries =
                    discovery.generateQueries(
                        brain,
                        depth = depth,
                        channelEdges = coWatchEdges(brain),
                    ) { b -> getPersona(b) }

                var candidates =
                    if (discoveryQueries.isNotEmpty()) {
                        discoveryQueries
                            .map { it.query }
                            .filter { query ->
                                !blocked.any { blockedTerm ->
                                    query.lowercase().contains(blockedTerm)
                                }
                            }
                    } else {
                        val preferred = brain.preferredTopics.toList()
                        if (preferred.isNotEmpty()) {
                            preferred.shuffled().take(5)
                        } else {
                            NeuroDiscovery.STARTER_QUERIES
                        }
                    }

                // ── Query rotation: filter queries too similar to recently used ones ──
                if (brain.recentQueryTokens.isNotEmpty() && candidates.size > 3) {
                    candidates = NeuroScoring.rotateQueries(candidates, brain.recentQueryTokens) { tokenizer.tokenize(it).toSet() }
                }

                // ── Skip queries whose recent RESULTS were mostly already shown ──
                val staleExpiryCutoff =
                    System.currentTimeMillis() -
                        NeuroScoring.STALE_QUERY_EXPIRY_HOURS * 60 * 60 * 1000L
                val activeStaleKeys =
                    brain.staleQueries
                        .filter { (_, ts) -> ts > staleExpiryCutoff }
                        .keys
                if (activeStaleKeys.isNotEmpty()) {
                    val fresh =
                        candidates.filter { query ->
                            queryStaleKey(query)?.let { it !in activeStaleKeys } ?: true
                        }
                    if (fresh.size >= 3) {
                        candidates = fresh
                    }
                }

                // ── Track used queries for future rotation; fallback searches were never learned ──
                val updatedRecentTokens =
                    if (discoveryQueries.isEmpty()) {
                        brain.recentQueryTokens
                    } else {
                        (brain.recentQueryTokens + candidates.map { tokenizer.tokenize(it).toSet() })
                            .takeLast(NeuroScoring.RECENT_QUERY_TOKENS_MAX)
                    }

                // ── Advance cluster rotation for the interest clusters actually served ──
                val candidateSet = candidates.toHashSet()
                val servedClusters =
                    discoveryQueries
                        .asSequence()
                        .filter { it.query in candidateSet }
                        .mapNotNull { it.clusterKey }
                        .toSet()
                val rotationNow = System.currentTimeMillis()
                val updatedRotation =
                    if (servedClusters.isEmpty()) {
                        brain.clusterRotation
                    } else {
                        val merged = brain.clusterRotation + servedClusters.associateWith { rotationNow }
                        if (merged.size > NeuroScoring.CLUSTER_ROTATION_MAX) {
                            merged.entries
                                .sortedByDescending { it.value }
                                .take(NeuroScoring.CLUSTER_ROTATION_MAX)
                                .associate { it.key to it.value }
                        } else {
                            merged
                        }
                    }

                currentUserBrain =
                    currentUserBrain.copy(
                        recentQueryTokens = updatedRecentTokens,
                        clusterRotation = updatedRotation,
                    )
                scheduleDebouncedSave(bookkeeping = true)

                Log.d(TAG, "Discovery queries (${candidates.size}): ${candidates.take(6)}")

                candidates
            }
        }

    /**
     * With [longTermCandidates], the last of [maxSeeds] goes to a lasting interest none of the
     * recent seeds covers, so a week of one new hobby cannot push the older ones out of the lane.
     */
    suspend fun selectRelatedSeeds(
        candidates: List<GraphSeedInput>,
        maxSeeds: Int = 4,
        longTermCandidates: List<GraphSeedInput> = emptyList(),
    ): List<String> =
        withContext(Dispatchers.Default) {
            if (candidates.isEmpty()) return@withContext emptyList()
            val now = System.currentTimeMillis()
            val seedCooldownCutoff = now - (NeuroScoring.RELATED_SEED_COOLDOWN_HOURS * 60 * 60 * 1000L)
            val excludedChannelIds: Set<String>
            val recentSeedIds: Set<String>
            val topicScores: Map<String, Double>
            val brainSnapshot: UserBrain
            withBrainLock {
                brainSnapshot = currentUserBrain
                val channelSuppressionCutoff = now - (CHANNEL_SUPPRESSION_DAYS * 24 * 60 * 60 * 1000L)
                excludedChannelIds = currentUserBrain.blockedChannels +
                    currentUserBrain.suppressedChannels
                        .filter { (_, ts) -> ts > channelSuppressionCutoff }
                        .keys
                recentSeedIds =
                    currentUserBrain.recentRelatedSeeds
                        .filter { (_, ts) -> ts > seedCooldownCutoff }
                        .keys
                topicScores = currentUserBrain.interests.topics
            }
            val hidden = brainSnapshot.suppressedVideoIds.keys

            // Map seed topic keys to interest communities so the spread-first pick
            // allocates one related seed per MAJOR interest (mma, android, anime…)
            // before any interest gets a second one.
            val clusters = clustersOf(brainSnapshot)
            val topicToCommunity = clusters.flatMap { cluster -> cluster.topics.map { it to cluster.representative } }.toMap()
            val communityOf = { key: String -> topicToCommunity[NeuroScoring.stripDomainTag(key)] ?: key }

            // Seed rotation: newest-first selection kept picking the same seeds every
            // refresh, making the RELATED lane byte-identical. Recently used seeds are
            // cooled down unless that would starve the selection.
            val selected =
                GraphSeedSelector.selectWithLongTerm(
                    candidates = candidates.filterNot { it.id in hidden },
                    maxSeeds = maxSeeds,
                    longTermCandidates = longTermCandidates.filterNot { it.id in hidden },
                    communityMass = clusters.associate { it.representative to it.mass },
                    communityOf = communityOf,
                    now = now,
                    cooledIds = recentSeedIds,
                    excludedChannelIds = excludedChannelIds,
                    topicScores = topicScores,
                )
            if (selected.isNotEmpty()) {
                val deepFlow = isLearningPaused()
                withBrainLock {
                    syncBookkeeping(deepFlow)
                    val updated = currentUserBrain.recentRelatedSeeds.toMutableMap()
                    updated.entries.removeAll { it.value < seedCooldownCutoff }
                    selected.forEach { updated[it] = now }
                    val capped =
                        if (updated.size > NeuroScoring.RECENT_RELATED_SEEDS_MAX) {
                            updated.entries
                                .sortedByDescending { it.value }
                                .take(NeuroScoring.RECENT_RELATED_SEEDS_MAX)
                                .associate { it.key to it.value }
                        } else {
                            updated
                        }
                    currentUserBrain = currentUserBrain.copy(recentRelatedSeeds = capped)
                    scheduleDebouncedSave(bookkeeping = true)
                }
            }
            selected
        }

    /**
     * The reels a related reel chain is opened from, with the same six-hour rotation the video
     * seeds get, so two sessions in a row do not reopen the same chain.
     */
    suspend fun selectShortsSeeds(
        candidates: List<ShortsSeedInput>,
        maxSeeds: Int,
    ): List<String> =
        withContext(Dispatchers.Default) {
            if (candidates.isEmpty() || maxSeeds <= 0) return@withContext emptyList()
            val now = System.currentTimeMillis()
            val seedCooldownCutoff = now - (NeuroScoring.RELATED_SEED_COOLDOWN_HOURS * 60 * 60 * 1000L)
            val excludedChannelIds: Set<String>
            val recentSeedIds: Set<String>
            withBrainLock {
                val channelSuppressionCutoff = now - (CHANNEL_SUPPRESSION_DAYS * 24 * 60 * 60 * 1000L)
                excludedChannelIds =
                    currentUserBrain.blockedChannels +
                    currentUserBrain.suppressedChannels.filter { (_, ts) -> ts > channelSuppressionCutoff }.keys
                recentSeedIds = currentUserBrain.recentShortsSeeds.filter { (_, ts) -> ts > seedCooldownCutoff }.keys
            }
            val selected = ShortsSeedSelector.select(candidates, maxSeeds, now, excludedChannelIds, recentSeedIds)
            if (selected.isNotEmpty()) {
                val deepFlow = isLearningPaused()
                withBrainLock {
                    syncBookkeeping(deepFlow)
                    val updated = currentUserBrain.recentShortsSeeds.toMutableMap()
                    updated.entries.removeAll { it.value < seedCooldownCutoff }
                    selected.forEach { updated[it] = now }
                    val capped =
                        updated.entries
                            .sortedByDescending { it.value }
                            .take(NeuroScoring.RECENT_RELATED_SEEDS_MAX)
                            .associate { it.key to it.value }
                    currentUserBrain = currentUserBrain.copy(recentShortsSeeds = capped)
                    scheduleDebouncedSave(bookkeeping = true)
                }
            }
            selected
        }

    /** Blocked + actively suppressed channels, for assembly paths that bypass rank(). */
    suspend fun getExcludedChannelIds(): Set<String> =
        withBrainLock {
            val cutoff = System.currentTimeMillis() - (CHANNEL_SUPPRESSION_DAYS * 24 * 60 * 60 * 1000L)
            currentUserBrain.blockedChannels +
                currentUserBrain.suppressedChannels
                    .filter { (_, ts) -> ts > cutoff }
                    .keys
        }

    /** The blocked topics as one text test, for feed paths whose reels never pass through [rank]. */
    suspend fun blockedContentMatcher(): (title: String, channelName: String) -> Boolean {
        val matchers =
            withBrainLock {
                NeuroScoring.buildBlockedMatchers(
                    currentUserBrain.blockedTopics,
                    NeuroTopicCatalog.TOPIC_CATEGORIES,
                    tokenizer::keyOf,
                )
            }
        if (matchers.isEmpty()) return { _, _ -> false }
        return { title, channelName -> NeuroScoring.isBlockedByText(title, channelName, matchers, tokenizer::keyOf) }
    }

    /** The same suppression windows [rank] applies; the topic matchers are built only if a caller asks. */
    suspend fun feedExclusions(now: Long = System.currentTimeMillis()): FeedExclusions {
        val brain = withBrainLock { currentUserBrain }
        val channelCutoff = now - CHANNEL_SUPPRESSION_DAYS * 86_400_000L
        val matchers by lazy {
            NeuroScoring.buildBlockedMatchers(brain.blockedTopics, NeuroTopicCatalog.TOPIC_CATEGORIES, tokenizer::keyOf)
        }
        return FeedExclusions(
            suppressedVideoIds = brain.suppressedVideoIds.keys,
            blockedChannelIds = brain.blockedChannels,
            suppressedChannelIds = brain.suppressedChannels.filterValues { it > channelCutoff }.keys,
            blockedText = { title, channelName ->
                brain.blockedTopics.isNotEmpty() &&
                    NeuroScoring.isBlockedByText(title, channelName, matchers, tokenizer::keyOf)
            },
        )
    }

    /**
     * Learns a channel's creator-declared keyword tags (+ description lead) into
     * its topic profile and the tag-affinity graph. Authored identity — the
     * strongest channel-level signal available; fetched on subscribe.
     */
    suspend fun onChannelTagsLearned(
        channelId: String,
        tags: List<String>,
        description: String?,
    ) {
        if (channelId.isBlank()) return
        val additions = NeuroChannelKnowledge.profileFromChannelTags(tags, description, tokenizer)
        if (additions.isEmpty()) return
        withBrainLock {
            val profiles = currentUserBrain.channelTopicProfiles.toMutableMap()
            profiles[channelId] = NeuroChannelKnowledge.mergeProfile(profiles[channelId].orEmpty(), additions)

            // Creator-authored co-occurrence: declared tags belong together.
            val tagAffinities = currentUserBrain.tagAffinities.toMutableMap()
            val tokens = additions.keys.take(5).toList()
            for (i in tokens.indices) {
                for (j in i + 1 until tokens.size) {
                    val key = NeuroScoring.makeAffinityKey(tokens[i], tokens[j])
                    tagAffinities[key] =
                        ((tagAffinities[key] ?: 0.0) + NeuroScoring.TAG_AFFINITY_INCREMENT)
                            .coerceAtMost(NeuroScoring.AFFINITY_MAX)
                }
            }
            val cappedTagAffinities =
                if (tagAffinities.size > NeuroScoring.TAG_AFFINITY_MAX_ENTRIES) {
                    tagAffinities.entries
                        .sortedByDescending { it.value }
                        .take(NeuroScoring.TAG_AFFINITY_KEEP_TOP)
                        .associate { it.key to it.value }
                } else {
                    tagAffinities
                }

            currentUserBrain =
                currentUserBrain.copy(
                    channelTopicProfiles = capChannelProfiles(profiles),
                    tagAffinities = cappedTagAffinities,
                )
            scheduleDebouncedSave()
        }
    }

    /**
     * Passive channel profiling from upload titles the subs lane already fetched.
     * Low weight, once per channel per session — teaches what each subscribed
     * channel is about without waiting for the user to click anything.
     */
    suspend fun onChannelUploadsObserved(uploads: List<Video>) {
        if (uploads.isEmpty()) return
        val byChannel = uploads.filter { it.channelId.isNotBlank() }.groupBy { it.channelId }
        if (byChannel.isEmpty()) return
        withBrainLock {
            var profiles: MutableMap<String, Map<String, Double>>? = null
            byChannel.forEach { (channelId, videos) ->
                if (!passiveProfiledChannels.add(channelId)) return@forEach
                val additions =
                    NeuroChannelKnowledge.profileFromUploadTitles(
                        videos.take(6).map { it.title },
                        tokenizer,
                    )
                if (additions.isEmpty()) return@forEach
                val target =
                    profiles ?: currentUserBrain.channelTopicProfiles.toMutableMap().also { profiles = it }
                target[channelId] = NeuroChannelKnowledge.mergeProfile(target[channelId].orEmpty(), additions)
            }
            profiles?.let {
                currentUserBrain = currentUserBrain.copy(channelTopicProfiles = capChannelProfiles(it))
                scheduleDebouncedSave(bookkeeping = true)
            }
        }
    }

    // Evicts the lowest-quality channels' profiles when over the cap. Call under brainMutex.
    private fun capChannelProfiles(profiles: MutableMap<String, Map<String, Double>>): Map<String, Map<String, Double>> {
        if (profiles.size <= NeuroScoring.CHANNEL_PROFILE_MAX_CHANNELS) return profiles
        val channelScores = currentUserBrain.channelScores
        profiles.entries
            .sortedBy { (id, _) -> channelScores[id] ?: 0.0 }
            .take(profiles.size - NeuroScoring.CHANNEL_PROFILE_MAX_CHANNELS)
            .map { it.key }
            .forEach { profiles.remove(it) }
        return profiles.toMap()
    }

    /** Feed-history ids shown within the window — for assembly-time exclusion sets. */
    suspend fun getRecentlyShownVideoIds(withinHours: Long = 48L): Set<String> =
        withBrainLock {
            val cutoff = System.currentTimeMillis() - withinHours * 60 * 60 * 1000L
            currentUserBrain.feedHistory.filter { it.value.lastShown > cutoff }.keys
        }

    /**
     * Marks a discovery query stale when its RESULTS were mostly already shown —
     * a stronger repetition signal than query-wording overlap. Stale queries are
     * skipped by generateDiscoveryQueries for STALE_QUERY_EXPIRY_HOURS; a query
     * that comes back with fresh results clears its mark.
     */
    suspend fun reportQueryResultNovelty(
        query: String,
        novelRatio: Double,
    ) {
        val key = queryStaleKey(query) ?: return
        val deepFlow = isLearningPaused()
        withBrainLock {
            syncBookkeeping(deepFlow)
            val now = System.currentTimeMillis()
            val expiryCutoff = now - NeuroScoring.STALE_QUERY_EXPIRY_HOURS * 60 * 60 * 1000L
            val updated = currentUserBrain.staleQueries.toMutableMap()
            updated.entries.removeAll { it.value < expiryCutoff }
            if (novelRatio < NeuroScoring.STALE_QUERY_NOVELTY_THRESHOLD) {
                updated[key] = now
                if (updated.size > NeuroScoring.STALE_QUERY_MAX) {
                    val oldest = updated.entries.minByOrNull { it.value }
                    oldest?.let { updated.remove(it.key) }
                }
            } else {
                updated.remove(key)
            }
            currentUserBrain = currentUserBrain.copy(staleQueries = updated)
            scheduleDebouncedSave(bookkeeping = true)
        }
    }

    private fun queryStaleKey(query: String): String? {
        val tokens = tokenizer.tokenize(query)
        if (tokens.isEmpty()) return null
        return tokens.sorted().joinToString("|")
    }

    // =================================================
    // MAIN RANKING FUNCTION
    // =================================================

    /**
     * [minRelevance] above 0 drops candidates that share less topic overlap with the viewer (see [NeuroScoring.keepRelevant]).
     * [trace] collects why each returned video was admitted.
     */
    suspend fun rank(
        candidates: List<Video>,
        userSubs: Set<String>,
        minRelevance: Double = 0.0,
        trace: RankTrace? = null,
    ): List<Video> = traced(TRACE_RANK) { rankTraced(candidates, userSubs, minRelevance, trace) }

    private suspend fun rankTraced(
        candidates: List<Video>,
        userSubs: Set<String>,
        minRelevance: Double,
        trace: RankTrace?,
    ): List<Video> =
        withContext(Dispatchers.Default) {
            if (candidates.isEmpty()) return@withContext emptyList()

            // Session staleness auto-reset
            val sessionAgeMinutes = getSessionDurationMinutes()
            if (sessionAgeMinutes > NeuroScoring.SESSION_RESET_IDLE_MINUTES ||
                (
                    sessionAgeMinutes > NeuroScoring.SESSION_RESET_EMPTY_MINUTES &&
                        sessionVideoCount == 0
                )
            ) {
                withBrainLock { resetSessionInternal() }
            }

            // Take consistent snapshots under the lock
            val brain: UserBrain
            val idfSnapshot: IdfSnapshot
            val sessionTopics: List<String>
            val impressionSnapshot: Map<String, ImpressionEntry>
            val watchHistorySnapshot: Map<String, WatchEntry>
            val recentInteractionsSnapshot: List<MomentumEntry>

            withBrainLock {
                brain = currentUserBrain
                idfSnapshot = takeIdfSnapshot()
                sessionTopics = sessionTopicHistory.toList()
                impressionSnapshot = impressionCache.toMap()
                watchHistorySnapshot = watchHistory.toMap()
                recentInteractionsSnapshot = recentInteractions.toList()
            }

            val random = rankRandom ?: java.util.Random()
            val now = System.currentTimeMillis()

            // Hard suppression: videos for good, channels for a rolling window
            val channelSuppressionCutoff = now - (CHANNEL_SUPPRESSION_DAYS * 24 * 60 * 60 * 1000L)
            val activeSuppressedVideos = brain.suppressedVideoIds.keys
            val activeSuppressedChannels =
                brain.suppressedChannels
                    .filter { (_, ts) -> ts > channelSuppressionCutoff }
                    .keys

            // Precompute blocked matchers ONCE (precision blocking — see NeuroScoring).
            val blockedMatchers =
                NeuroScoring.buildBlockedMatchers(
                    brain.blockedTopics,
                    NeuroTopicCatalog.TOPIC_CATEGORIES,
                    tokenizer::keyOf,
                )

            // ── Feed overlap ratio (for adaptive jitter + feed history penalty) ──
            val feedOverlapRatio =
                if (candidates.isEmpty() || brain.feedHistory.isEmpty()) {
                    0.0
                } else {
                    val candidateIds = candidates.map { it.id }.toSet()
                    val recentHistoryIds =
                        brain.feedHistory
                            .filter { (_, entry) ->
                                (now - entry.lastShown) < 48L * 60 * 60 * 1000
                            }.keys
                    val overlap = candidateIds.intersect(recentHistoryIds).size
                    (overlap.toDouble() / candidateIds.size).coerceIn(0.0, 1.0)
                }

            // Pre-filter blocked content
            val filtered =
                candidates.filter { video ->
                    if (video.id in activeSuppressedVideos) return@filter false
                    if (video.channelId in activeSuppressedChannels) return@filter false
                    if (brain.blockedChannels.contains(video.channelId)) {
                        return@filter false
                    }
                    !NeuroScoring.isBlockedByText(
                        video.title,
                        video.channelName,
                        blockedMatchers,
                        tokenizer::keyOf,
                    )
                }

            if (filtered.isEmpty()) return@withContext emptyList()

            // Hard seen-gate: recently over-shown items are removed, not just
            // penalized — score penalties let high-scoring repeats punch back in.
            val gated = NeuroScoring.applySeenGate(filtered, brain.feedHistory, now) { it.id }

            // Extract all features with consistent IDF snapshot
            val videoVectors =
                gated.map { video ->
                    val channelProfile = brain.channelTopicProfiles[video.channelId]
                    video to getOrExtractFeatures(video, idfSnapshot, channelProfile)
                }

            // Vector-level topic blocking: remove videos whose top topics match blocked topics
            val blockedTopicLemmas = brain.blockedTopics.map { tokenizer.keyOf(it) }.toSet()
            val unblocked =
                if (blockedTopicLemmas.isEmpty()) {
                    videoVectors
                } else {
                    videoVectors.filter { (_, vector) ->
                        val topVideoTopics =
                            vector.topics.entries
                                .sortedByDescending { it.value }
                                .take(3)
                                .map { it.key }
                        // Token-precise: "art" must block "art", not "startup".
                        !topVideoTopics.any { topic ->
                            val base = NeuroScoring.stripDomainTag(topic)
                            val parts = base.split(' ')
                            blockedTopicLemmas.any { blocked ->
                                base == blocked || parts.contains(blocked)
                            }
                        }
                    }
                }

            val vectorFiltered =
                if (minRelevance <= 0.0 || brain.totalInteractions < NeuroScoring.COLD_START_THRESHOLD) {
                    unblocked
                } else {
                    NeuroScoring.keepRelevant(
                        unblocked,
                        NeuroVectorMath.PreparedVector(brain.interests),
                        minRelevance,
                        userSubs,
                        brain.channelScores,
                        tokenizer::specificity,
                    )
                }

            // How many candidates carry a category decides whether a category probe is worth adding (D4, #907).
            val categorized =
                vectorFiltered.count { (video, _) ->
                    video.category != null ||
                        categoryStore.categoryOf(video.channelId) != null
                }
            Log.d(TAG, "Category known for $categorized of ${vectorFiltered.size} candidates")

            // Time context
            val bucket = TimeBucket.current()
            val timeContextVector = brain.timeVectors[bucket] ?: ContentVector()

            // Skipping several videos in a row earns an exploration slot (see applySmartDiversity); it
            // never reweights scoring, where it once made less relevant videos outscore relevant ones (#907).
            val tasteWeight = 0.8
            val wContext = tasteWeight * 0.5 * NeuroScoring.timeBucketConfidence(brain, bucket)
            val wPersonality = tasteWeight - wContext
            val wNovelty = 0.2

            // Onboarding warmup factor
            val isColdStart = brain.totalInteractions < NeuroScoring.COLD_START_THRESHOLD
            val isOnboarding = brain.totalInteractions < NeuroScoring.ONBOARDING_WARMUP_INTERACTIONS
            val onboardingWarmup =
                if (isOnboarding) {
                    1.0 - (
                        brain.totalInteractions /
                            NeuroScoring.ONBOARDING_WARMUP_INTERACTIONS.toDouble()
                    ) * 0.5
                } else {
                    0.5
                }

            // Precompute lemmatized preferred topics once (was per-candidate)
            val lemmatizedPreferred = brain.preferredTopics.flatMapTo(HashSet(), tokenizer::keysOf)

            // Persona sets exploration appetite: explorers roam, specialists stay focused.
            val exploreWeight =
                when (getPersona(brain)) {
                    FlowPersona.EXPLORER -> 1.0
                    FlowPersona.SPECIALIST -> 0.3
                    FlowPersona.INITIATE -> 0.0
                    else -> 0.6
                }

            val scoringParams =
                ScoringParams(
                    brain = brain,
                    userSubs = userSubs,
                    timeContextVector = timeContextVector,
                    wPersonality = wPersonality,
                    wContext = wContext,
                    wNovelty = wNovelty,
                    isColdStart = isColdStart,
                    isOnboarding = isOnboarding,
                    onboardingWarmup = onboardingWarmup,
                    lemmatizedPreferred = lemmatizedPreferred,
                    sessionTopics = sessionTopics,
                    sessionVideoCount = sessionVideoCount,
                    impressions = impressionSnapshot,
                    watchHistory = watchHistorySnapshot,
                    recentInteractions = recentInteractionsSnapshot,
                    candidatePoolSize = gated.size,
                    now = now,
                    exploreWeight = exploreWeight,
                    categoryOf = categoryStore::categoryOf,
                    coWatchPrior = NeuroCoWatchStore.neighbours(coWatchEdges(brain, now)) { isWatchedChannel(brain, it) },
                )

            // Jitter magnitude is invariant across candidates in a single rank() call
            val jitterMagnitude =
                NeuroScoring.calculateAdaptiveJitter(
                    brain.totalInteractions,
                    feedOverlapRatio,
                )

            // Score first, then jitter RELATIVE to the live score distribution.
            // Absolute jitter (0.12-0.20) frequently exceeded typical post-penalty
            // scores, degrading ranking to a shuffle exactly when the feed was stale.
            val baseScored =
                vectorFiltered.map { (video, videoVector) ->
                    Triple(video, videoVector, NeuroScoring.scoreCandidate(video, videoVector, scoringParams))
                }
            val medianScore =
                baseScored
                    .map { it.third }
                    .sorted()
                    .let { sortedScores ->
                        if (sortedScores.isEmpty()) 0.0 else sortedScores[sortedScores.size / 2]
                    }.coerceAtLeast(0.05)
            val jitterUnit = jitterMagnitude * medianScore
            val scored =
                baseScored
                    .map { (video, videoVector, base) ->
                        ScoredVideo(video, base + random.nextDouble() * jitterUnit, videoVector)
                    }.toMutableList()

            // Apply diversity reranking
            val extraExploration = if (brain.consecutiveSkips >= NeuroScoring.BOREDOM_SKIPS) 1 else 0
            val viewer = NeuroVectorMath.PreparedVector(brain.interests)
            // Every diversity phase asks, so each candidate is judged once.
            val unrelated = HashMap<String, Boolean>()
            val isUnrelated = { candidate: ScoredVideo ->
                unrelated.getOrPut(candidate.video.id) {
                    brain.totalInteractions >= NeuroScoring.COLD_START_THRESHOLD &&
                        candidate.video.channelId !in userSubs &&
                        (brain.channelScores[candidate.video.channelId] ?: 0.0) < NeuroScoring.KNOWN_CHANNEL_SCORE &&
                        NeuroVectorMath.topicSimilarity(viewer, candidate.vector, tokenizer::specificity) <
                        NeuroScoring.UNRELATED_SIMILARITY
                }
            }
            // A Home lane under the relevance floor never fills with them; the Shorts feed, chips and channel
            // memory still place them last, as they always did.
            return@withContext NeuroScoring.applySmartDiversity(
                scored,
                tokenizer,
                extraExploration,
                isUnrelated,
                trace,
                dropUnrelated = minRelevance > 0.0,
            )
        }

    suspend fun recordFeedImpressions(ids: List<String>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.Default) { recordFeedImpressionsLocked(ids) }
    }

    private suspend fun recordFeedImpressionsLocked(ids: List<String>) {
        val now = System.currentTimeMillis()
        val deepFlow = isLearningPaused()

        withBrainLock {
            syncBookkeeping(deepFlow)
            val fresh = FeedImpressionLedger.countable(ids, currentUserBrain.feedHistory, now)
            if (fresh.isEmpty()) return@withBrainLock

            fresh.forEach { id ->
                val existing = impressionCache[id]
                if (existing != null) {
                    existing.count++
                    existing.lastSeen = now
                } else {
                    impressionCache[id] = ImpressionEntry(1, now)
                }
            }

            val updatedHistory = currentUserBrain.feedHistory.toMutableMap()
            fresh.forEach { id ->
                val prev = updatedHistory[id]
                updatedHistory[id] =
                    FeedEntry(
                        lastShown = now,
                        showCount = (prev?.showCount ?: 0) + 1,
                    )
            }

            val expiryCutoff =
                now - (
                    NeuroScoring.FEED_HISTORY_EXPIRY_DAYS * 24 * 60 * 60 * 1000
                )
            val pruned =
                if (updatedHistory.size > NeuroScoring.FEED_HISTORY_MAX) {
                    updatedHistory.entries
                        .filter { it.value.lastShown > expiryCutoff }
                        .sortedByDescending { it.value.lastShown }
                        .take(NeuroScoring.FEED_HISTORY_MAX)
                        .associate { it.key to it.value }
                } else {
                    updatedHistory.filter { it.value.lastShown > expiryCutoff }
                }

            currentUserBrain = currentUserBrain.copy(feedHistory = pruned)
            scheduleDebouncedSave(bookkeeping = true)
        }
    }

    // =================================================
    // LEARNING FUNCTION
    // =================================================

    suspend fun onVideoInteraction(
        video: Video,
        interactionType: InteractionType,
        percentWatched: Float = 0f,
    ) {
        // Deep Flow mode: freeze vector learning while active and not yet expired
        if (isLearningPaused()) return

        val idfSnapshot = withBrainLock { takeIdfSnapshot() }
        val videoVector = getOrExtractFeatures(video, idfSnapshot)

        val absoluteMinutesWatched =
            if (
                interactionType == InteractionType.WATCHED && video.duration > 0
            ) {
                (video.duration * percentWatched / 60.0).coerceAtLeast(0.0)
            } else {
                0.0
            }

        var learningRate =
            when (interactionType) {
                InteractionType.CLICK -> {
                    0.03
                }

                InteractionType.LIKED -> {
                    0.30
                }

                InteractionType.SAVED -> {
                    0.22
                }

                InteractionType.WATCHED -> {
                    val baseWatchRate = 0.15 * percentWatched
                    val timeBonus = (
                        ln(1.0 + absoluteMinutesWatched) /
                            ln(1.0 + 60.0) * 0.08
                    )
                    baseWatchRate + timeBonus
                }

                InteractionType.SKIPPED -> {
                    -0.15
                }

                InteractionType.DISLIKED -> {
                    -0.40
                }
            }

        if (video.isShort) {
            learningRate *= NeuroScoring.SHORTS_LEARNING_PENALTY
        }
        var strength = learningRate / NeuroVectorMath.DECAY_REFERENCE_RATE

        withBrainLock {
            // Maturity damping slows duration, pacing and complexity; topic evidence needs no brake (see NeuroVectorMath.Horizon).
            if (learningRate > 0) {
                val maturityDamping =
                    1.0 / (
                        1.0 +
                            ln(1.0 + currentUserBrain.totalInteractions / 50.0)
                    )
                learningRate *= maturityDamping.coerceIn(0.25, 1.0)
            }

            // 0. Watch velocity: adjust click learning rate based on impression timing
            if (interactionType == InteractionType.CLICK) {
                val impression = impressionCache[video.id]
                if (impression != null) {
                    val secondsSinceImpression =
                        (System.currentTimeMillis() - impression.lastSeen) / 1000.0
                    val clickVelocity =
                        when {
                            secondsSinceImpression < 5.0 -> 1.5

                            // instant click
                            secondsSinceImpression < 30.0 -> 1.0

                            // normal
                            secondsSinceImpression < 120.0 -> 0.8

                            // delayed
                            else -> 0.6 // much later
                        }
                    learningRate *= clickVelocity
                    strength *= clickVelocity
                }
            }

            // 1. Update the long-term and recent taste
            val learningVector = if (learningRate > 0) NeuroVectorMath.phraseFirst(videoVector) else videoVector
            val pickKeys = currentUserBrain.preferredTopics.flatMapTo(HashSet(), tokenizer::keysOf)

            fun learnOn(
                vector: ContentVector,
                horizon: NeuroVectorMath.Horizon,
            ) = if (learningRate > 0) {
                NeuroVectorMath.learn(vector, learningVector, strength, horizon, learningRate, pickKeys)
            } else {
                NeuroVectorMath.adjustVector(vector, videoVector, learningRate, pruneBelow = NeuroVectorMath.EVIDENCE_PRUNE_THRESHOLD)
            }
            var newGlobal = learnOn(currentUserBrain.globalVector, NeuroVectorMath.LONG_TERM)
            val newRecent = learnOn(currentUserBrain.recentVector, NeuroVectorMath.RECENT)

            // 1a. A real watch, like or save counts at least the acquisition floor for its top
            // topics (see NeuroVectorMath.plantTopics).
            val isStrongSignal =
                interactionType == InteractionType.LIKED ||
                    interactionType == InteractionType.SAVED ||
                    (interactionType == InteractionType.WATCHED && percentWatched >= 0.40f)
            if (isStrongSignal && !video.isShort) {
                newGlobal =
                    NeuroVectorMath.plantTopics(
                        newGlobal,
                        videoVector,
                        NeuroScoring.TOPIC_ACQUISITION_FLOOR,
                        NeuroScoring.TOPIC_ACQUISITION_TOP_K,
                        tokenizer.plantFilter(video, idfSnapshot, ownDocuments = if (isDocumentCounted(video.id)) 1 else 0),
                    )
            }
            newGlobal = NeuroVectorMath.capTopics(newGlobal, NeuroVectorMath.MAX_GLOBAL_TOPICS, pickKeys)

            // A watch session ends in one WATCHED or SKIPPED; either way the channel posted in that category.
            val categoryId = NeuroCategories.idOf(video.category)
            val sessionEnded = interactionType == InteractionType.WATCHED || interactionType == InteractionType.SKIPPED
            if (categoryId != null && sessionEnded) categoryStore.record(video.channelId, categoryId)
            if (isStrongSignal &&
                !video.isShort
            ) {
                pendingRelated.remove(video.id)?.let { coWatchStore.record(video.id, video.channelId, it) }
            }

            // 1b. Shorts-specific vector (not dampened by SHORTS_LEARNING_PENALTY)
            val newShortsVector =
                if (video.isShort) {
                    val shortsRate =
                        when (interactionType) {
                            InteractionType.CLICK -> 0.08
                            InteractionType.LIKED -> 0.20
                            InteractionType.SAVED -> 0.15
                            InteractionType.WATCHED -> 0.10 * percentWatched
                            InteractionType.SKIPPED -> -0.12
                            InteractionType.DISLIKED -> -0.30
                        }
                    NeuroVectorMath.adjustVector(
                        currentUserBrain.shortsVector,
                        videoVector,
                        shortsRate,
                    )
                } else {
                    currentUserBrain.shortsVector
                }

            // 2. Update time bucket
            val bucket = TimeBucket.current()
            val currentBucketVec =
                currentUserBrain.timeVectors[bucket]
                    ?: ContentVector()
            val newBucketVec = learnOn(currentBucketVec, NeuroVectorMath.TIME_OF_DAY)
            val newBucketCounts =
                if (learningRate > 0 && !video.isShort) {
                    currentUserBrain.timeBucketCounts + (bucket to (currentUserBrain.timeBucketCounts[bucket] ?: 0) + 1)
                } else {
                    currentUserBrain.timeBucketCounts
                }

            // 3. Channel score
            val currentChScore =
                currentUserBrain.channelScores[video.channelId] ?: 0.5
            val outcome = if (learningRate > 0) 1.0 else 0.0
            val newChScore =
                if (interactionType == InteractionType.DISLIKED) {
                    (currentChScore * NeuroScoring.DISLIKE_CHANNEL_FACTOR).coerceAtLeast(0.01)
                } else {
                    (currentChScore * NeuroScoring.CHANNEL_EMA_DECAY) +
                        (outcome * NeuroScoring.CHANNEL_EMA_ALPHA)
                }
            var newChannelScores =
                currentUserBrain.channelScores +
                    (video.channelId to newChScore)

            // Channel pruning
            if (newChannelScores.size > NeuroScoring.MAX_CHANNEL_SCORES) {
                val sorted = newChannelScores.entries.sortedBy { it.value }
                val keepLow = sorted.take(NeuroScoring.CHANNEL_KEEP_LOW)
                val keepHigh = sorted.takeLast(NeuroScoring.CHANNEL_KEEP_HIGH)
                val keepSet = (keepLow + keepHigh).map { it.key }.toSet()
                newChannelScores =
                    newChannelScores
                        .filter { it.key in keepSet }
            }

            // 4. Consecutive skips. Boredom is passive skipping; an explicit rejection says what the
            // viewer does not want, which is no reason to make the next feed more random (#907).
            val newSkips =
                when (interactionType) {
                    InteractionType.CLICK, InteractionType.LIKED,
                    InteractionType.WATCHED, InteractionType.SAVED,
                    -> {
                        0
                    }

                    // A swiped-away Short is how Shorts are browsed, not a sign of boredom with the feed (#907).
                    InteractionType.SKIPPED -> {
                        if (video.isShort) {
                            currentUserBrain.consecutiveSkips
                        } else {
                            (currentUserBrain.consecutiveSkips + 1).coerceAtMost(NeuroScoring.MAX_CONSECUTIVE_SKIPS)
                        }
                    }

                    InteractionType.DISLIKED -> {
                        currentUserBrain.consecutiveSkips
                    }
                }
            val newRejectionPatterns =
                if (interactionType == InteractionType.DISLIKED) {
                    NeuroScoring.recordRejection(currentUserBrain.rejectionPatterns, videoVector, System.currentTimeMillis())
                } else {
                    currentUserBrain.rejectionPatterns
                }

            // 5. Topic co-occurrence
            var newAffinities = currentUserBrain.topicAffinities
            if (learningRate > 0) {
                val topTopics =
                    videoVector.topics.entries
                        .sortedByDescending { it.value }
                        .take(3)
                        .map { NeuroScoring.stripDomainTag(it.key) }
                        .distinct()
                if (topTopics.size >= 2) {
                    val mutableAffinities = newAffinities.toMutableMap()
                    for (i in topTopics.indices) {
                        for (j in i + 1 until topTopics.size) {
                            val key =
                                NeuroScoring.makeAffinityKey(
                                    topTopics[i],
                                    topTopics[j],
                                )
                            val current = mutableAffinities[key] ?: 0.0
                            mutableAffinities[key] =
                                (current + NeuroScoring.AFFINITY_INCREMENT)
                                    .coerceAtMost(NeuroScoring.AFFINITY_MAX)
                        }
                    }
                    // No per-update value pruning: the old filter (> 0.05) deleted every
                    // newborn edge in the same update that created it (+0.01), so no pair
                    // could EVER accumulate organically. The size cap alone bounds growth —
                    // it evicts the weakest pairs first, which is the pruning we wanted.
                    newAffinities = mutableAffinities
                    if (newAffinities.size > NeuroScoring.AFFINITY_MAX_ENTRIES) {
                        newAffinities =
                            newAffinities.entries
                                .sortedByDescending { it.value }
                                .take(NeuroScoring.AFFINITY_KEEP_TOP)
                                .associate { it.key to it.value }
                    }
                }
            }

            // 5b. Tag-grounded knowledge: persist the tag-rich vector so this video
            // is scored on real content when it reappears as a bare-title candidate,
            // and record tag co-occurrence edges for interest clustering.
            var newTagAffinities = currentUserBrain.tagAffinities
            if (learningRate > 0 && video.tags.isNotEmpty()) {
                contentStore.put(video.id, videoVector.topics)

                val tagTokens =
                    video.tags
                        .asSequence()
                        .flatMap { tokenizer.tokenize(it) }
                        .distinct()
                        .take(NeuroScoring.TAG_AFFINITY_TOKENS)
                        .toList()
                if (tagTokens.size >= 2) {
                    val mutableTags = newTagAffinities.toMutableMap()
                    for (i in tagTokens.indices) {
                        for (j in i + 1 until tagTokens.size) {
                            val key = NeuroScoring.makeAffinityKey(tagTokens[i], tagTokens[j])
                            mutableTags[key] =
                                ((mutableTags[key] ?: 0.0) + NeuroScoring.TAG_AFFINITY_INCREMENT)
                                    .coerceAtMost(NeuroScoring.AFFINITY_MAX)
                        }
                    }
                    newTagAffinities =
                        if (mutableTags.size > NeuroScoring.TAG_AFFINITY_MAX_ENTRIES) {
                            mutableTags.entries
                                .sortedByDescending { it.value }
                                .take(NeuroScoring.TAG_AFFINITY_KEEP_TOP)
                                .associate { it.key to it.value }
                        } else {
                            mutableTags
                        }
                }
            }

            val topicEvidenceSignal =
                calculateTopicEvidenceSignal(
                    interactionType,
                    percentWatched,
                    video.isShort,
                )
            val positiveEvidence =
                updateTopicEvidence(
                    currentUserBrain.topicEvidence,
                    videoVector,
                    video,
                    signalScore = topicEvidenceSignal,
                    isWatchSignal =
                        interactionType == InteractionType.WATCHED &&
                            percentWatched >= 0.40f,
                    isExplicitSignal =
                        interactionType == InteractionType.LIKED ||
                            interactionType == InteractionType.SAVED,
                )
            val newTopicEvidence =
                if (
                    interactionType == InteractionType.SKIPPED ||
                    interactionType == InteractionType.DISLIKED
                ) {
                    bumpNegativeEvidence(positiveEvidence, videoVector)
                } else {
                    positiveEvidence
                }

            // 6. Update IDF counters on interaction
            if (learningRate > 0 && countDocument(video.id) { tokenizer.documentTerms(video, videoVector) }) {
                compactIdfIfNeeded()

                if (idfTotalDocuments % 100 == 0) {
                    featureCache.clear()
                }
            }

            // 7. Persona tracking
            val rawPersona = getPersona(currentUserBrain)
            val lastPersonaName = currentUserBrain.lastPersona
            val newStability =
                if (rawPersona.name == lastPersonaName) {
                    (currentUserBrain.personaStability + 1)
                        .coerceAtMost(NeuroScoring.PERSONA_MAX_STABILITY)
                } else {
                    1
                }

            // 8. Session tracking
            val primaryTopic =
                videoVector.topics
                    .maxByOrNull { it.value }
                    ?.key
            if (primaryTopic != null) {
                sessionTopicHistory.add(primaryTopic)
                while (sessionTopicHistory.size > SESSION_TOPIC_HISTORY_MAX) {
                    sessionTopicHistory.removeAt(0)
                }
            }
            sessionVideoCount++

            // 9. Channel topic profile update
            var newChannelProfiles = currentUserBrain.channelTopicProfiles
            if (learningRate > 0) {
                val channelId = video.channelId
                val existingProfile =
                    newChannelProfiles[channelId]?.toMutableMap()
                        ?: mutableMapOf()

                videoVector.topics.forEach { (topic, weight) ->
                    val current = existingProfile[topic] ?: 0.0
                    existingProfile[topic] = current +
                        (weight - current) * NeuroScoring.CHANNEL_PROFILE_LEARNING_RATE
                }

                val profileIterator = existingProfile.iterator()
                while (profileIterator.hasNext()) {
                    val entry = profileIterator.next()
                    if (!videoVector.topics.containsKey(entry.key)) {
                        entry.setValue(entry.value * 0.98)
                    }
                    if (entry.value < NeuroScoring.CHANNEL_PROFILE_PRUNE_THRESHOLD) {
                        profileIterator.remove()
                    }
                }

                val pruned =
                    if (existingProfile.size > NeuroScoring.CHANNEL_PROFILE_MAX_TOPICS) {
                        existingProfile.entries
                            .sortedByDescending { it.value }
                            .take(NeuroScoring.CHANNEL_PROFILE_MAX_TOPICS)
                            .associate { it.key to it.value }
                    } else {
                        existingProfile.toMap()
                    }

                val mutableProfiles = newChannelProfiles.toMutableMap()
                mutableProfiles[channelId] = pruned

                if (mutableProfiles.size > NeuroScoring.CHANNEL_PROFILE_MAX_CHANNELS) {
                    val channelScoreMap = currentUserBrain.channelScores
                    val sorted =
                        mutableProfiles.entries.sortedBy { (id, _) ->
                            channelScoreMap[id] ?: 0.0
                        }
                    val toRemove =
                        sorted.take(
                            mutableProfiles.size - NeuroScoring.CHANNEL_PROFILE_MAX_CHANNELS,
                        )
                    toRemove.forEach { mutableProfiles.remove(it.key) }
                }

                newChannelProfiles = mutableProfiles
            }

            // 10. Engagement momentum tracking
            if (primaryTopic != null) {
                recentInteractions.add(MomentumEntry(primaryTopic, learningRate > 0))
                while (recentInteractions.size > NeuroScoring.MOMENTUM_WINDOW) {
                    recentInteractions.removeAt(0)
                }
            }

            if (learningRate > 0) {
                impressionCache.remove(video.id)
            }

            if (interactionType == InteractionType.WATCHED &&
                percentWatched > NeuroScoring.WATCHED_THRESHOLD_SAMPLED
            ) {
                val existing = watchHistory[video.id]
                if (existing == null ||
                    percentWatched > existing.percentWatched
                ) {
                    watchHistory[video.id] =
                        WatchEntry(
                            percentWatched,
                            System.currentTimeMillis(),
                        )
                    while (watchHistory.size > NeuroScoring.WATCH_HISTORY_MAX) {
                        val oldestKey = watchHistory.keys.first()
                        watchHistory.remove(oldestKey)
                    }
                }
            }

            val watchHistoryMap =
                watchHistory.mapValues { (_, entry) ->
                    entry.percentWatched
                }

            val profile = currentUserBrain.languageProfile ?: tokenizer.languageProfile
            val language =
                if (learningRate > 0 && interactionType == InteractionType.WATCHED) {
                    val firstLine =
                        video.description
                            .lineSequence()
                            .firstOrNull { it.isNotBlank() }
                            .orEmpty()
                    NeuroLanguages.vote(profile, currentUserBrain.languageVotes, NeuroLanguages.detectText("${video.title}\n$firstLine"))
                } else {
                    profile to currentUserBrain.languageVotes
                }
            if (language.first != profile) {
                Log.i(TAG, "Language profile $profile → ${language.first}")
                tokenizer.languageProfile = language.first
            }

            currentUserBrain =
                currentUserBrain.copy(
                    globalVector = newGlobal,
                    recentVector = newRecent,
                    timeVectors =
                        currentUserBrain.timeVectors +
                            (bucket to newBucketVec),
                    timeBucketCounts = newBucketCounts,
                    channelScores = newChannelScores,
                    topicAffinities = newAffinities,
                    totalInteractions = currentUserBrain.totalInteractions + 1,
                    consecutiveSkips = newSkips,
                    lastPersona = rawPersona.name,
                    personaStability = newStability,
                    idfWordFrequency = idfWordFrequency.toMap(),
                    idfTotalDocuments = idfTotalDocuments,
                    watchHistoryMap = watchHistoryMap,
                    categoryAffinity =
                        if (categoryId != null && isStrongSignal && !video.isShort) {
                            NeuroCategories.learn(currentUserBrain.categoryAffinity, categoryId)
                        } else {
                            currentUserBrain.categoryAffinity
                        },
                    formatAffinity =
                        if (learningRate > 0 && !video.isShort) {
                            NeuroFormats.learn(currentUserBrain.formatAffinity, videoVector.formats)
                        } else {
                            currentUserBrain.formatAffinity
                        },
                    languageProfile = language.first,
                    languageVotes = language.second,
                    displayForms =
                        if (learningRate > 0) {
                            val keys = learningVector.topics.keys
                            val fromTitle =
                                NeuroDisplayForms.record(
                                    currentUserBrain.displayForms,
                                    NeuroDisplayForms.spellingsIn(video.title, keys, tokenizer),
                                ) { it in newGlobal.topics }
                            // Keys stemmed from tags or the channel name still read as written somewhere.
                            val elsewhere = (video.tags + video.channelName).joinToString(" | ")
                            NeuroDisplayForms.fillGaps(fromTitle, NeuroDisplayForms.spellingsIn(elsewhere, keys, tokenizer)) {
                                it in
                                    newGlobal.topics
                            }
                        } else {
                            currentUserBrain.displayForms
                        },
                    channelTopicProfiles = newChannelProfiles,
                    shortsVector = newShortsVector,
                    topicEvidence = newTopicEvidence,
                    tagAffinities = newTagAffinities,
                    rejectionPatterns = newRejectionPatterns,
                    channelMemory =
                        if (interactionType == InteractionType.DISLIKED) {
                            ChannelMemory.recordRejection(
                                currentUserBrain.channelMemory,
                                video.channelId,
                                video.channelName,
                                System.currentTimeMillis(),
                            )
                        } else {
                            currentUserBrain.channelMemory
                        },
                )

            scheduleDebouncedSave()
        }
    }

    /** Fire-and-forget interaction on the engine's own scope (survives ViewModel teardown). */
    fun onVideoInteractionAsync(
        video: Video,
        interactionType: InteractionType,
        percentWatched: Float = 0f,
    ) {
        saveScope.launch {
            try {
                onVideoInteraction(video, interactionType, percentWatched)
            } catch (e: Exception) {
                Log.w(TAG, "Async interaction dispatch failed for ${video.id}", e)
            }
        }
    }

    /**
     * Live subscription changes are strong channel-intent signals. Subscribing lifts
     * the channel's quality floor and clears any inferred suppression; unsubscribing
     * caps it back to neutral (it is not a dislike).
     */
    suspend fun onChannelSubscriptionChanged(
        channelId: String,
        channelName: String,
        subscribed: Boolean,
    ) {
        if (channelId.isBlank()) return
        withBrainLock {
            val scores = currentUserBrain.channelScores.toMutableMap()
            val current = scores[channelId] ?: 0.5
            if (subscribed) {
                scores[channelId] = maxOf(current, 0.65)
                val nameTokens = tokenizer.tokenizeChannelName(channelName)
                val newGlobal =
                    if (nameTokens.isNotEmpty()) {
                        NeuroVectorMath.learn(
                            currentUserBrain.globalVector,
                            ContentVector(topics = nameTokens.associateWith { 0.5 }),
                            SUBSCRIPTION_RATE / NeuroVectorMath.DECAY_REFERENCE_RATE,
                            NeuroVectorMath.LONG_TERM,
                            SUBSCRIPTION_RATE,
                            pickKeys(currentUserBrain),
                        )
                    } else {
                        currentUserBrain.globalVector
                    }
                currentUserBrain =
                    currentUserBrain.copy(
                        channelScores = scores,
                        suppressedChannels = currentUserBrain.suppressedChannels - channelId,
                        globalVector = newGlobal,
                    )
            } else {
                scores[channelId] = minOf(current, 0.5)
                currentUserBrain = currentUserBrain.copy(channelScores = scores)
            }
            scheduleDebouncedSave()
        }
    }

    /**
     * A typed search is the most explicit interest statement in the product.
     * Records explicit topic evidence and plants the query's phrase-level topics
     * (see NeuroSearchLearning) without counting as a full interaction.
     */
    suspend fun onSearchQuery(rawQuery: String) {
        if (rawQuery.isBlank() || isLearningPaused()) return
        withBrainLock {
            val learned =
                NeuroSearchLearning.learn(currentUserBrain, rawQuery, tokenizer, System.currentTimeMillis())
                    ?: return
            val spellings = NeuroDisplayForms.spellingsIn(rawQuery, learned.globalVector.topics.keys, tokenizer)
            currentUserBrain =
                learned.copy(
                    topicEvidence = capEvidence(learned.topicEvidence.toMutableMap()),
                    displayForms = NeuroDisplayForms.fillGaps(learned.displayForms, spellings) { it in learned.globalVector.topics },
                )
            scheduleDebouncedSave()
        }
    }

    // =================================================
    // FEATURE EXTRACTION (delegates to NeuroTokenizer)
    // =================================================

    private fun getOrExtractFeatures(
        video: Video,
        idfSnapshot: IdfSnapshot,
        channelProfile: Map<String, Double>? = null,
    ): ContentVector {
        val cacheKey = video.id
        val hasTags = video.tags.isNotEmpty()
        if (!hasTags) {
            synchronized(featureCache) {
                featureCache[cacheKey]?.let { return it }
            }
        }
        var vector = tokenizer.extractFeatures(video, idfSnapshot, channelProfile)
        if (!hasTags) {
            // A bare-title candidate we have watched before: score it on the
            // tag-rich vector stored at watch time instead of its title alone.
            contentStore.topicsFor(video.id)?.let { storedTopics ->
                vector = vector.copy(topics = storedTopics)
            }
            synchronized(featureCache) {
                featureCache[cacheKey] = vector
            }
        }
        return vector
    }

    /** The strongest topics of [video], read without learning anything from it. */
    suspend fun topTopicsFor(
        video: Video,
        limit: Int,
    ): List<String> =
        getOrExtractFeatures(video, takeIdfSnapshotSafe())
            .topics.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }

    private fun takeIdfSnapshot(): IdfSnapshot =
        IdfSnapshot(
            wordFrequency = idfWordFrequency.toMap(),
            totalDocs = idfTotalDocuments,
        )

    private suspend fun takeIdfSnapshotSafe(): IdfSnapshot = withBrainLock { takeIdfSnapshot() }

    // =================================================
    // SEEN SHORTS
    // =================================================

    suspend fun recordSeenShorts(shortIds: List<String>) {
        if (shortIds.isEmpty()) return
        val deepFlow = isLearningPaused()
        withBrainLock {
            syncBookkeeping(deepFlow)
            val now = System.currentTimeMillis()
            val updated = currentUserBrain.seenShortsHistory.toMutableMap()
            // Every sighting refreshes the stamp, so the seven-day window runs from the last time on screen.
            shortIds.forEach { id -> updated[id] = now }
            if (updated.size > NeuroScoring.SEEN_SHORTS_MAX) {
                val toRemove =
                    updated.entries
                        .sortedBy { it.value }
                        .take(updated.size - NeuroScoring.SEEN_SHORTS_MAX)
                toRemove.forEach { updated.remove(it.key) }
            }
            currentUserBrain = currentUserBrain.copy(seenShortsHistory = updated)
            scheduleDebouncedSave(bookkeeping = true)
        }
    }

    suspend fun getRecentlySeenShorts(): Set<String> =
        withBrainLock {
            val now = System.currentTimeMillis()
            val expiryMs = NeuroScoring.SEEN_SHORT_EXPIRY_DAYS * 24L * 60 * 60 * 1000
            currentUserBrain.seenShortsHistory
                .filter { (_, ts) -> (now - ts) < expiryMs }
                .keys
        }

    // =================================================
    // EXPORT / IMPORT (delegates to NeuroStorage)
    // =================================================

    suspend fun exportBrainToStream(output: OutputStream): Boolean {
        val brainCopy = withBrainLock { deepFlowBookkeeping.persistable(currentUserBrain) }
        return storage.exportToStream(brainCopy, output)
    }

    /** [afterSync] marks a brain merged with peers, whose keys may follow an older key spec. */
    suspend fun importBrainFromStream(
        input: InputStream,
        afterSync: Boolean = false,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val finalBrain =
                    storage.importFromStream(input)
                        ?: return@withContext false

                withBrainLock {
                    // Imported brains may pre-date the current maintenance, so run it.
                    val maintained = runMaintenanceIfNeeded(withLanguageProfile(finalBrain))
                    currentUserBrain = if (afterSync) NeuroKeyMigration.afterSync(maintained, tokenizer) else maintained
                    deepFlowBookkeeping.forget()
                    // From the maintained brain: maintenance re-keys words, and the next event saves this map over it.
                    idfWordFrequency = currentUserBrain.idfWordFrequency.toMutableMap()
                    idfTotalDocuments = currentUserBrain.idfTotalDocuments
                    countedDocuments.clear()
                    watchHistory.clear()
                    finalBrain.watchHistoryMap.forEach { (id, pct) ->
                        watchHistory[id] = WatchEntry(pct, System.currentTimeMillis())
                    }
                    storage.save(deepFlowBookkeeping.persistable(currentUserBrain))
                }
                Log.i(
                    TAG,
                    "Brain imported (${finalBrain.totalInteractions} " +
                        "interactions, ${finalBrain.timeVectors.count {
                            it.value.topics.isNotEmpty()
                        }} active time buckets)",
                )
                true
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
                false
            }
        }

    // =================================================
    // PERSONA ENGINE
    // =================================================

    fun getPersona(brain: UserBrain): FlowPersona {
        if (brain.totalInteractions < 15) return FlowPersona.INITIATE

        val v = brain.globalVector

        val sortedTopics = v.topics.values.sortedDescending()
        val topScore = sortedTopics.firstOrNull() ?: 0.0
        val diversityIndex =
            if (sortedTopics.size >= 5 && topScore > 0) {
                sortedTopics[4] / topScore
            } else {
                0.0
            }

        val musicKeywords =
            setOf(
                "music",
                "song",
                "lyrics",
                "remix",
                "lofi",
                "playlist",
                "official audio",
            )
        val musicScore =
            v.topics.entries
                .filter {
                    musicKeywords.contains(it.key) ||
                        it.key.contains("feat")
                }.sumOf { it.value }
        val totalScore = v.topics.values.sum()

        fun mag(cv: ContentVector) = cv.topics.values.sum()
        val nightMag = (
            mag(
                brain.timeVectors[TimeBucket.WEEKDAY_NIGHT]
                    ?: ContentVector(),
            ) +
                mag(
                    brain.timeVectors[TimeBucket.WEEKEND_NIGHT]
                        ?: ContentVector(),
                )
        )
        val morningMag = (
            mag(
                brain.timeVectors[TimeBucket.WEEKDAY_MORNING]
                    ?: ContentVector(),
            ) +
                mag(
                    brain.timeVectors[TimeBucket.WEEKEND_MORNING]
                        ?: ContentVector(),
                )
        )
        val isNocturnal = nightMag > (morningMag * 1.5) && nightMag > 5.0

        val rawPersona =
            when {
                totalScore > 0 &&
                    musicScore > (totalScore * 0.4) -> FlowPersona.AUDIOPHILE

                v.isLive > 0.6 -> FlowPersona.LIVEWIRE

                isNocturnal -> FlowPersona.NIGHT_OWL

                brain.totalInteractions > 500 &&
                    v.pacing > 0.65 -> FlowPersona.BINGER

                v.complexity > 0.75 -> FlowPersona.SCHOLAR

                v.duration > 0.70 -> FlowPersona.DEEP_DIVER

                v.duration < 0.35 &&
                    v.pacing > 0.60 -> FlowPersona.SKIMMER

                diversityIndex < 0.25 -> FlowPersona.SPECIALIST

                else -> FlowPersona.EXPLORER
            }

        val lastPersona =
            brain.lastPersona?.let { name ->
                FlowPersona.entries.find { it.name == name }
            }

        return if (lastPersona != null &&
            rawPersona != lastPersona &&
            brain.personaStability < NeuroScoring.PERSONA_STABILITY_THRESHOLD
        ) {
            lastPersona
        } else {
            rawPersona
        }
    }
}
