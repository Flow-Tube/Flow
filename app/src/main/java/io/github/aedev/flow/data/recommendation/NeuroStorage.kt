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

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import io.github.aedev.flow.data.profile.ProfileFiles
import io.github.aedev.flow.data.profile.StoreScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * All persistence concerns: DataStore, serialization,
 * legacy migration, export/import.
 */
internal class NeuroStorage(
    private val appContext: Context,
) {
    companion object {
        private const val TAG = "FlowNeuroEngine"
        private const val BRAIN_FILENAME = "user_neuro_brain.json"
        private const val BRAIN_DATASTORE_FILE = "flow_neuro_brain_v10.json"
        private val PREVIOUS_DATASTORE_FILES = listOf("v9", "v8", "v7").map { "flow_neuro_brain_$it.json" }
        private const val SCHEMA_VERSION = NeuroMaintenance.TARGET_SCHEMA_VERSION
        private const val PRE_MIGRATION_PREFIX = "neuro_brain_before_v"

        /** The schema of a brain that predates the field, so every maintenance step runs on it. */
        const val LEGACY_SCHEMA_VERSION = 0

        private val LEGACY_ONLY_KEYS = listOf("morning", "afternoon", "evening", "night", "longTerm")

        private val exportJson = Json { encodeDefaults = true }

        /** The schema [json] declares, or [LEGACY_SCHEMA_VERSION] when it has none. */
        internal fun declaredSchema(json: JsonObject): Int = json["schemaVersion"]?.jsonPrimitive?.intOrNull ?: LEGACY_SCHEMA_VERSION

        /**
         * An export from before time buckets, which only the legacy parser reads. Today's format can
         * hold empty time vectors, so having none says nothing about its age (#907).
         */
        internal fun isLegacyFormat(json: JsonObject): Boolean = LEGACY_ONLY_KEYS.any { it in json }
    }

    // ── Serializable models ──

    @Serializable
    data class SerializableVector(
        val topics: Map<String, Double> = emptyMap(),
        val duration: Double = 0.5,
        val pacing: Double = 0.5,
        val complexity: Double = 0.5,
        val isLive: Double = 0.0,
    )

    @Serializable
    data class SerializableFeedEntry(
        val lastShown: Long = 0L,
        val showCount: Int = 0,
    )

    @Serializable
    data class SerializableRejectionSignal(
        val count: Int = 0,
        val lastRejectedAt: Long = 0L,
    )

    @Serializable
    data class SerializableTopicEvidence(
        val positiveSignals: Int = 0,
        val watchSignals: Int = 0,
        val explicitSignals: Int = 0,
        val positiveScore: Double = 0.0,
        val videoIds: Set<String> = emptySet(),
        val channelIds: Set<String> = emptySet(),
        val firstSeenAt: Long = 0L,
        val lastSeenAt: Long = 0L,
    )

    @Serializable
    data class SerializableBrain(
        val schemaVersion: Int = SCHEMA_VERSION,
        val timeVectors: Map<String, SerializableVector> = emptyMap(),
        val global: SerializableVector = SerializableVector(),
        val channelScores: Map<String, Double> = emptyMap(),
        val topicAffinities: Map<String, Double> = emptyMap(),
        val interactions: Int = 0,
        val consecutiveSkips: Int = 0,
        val blockedTopics: Set<String> = emptySet(),
        val blockedChannels: Set<String> = emptySet(),
        val preferredTopics: Set<String> = emptySet(),
        val hasCompletedOnboarding: Boolean = false,
        val lastPersona: String? = null,
        val personaStability: Int = 0,
        val idfWordFrequency: Map<String, Int> = emptyMap(),
        val idfTotalDocuments: Int = 0,
        val watchHistoryMap: Map<String, Float> = emptyMap(),
        val seenShortsHistory: Map<String, Long> = emptyMap(),
        val channelTopicProfiles: Map<String, Map<String, Double>> = emptyMap(),
        val shortsVector: SerializableVector = SerializableVector(),
        val recentVector: SerializableVector = SerializableVector(),
        val suppressedVideoIds: Map<String, Long> = emptyMap(),
        val suppressedChannels: Map<String, Long> = emptyMap(),
        val rejectionPatterns: Map<String, SerializableRejectionSignal> = emptyMap(),
        val feedHistory: Map<String, SerializableFeedEntry> = emptyMap(),
        val recentQueryTokens: List<List<String>> = emptyList(),
        val topicEvidence: Map<String, SerializableTopicEvidence> = emptyMap(),
        val recentRelatedSeeds: Map<String, Long> = emptyMap(),
        val recentShortsSeeds: Map<String, Long> = emptyMap(),
        val staleQueries: Map<String, Long> = emptyMap(),
        val clusterRotation: Map<String, Long> = emptyMap(),
        val tagAffinities: Map<String, Double> = emptyMap(),
        val timeBucketCounts: Map<String, Int> = emptyMap(),
        val channelMemory: Map<String, SerializableChannelMemoryEntry> = emptyMap(),
        val channelMemoryClearedAt: Long = 0L,
        val interestChipMasses: Map<String, Double> = emptyMap(),
        val interestChipsComputedAt: Long = 0L,
        val displayForms: Map<String, DisplayForm> = emptyMap(),
        val formatAffinity: Map<String, Double> = emptyMap(),
        val categoryAffinity: Map<String, Double> = emptyMap(),
        val languageProfile: String? = null,
        val languageVotes: Map<String, Int> = emptyMap(),
        val keyLanguage: String? = null,
    )

    @Serializable
    data class SerializableRememberedUpload(
        val videoId: String = "",
        val title: String = "",
        val thumbnailUrl: String = "",
        val durationSec: Int = 0,
        val publishedAt: Long = 0L,
        val uploadDate: String = "",
        val viewCount: Long = 0L,
    )

    @Serializable
    data class SerializableChannelMemoryEntry(
        val name: String = "",
        val lastFetchedAt: Long = 0L,
        val uploads: List<SerializableRememberedUpload> = emptyList(),
        val rejectedAt: Long = 0L,
        val forgottenAt: Long = 0L,
    )

    // ── DataStore setup ──

    private object BrainSerializer : Serializer<SerializableBrain> {
        override val defaultValue: SerializableBrain = SerializableBrain()

        override suspend fun readFrom(input: InputStream): SerializableBrain =
            try {
                val text = input.bufferedReader().readText()
                if (text.isBlank()) {
                    defaultValue
                } else {
                    Json { ignoreUnknownKeys = true }
                        .decodeFromString<SerializableBrain>(text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read brain", e)
                defaultValue
            }

        override suspend fun writeTo(
            t: SerializableBrain,
            output: OutputStream,
        ) {
            output.write(
                Json { encodeDefaults = true }
                    .encodeToString(t)
                    .toByteArray(),
            )
        }
    }

    private val brainDataStore: DataStore<SerializableBrain> by lazy {
        DataStoreFactory.create(
            serializer = BrainSerializer,
            produceFile = ::brainFile,
        )
    }

    private fun brainFile() = ProfileFiles.dataStore(appContext, StoreScope.PROFILE_PRIVATE, BRAIN_DATASTORE_FILE)

    // Legacy formats exist only in Default; elsewhere these resolve inside the profile's own folder and are absent.
    private fun previousBrainFile(fileName: String) = ProfileFiles.dataStore(appContext, StoreScope.DEFAULT_ONLY, fileName)

    // ── Conversion functions ──

    fun ContentVector.toSerializable() =
        SerializableVector(
            topics = topics,
            duration = duration,
            pacing = pacing,
            complexity = complexity,
            isLive = isLive,
        )

    fun FeedEntry.toSerializable() =
        SerializableFeedEntry(
            lastShown = lastShown,
            showCount = showCount,
        )

    fun RejectionSignal.toSerializable() =
        SerializableRejectionSignal(
            count = count,
            lastRejectedAt = lastRejectedAt,
        )

    fun TopicEvidence.toSerializable() =
        SerializableTopicEvidence(
            positiveSignals = positiveSignals,
            watchSignals = watchSignals,
            explicitSignals = explicitSignals,
            positiveScore = positiveScore,
            videoIds = videoIds,
            channelIds = channelIds,
            firstSeenAt = firstSeenAt,
            lastSeenAt = lastSeenAt,
        )

    fun UserBrain.toSerializable() =
        SerializableBrain(
            schemaVersion = schemaVersion,
            timeVectors =
                timeVectors
                    .map { (k, v) ->
                        k.name to v.toSerializable()
                    }.toMap(),
            global = globalVector.toSerializable(),
            channelScores = channelScores,
            topicAffinities = topicAffinities,
            interactions = totalInteractions,
            consecutiveSkips = consecutiveSkips,
            blockedTopics = blockedTopics,
            blockedChannels = blockedChannels,
            preferredTopics = preferredTopics,
            hasCompletedOnboarding = hasCompletedOnboarding,
            lastPersona = lastPersona,
            personaStability = personaStability,
            idfWordFrequency = idfWordFrequency,
            idfTotalDocuments = idfTotalDocuments,
            watchHistoryMap = watchHistoryMap,
            seenShortsHistory = seenShortsHistory,
            channelTopicProfiles = channelTopicProfiles,
            shortsVector = shortsVector.toSerializable(),
            recentVector = recentVector.toSerializable(),
            suppressedVideoIds = suppressedVideoIds,
            suppressedChannels = suppressedChannels,
            rejectionPatterns =
                rejectionPatterns.mapValues { (_, v) ->
                    v.toSerializable()
                },
            feedHistory = feedHistory.mapValues { (_, v) -> v.toSerializable() },
            recentQueryTokens = recentQueryTokens.map { it.toList() },
            topicEvidence = topicEvidence.mapValues { (_, v) -> v.toSerializable() },
            recentRelatedSeeds = recentRelatedSeeds,
            recentShortsSeeds = recentShortsSeeds,
            staleQueries = staleQueries,
            clusterRotation = clusterRotation,
            tagAffinities = tagAffinities,
            timeBucketCounts = timeBucketCounts.mapKeys { it.key.name },
            channelMemory = channelMemory.entries.mapValues { (_, entry) -> entry.toSerializable() },
            channelMemoryClearedAt = channelMemory.clearedAt,
            interestChipMasses = interestChips.masses,
            interestChipsComputedAt = interestChips.computedAt,
            displayForms = displayForms,
            formatAffinity = formatAffinity,
            categoryAffinity = categoryAffinity,
            languageProfile = languageProfile,
            languageVotes = languageVotes,
            keyLanguage = keyLanguage,
        )

    private fun ChannelMemoryEntry.toSerializable() =
        SerializableChannelMemoryEntry(
            name = name,
            lastFetchedAt = lastFetchedAt,
            uploads =
                uploads.map {
                    SerializableRememberedUpload(
                        it.videoId,
                        it.title,
                        it.thumbnailUrl,
                        it.durationSec,
                        it.publishedAt,
                        it.uploadDate,
                        it.viewCount,
                    )
                },
            rejectedAt = rejectedAt,
            forgottenAt = forgottenAt,
        )

    // ── Persistence operations ──

    suspend fun save(brain: UserBrain) =
        withContext(Dispatchers.IO) {
            try {
                brainDataStore.updateData {
                    brain.toSerializable()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save brain", e)
            }
        }

    suspend fun load(): UserBrain? =
        withContext(Dispatchers.IO) {
            try {
                val data = brainDataStore.data.first()
                if (data.hasAnyUserState()) {
                    val brain = data.toUserBrain()
                    Log.i(
                        TAG,
                        "Loaded brain v${data.schemaVersion}, " +
                            "${data.interactions} interactions",
                    )
                    return@withContext brain
                }
                return@withContext null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load brain", e)
                return@withContext null
            }
        }

    // ── Export / Import ──

    private fun SerializableBrain.hasAnyUserState(): Boolean =
        interactions > 0 ||
            hasCompletedOnboarding ||
            preferredTopics.isNotEmpty() ||
            blockedTopics.isNotEmpty() ||
            blockedChannels.isNotEmpty() ||
            channelScores.isNotEmpty() ||
            topicAffinities.isNotEmpty() ||
            idfWordFrequency.isNotEmpty() ||
            idfTotalDocuments > 0 ||
            watchHistoryMap.isNotEmpty() ||
            seenShortsHistory.isNotEmpty() ||
            channelTopicProfiles.isNotEmpty() ||
            shortsVector.topics.isNotEmpty() ||
            suppressedVideoIds.isNotEmpty() ||
            suppressedChannels.isNotEmpty() ||
            rejectionPatterns.isNotEmpty() ||
            feedHistory.isNotEmpty() ||
            recentQueryTokens.isNotEmpty() ||
            topicEvidence.isNotEmpty() ||
            global.topics.isNotEmpty() ||
            timeVectors.any { (_, vector) -> vector.topics.isNotEmpty() }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun exportToStream(
        brain: UserBrain,
        output: OutputStream,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                // Straight into the stream: a string and then its bytes held the whole brain twice.
                exportJson.encodeToStream(brain.toSerializable(), output)
                output.flush()
                Log.i(TAG, "Brain exported")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                false
            }
        }

    suspend fun importFromStream(input: InputStream): UserBrain? =
        withContext(Dispatchers.IO) {
            try {
                val text = input.bufferedReader().readText()
                val jsonParser = Json { ignoreUnknownKeys = true }

                val imported =
                    jsonParser
                        .decodeFromString<SerializableBrain>(text)
                val json = jsonParser.parseToJsonElement(text).jsonObject

                val finalBrain =
                    if (isLegacyFormat(json)) {
                        migrateLegacyBackup(text, imported)
                    } else {
                        imported.toUserBrain().copy(schemaVersion = declaredSchema(json))
                    }

                Log.i(
                    TAG,
                    "Brain imported (${finalBrain.totalInteractions} " +
                        "interactions, ${finalBrain.timeVectors.count {
                            it.value.topics.isNotEmpty()
                        }} active time buckets)",
                )
                finalBrain
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
                null
            }
        }

    // ── Migration ──

    suspend fun migrateLegacy(): UserBrain? =
        withContext(Dispatchers.IO) {
            val legacyFile = ProfileFiles.file(appContext, StoreScope.DEFAULT_ONLY, BRAIN_FILENAME)
            if (legacyFile.exists()) {
                try {
                    Log.i(TAG, "Migrating legacy JSON brain...")
                    val text = legacyFile.readText()
                    val migrated =
                        migrateLegacyBackup(
                            text,
                            SerializableBrain(),
                        )
                    Log.i(
                        TAG,
                        "Legacy brain migrated " +
                            "(${migrated.totalInteractions} interactions)",
                    )
                    return@withContext migrated
                } catch (e: Exception) {
                    Log.e(TAG, "Legacy JSON migration failed", e)
                }
            }

            tryMigrateFromPreviousDataStore()
        }

    /**
     * Keeps [brain] as it was before migration [toSchema] for one release, so a migration that went
     * wrong can be undone. noBackupFilesDir never leaves the device.
     */
    suspend fun keepCopyBeforeMigration(
        brain: UserBrain,
        toSchema: Int,
    ) = withContext(Dispatchers.IO) {
        val file = File(ProfileFiles.noBackupRoot(appContext), "$PRE_MIGRATION_PREFIX$toSchema.json")
        if (file.exists()) return@withContext
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(Json { encodeDefaults = true }.encodeToString(brain.toSerializable()))
        }.onFailure { Log.e(TAG, "Could not keep the brain before migration $toSchema", it) }
    }

    /** Removes the copies [keepCopyBeforeMigration] kept: a reset forgets what was learned. */
    suspend fun deleteCopiesBeforeMigration() =
        withContext(Dispatchers.IO) {
            ProfileFiles
                .noBackupRoot(appContext)
                .listFiles { file -> file.name.startsWith(PRE_MIGRATION_PREFIX) }
                ?.forEach { it.delete() }
        }

    /** True once any brain, even an empty one after a reset, has been saved in the current format. */
    suspend fun hasSavedBrain(): Boolean = withContext(Dispatchers.IO) { brainFile().exists() }

    /** Removes the brain files of earlier formats once a current one is saved, so nothing can bring an old brain back. */
    suspend fun deleteOldBrainFiles() =
        withContext(Dispatchers.IO) {
            if (!brainFile().exists()) return@withContext
            (
                listOf(ProfileFiles.file(appContext, StoreScope.DEFAULT_ONLY, BRAIN_FILENAME)) +
                    PREVIOUS_DATASTORE_FILES.map(::previousBrainFile)
            ).filter { it.exists() }
                .forEach { file ->
                    if (file.delete()) Log.i(TAG, "Deleted old brain file ${file.name}")
                }
        }

    fun migrateLegacyBackup(
        rawJson: String,
        partialParse: SerializableBrain,
    ): UserBrain {
        try {
            val jsonObj = JSONObject(rawJson)

            fun parseVector(key: String): ContentVector {
                val obj =
                    jsonObj.optJSONObject(key)
                        ?: return ContentVector()
                return legacyJsonToVector(obj)
            }

            val morningVec = parseVector("morning")
            val afternoonVec = parseVector("afternoon")
            val eveningVec = parseVector("evening")
            val nightVec = parseVector("night")

            val hasLegacyData =
                listOf(
                    morningVec,
                    afternoonVec,
                    eveningVec,
                    nightVec,
                ).any { it.topics.isNotEmpty() }

            val timeVectors: Map<TimeBucket, ContentVector> =
                if (hasLegacyData) {
                    mapOf(
                        TimeBucket.WEEKDAY_MORNING to morningVec,
                        TimeBucket.WEEKEND_MORNING to morningVec,
                        TimeBucket.WEEKDAY_AFTERNOON to afternoonVec,
                        TimeBucket.WEEKEND_AFTERNOON to afternoonVec,
                        TimeBucket.WEEKDAY_EVENING to eveningVec,
                        TimeBucket.WEEKEND_EVENING to eveningVec,
                        TimeBucket.WEEKDAY_NIGHT to nightVec,
                        TimeBucket.WEEKEND_NIGHT to nightVec,
                    )
                } else {
                    val tvObj = jsonObj.optJSONObject("timeVectors")
                    if (tvObj != null) {
                        TimeBucket.entries.associateWith { bucket ->
                            val bucketObj = tvObj.optJSONObject(bucket.name)
                            if (bucketObj != null) {
                                legacyJsonToVector(bucketObj)
                            } else {
                                ContentVector()
                            }
                        }
                    } else {
                        TimeBucket.entries.associateWith { ContentVector() }
                    }
                }

            val globalVec =
                if (partialParse.global.topics.isNotEmpty()) {
                    partialParse.global.toContentVector()
                } else {
                    parseVector("global").let {
                        if (it.topics.isEmpty()) parseVector("longTerm") else it
                    }
                }

            val channelScores = mutableMapOf<String, Double>()
            val scoresObj = jsonObj.optJSONObject("channelScores")
            scoresObj?.keys()?.forEach { key ->
                channelScores[key] = scoresObj.getDouble(key)
            }

            val affinities = mutableMapOf<String, Double>()
            val affObj = jsonObj.optJSONObject("topicAffinities")
            affObj?.keys()?.forEach { key ->
                affinities[key] = affObj.getDouble(key)
            }

            fun loadStringSet(key: String): Set<String> {
                val set = mutableSetOf<String>()
                val arr = jsonObj.optJSONArray(key)
                if (arr != null) {
                    for (i in 0 until arr.length()) set.add(arr.getString(i))
                }
                return set
            }

            val legacyIdfFreq = mutableMapOf<String, Int>()
            val idfObj = jsonObj.optJSONObject("idfWordFrequency")
            idfObj?.keys()?.forEach { key ->
                legacyIdfFreq[key] = idfObj.getInt(key)
            }
            val legacyIdfTotal = jsonObj.optInt("idfTotalDocuments", 0)

            return UserBrain(
                timeVectors = timeVectors,
                globalVector = globalVec,
                channelScores =
                    if (channelScores.isNotEmpty()) {
                        channelScores
                    } else {
                        partialParse.channelScores
                    },
                topicAffinities =
                    if (affinities.isNotEmpty()) {
                        affinities
                    } else {
                        partialParse.topicAffinities
                    },
                totalInteractions =
                    if (partialParse.interactions > 0) {
                        partialParse.interactions
                    } else {
                        jsonObj.optInt("interactions", 0)
                    },
                consecutiveSkips = partialParse.consecutiveSkips,
                blockedTopics =
                    partialParse.blockedTopics.ifEmpty {
                        loadStringSet("blockedTopics")
                    },
                blockedChannels =
                    partialParse.blockedChannels.ifEmpty {
                        loadStringSet("blockedChannels")
                    },
                preferredTopics =
                    partialParse.preferredTopics.ifEmpty {
                        loadStringSet("preferredTopics")
                    },
                hasCompletedOnboarding =
                    partialParse.hasCompletedOnboarding ||
                        jsonObj.optBoolean("hasCompletedOnboarding", false),
                lastPersona = partialParse.lastPersona,
                personaStability = partialParse.personaStability,
                idfWordFrequency =
                    if (legacyIdfFreq.isNotEmpty()) {
                        legacyIdfFreq
                    } else {
                        partialParse.idfWordFrequency
                    },
                idfTotalDocuments =
                    if (legacyIdfTotal > 0) {
                        legacyIdfTotal
                    } else {
                        partialParse.idfTotalDocuments
                    },
                watchHistoryMap = partialParse.watchHistoryMap,
                schemaVersion = LEGACY_SCHEMA_VERSION,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Legacy backup migration failed", e)
            return partialParse.toUserBrain().copy(schemaVersion = LEGACY_SCHEMA_VERSION)
        }
    }

    private suspend fun tryMigrateFromPreviousDataStore(): UserBrain? {
        for (fileName in PREVIOUS_DATASTORE_FILES) {
            try {
                val file = previousBrainFile(fileName)
                if (!file.exists()) continue

                Log.i(TAG, "Found $fileName, migrating...")
                val text = file.readText()
                if (text.isBlank()) continue

                val data =
                    Json { ignoreUnknownKeys = true }
                        .decodeFromString<SerializableBrain>(text)

                if (data.interactions > 0 ||
                    data.hasCompletedOnboarding ||
                    data.preferredTopics.isNotEmpty()
                ) {
                    val brain = data.toUserBrain().copy(schemaVersion = declaredSchema(Json.parseToJsonElement(text).jsonObject))
                    Log.i(
                        TAG,
                        "Migrated $fileName " +
                            "(${data.interactions} interactions)",
                    )
                    return brain
                }
            } catch (e: Exception) {
                Log.e(TAG, "$fileName migration failed", e)
            }
        }
        return null
    }

    fun legacyJsonToVector(jsonObj: JSONObject): ContentVector {
        val topicsMap = mutableMapOf<String, Double>()
        val topicsObj = jsonObj.optJSONObject("topics")
        topicsObj?.keys()?.forEach { key ->
            topicsMap[key] = topicsObj.getDouble(key)
        }
        return ContentVector(
            topics = topicsMap,
            duration = jsonObj.optDouble("duration", 0.5),
            pacing = jsonObj.optDouble("pacing", 0.5),
            complexity = jsonObj.optDouble("complexity", 0.5),
            isLive = jsonObj.optDouble("isLive", 0.0),
        )
    }
}

