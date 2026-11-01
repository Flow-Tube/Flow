package io.github.aedev.flow.sync.merge

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.safePreferencesDataStore
import io.github.aedev.flow.data.profile.StoreScope
import io.github.aedev.flow.sync.canonical.BrainLwwMaps
import io.github.aedev.flow.sync.canonical.BrainSets
import io.github.aedev.flow.sync.canonical.CanonicalBrain
import io.github.aedev.flow.sync.canonical.CanonicalChannelStrike
import io.github.aedev.flow.sync.canonical.Lww
import io.github.aedev.flow.sync.canonical.OrSet
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.brainCrdtDataStore by safePreferencesDataStore(name = "sync_brain_crdt", scope = StoreScope.PROFILE_PRIVATE)

/**
 * The sync-owned CRDT state that the FlowNeuro brain itself cannot hold, persisted alongside it:
 *
 * - **G-Counter sub-counts** for the additive counters (idf docs/interactions/word-freq). This is
 *   what makes the sums idempotent: each device only ever increments its OWN sub-count (via
 *   [attributeLocal] delta-attribution), so re-syncing the same peer re-maxes its sub-count to the
 *   same value — no double-counting.
 * - **OR-Set stamps** for the blocklists and preferred topics. The brain models these as plain
 *   sets, which cannot express "explicitly unblocked"; [attributeSets] recovers the intent by
 *   diffing the brain's current membership against the last-synced membership, so a member that
 *   disappeared locally becomes a remove tombstone that can actually propagate.
 * - **Wire-only fields** the Android brain has no home for ([watchSignalProgress],
 *   [channelStrikes]). Keeping them here is what lets a third device receive them through the
 *   phone instead of the phone silently erasing them on every round-trip.
 * - **Every device's learned vectors** ([deviceVectors]), which [BrainVectorBlend] averages.
 * - **The LWW values as last merged** ([syncedLww]), so a value that has not changed since keeps
 *   its stamp instead of being restamped on every export and beating a newer edit from a peer.
 */
@Serializable
data class BrainCrdtState(
    val idfDocs: Map<String, Long> = emptyMap(),
    val interactions: Map<String, Long> = emptyMap(),
    val idfWords: Map<String, Map<String, Long>> = emptyMap(),
    val lastIdfDocsScalar: Long = 0,
    val lastInteractionsScalar: Long = 0,
    val lastIdfWordScalars: Map<String, Long> = emptyMap(),
    val sets: BrainSets = BrainSets(),
    val watchSignalProgress: Map<String, Float> = emptyMap(),
    val channelStrikes: Map<String, Lww<CanonicalChannelStrike>> = emptyMap(),
    val deviceVectors: Map<String, DeviceVectors> = emptyMap(),
    val syncedLww: BrainLwwMaps = BrainLwwMaps(),
    /** The key spec and key language the topic-keyed state above is written in; see [BrainKeyRekey]. */
    val keySpec: Int = 1,
    val keyLanguage: String = "",
) {
    companion object {
        /**
         * Fold the local brain's growth since the last update into [myDevice]'s sub-counts.
         * Because sync is the only cross-device path, any change between syncs is local activity.
         */
        fun attributeLocal(
            state: BrainCrdtState,
            myDevice: String,
            idfDocsScalar: Long,
            interactionsScalar: Long,
            idfWordCounts: Map<String, Long>,
        ): BrainCrdtState {
            val docDelta = (idfDocsScalar - state.lastIdfDocsScalar).coerceAtLeast(0)
            val newIdfDocs = state.idfDocs + (myDevice to ((state.idfDocs[myDevice] ?: 0L) + docDelta))
            val intDelta = (interactionsScalar - state.lastInteractionsScalar).coerceAtLeast(0)
            val newInteractions = state.interactions + (myDevice to ((state.interactions[myDevice] ?: 0L) + intDelta))

            val newIdfWords = HashMap(state.idfWords)
            val newLastWords = HashMap(state.lastIdfWordScalars)
            for ((word, count) in idfWordCounts) {
                val delta = (count - (state.lastIdfWordScalars[word] ?: 0L)).coerceAtLeast(0)
                val perDev = HashMap(state.idfWords[word] ?: emptyMap())
                perDev[myDevice] = (perDev[myDevice] ?: 0L) + delta
                newIdfWords[word] = perDev
                newLastWords[word] = count
            }
            return state.copy(
                idfDocs = newIdfDocs,
                interactions = newInteractions,
                idfWords = newIdfWords,
                lastIdfDocsScalar = idfDocsScalar,
                lastInteractionsScalar = interactionsScalar,
                lastIdfWordScalars = newLastWords,
            )
        }

        /**
         * Stamp the local blocklist/preference edits made since the last sync.
         *
         * The brain stores plain sets, so a block and an unblock are indistinguishable from the
         * state alone — the difference only shows up as a *diff* against what we last knew. A
         * member that appeared gets an add stamp; one that vanished gets a remove tombstone, which
         * is the only way "unblocked on the phone" can ever reach the other device.
         *
         * Idempotent: syncing twice with no local edits produces no new stamps.
         */
        fun attributeSets(
            state: BrainCrdtState,
            blockedTopics: Set<String>,
            blockedChannels: Set<String>,
            preferredTopics: Set<String>,
            hlc: String,
        ): BrainCrdtState =
            state.copy(
                sets =
                    BrainSets(
                        blockedTopics = reconcile(state.sets.blockedTopics, blockedTopics, hlc),
                        blockedChannels = reconcile(state.sets.blockedChannels, blockedChannels, hlc),
                        preferredTopics = reconcile(state.sets.preferredTopics, preferredTopics, hlc),
                    ),
            )

        private fun reconcile(
            orSet: OrSet,
            current: Set<String>,
            hlc: String,
        ): OrSet {
            val known = orSet.members()
            if (known == current) return orSet
            var out = orSet
            for (member in current) if (member !in known) out = out.add(member, hlc)
            for (member in known) if (member !in current) out = out.remove(member, hlc)
            return out
        }

        /** After merging with a peer, adopt the merged CRDT state + reset the scalar baselines. */
        fun afterMerge(
            state: BrainCrdtState,
            merged: CanonicalBrain,
            deviceVectors: Map<String, DeviceVectors>,
        ): BrainCrdtState =
            state.copy(
                idfDocs = merged.counters.idfTotalDocuments.perDevice,
                interactions = merged.counters.totalInteractions.perDevice,
                idfWords = merged.idfWordFrequency.mapValues { it.value.perDevice },
                lastIdfDocsScalar = merged.counters.idfTotalDocuments.sum(),
                lastInteractionsScalar = merged.counters.totalInteractions.sum(),
                lastIdfWordScalars = merged.idfWordFrequency.mapValues { it.value.sum() },
                sets = merged.sets,
                watchSignalProgress = merged.perVideo.watchSignalProgress,
                channelStrikes = merged.lwwMaps.channelStrikes,
                deviceVectors = deviceVectors,
                syncedLww = merged.lwwMaps,
            )
    }
}

@Singleton
class BrainCrdtStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val store = context.brainCrdtDataStore
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
        private val key = stringPreferencesKey("state")

        suspend fun load(): BrainCrdtState {
            val raw = store.data.first()[key] ?: return BrainCrdtState()
            return runCatching { json.decodeFromString(BrainCrdtState.serializer(), raw) }
                .getOrDefault(BrainCrdtState())
        }

        suspend fun save(state: BrainCrdtState) {
            store.edit { it[key] = json.encodeToString(BrainCrdtState.serializer(), state) }
        }
    }