// ── Reverse conversions (pure, Context-free) ──
// Top-level so offline diagnostics can parse an exported brain without an
// Android Context. The forward (save) direction stays inside NeuroStorage.

internal fun NeuroStorage.SerializableVector.toContentVector() =
    ContentVector(
        topics = topics,
        duration = duration,
        pacing = pacing,
        complexity = complexity,
        isLive = isLive,
    )

internal fun NeuroStorage.SerializableFeedEntry.toFeedEntry() =
    FeedEntry(
        lastShown = lastShown,
        showCount = showCount,
    )

internal fun NeuroStorage.SerializableRejectionSignal.toRejectionSignal() =
    RejectionSignal(
        count = count,
        lastRejectedAt = lastRejectedAt,
    )

internal fun NeuroStorage.SerializableTopicEvidence.toTopicEvidence() =
    TopicEvidence(
        positiveSignals = positiveSignals,
        watchSignals = watchSignals,
        explicitSignals = explicitSignals,
        positiveScore = positiveScore,
        videoIds = videoIds,
        channelIds = channelIds,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
    )

internal fun NeuroStorage.SerializableBrain.toUserBrain(): UserBrain {
    val vectors =
        TimeBucket.entries.associateWith { bucket ->
            val serialized = timeVectors[bucket.name]
            serialized?.toContentVector() ?: ContentVector()
        }
    return UserBrain(
        timeVectors = vectors,
        globalVector = global.toContentVector(),
        channelScores = channelScores,
        topicAffinities = topicAffinities,
        totalInteractions = interactions,
        consecutiveSkips = consecutiveSkips,
        blockedTopics = blockedTopics,
        blockedChannels = blockedChannels,
        preferredTopics = preferredTopics,
        hasCompletedOnboarding = hasCompletedOnboarding,
        lastPersona = lastPersona,
        personaStability = personaStability,
        idfWordFrequency = idfWordFrequency,
        idfTotalDocuments = idfTotalDocuments,
        watchHistoryMap = watchHistoryMap,
        seenShortsHistory = seenShortsHistory,
        channelTopicProfiles = channelTopicProfiles,
        shortsVector = shortsVector.toContentVector(),
        recentVector = recentVector.toContentVector(),
        suppressedVideoIds = suppressedVideoIds,
        suppressedChannels = suppressedChannels,
        rejectionPatterns =
            rejectionPatterns.mapValues { (_, v) ->
                v.toRejectionSignal()
            },
        feedHistory = feedHistory.mapValues { (_, v) -> v.toFeedEntry() },
        recentQueryTokens = recentQueryTokens.map { it.toSet() },
        topicEvidence = topicEvidence.mapValues { (_, v) -> v.toTopicEvidence() },
        recentRelatedSeeds = recentRelatedSeeds,
        recentShortsSeeds = recentShortsSeeds,
        staleQueries = staleQueries,
        clusterRotation = clusterRotation,
        tagAffinities = tagAffinities,
        timeBucketCounts =
            timeBucketCounts
                .mapNotNull { (name, count) ->
                    TimeBucket.entries.firstOrNull { it.name == name }?.let { it to count }
                }.toMap(),
        channelMemory =
            ChannelMemoryState(
                entries =
                    channelMemory.mapValues { (_, entry) ->
                        ChannelMemoryEntry(
                            name = entry.name,
                            lastFetchedAt = entry.lastFetchedAt,
                            uploads =
                                entry.uploads.map {
                                    RememberedUpload(
                                        it.videoId,
                                        it.title,
                                        it.thumbnailUrl,
                                        it.durationSec,
                                        it.publishedAt,
                                        it.uploadDate,
                                        it.viewCount,
                                    )
                                },
                            rejectedAt = entry.rejectedAt,
                            forgottenAt = entry.forgottenAt,
                        )
                    },
                clearedAt = channelMemoryClearedAt,
            ),
        interestChips = InterestChipSet(interestChipMasses, interestChipsComputedAt),
        displayForms = displayForms,
        formatAffinity = formatAffinity,
        categoryAffinity = categoryAffinity,
        languageProfile = languageProfile,
        languageVotes = languageVotes,
        keyLanguage = keyLanguage,
        schemaVersion = schemaVersion,
    )
}
