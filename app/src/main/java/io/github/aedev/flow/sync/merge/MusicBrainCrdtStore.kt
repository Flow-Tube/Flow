package io.github.aedev.flow.sync.merge

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.safePreferencesDataStore
import io.github.aedev.flow.data.profile.StoreScope
import io.github.aedev.flow.sync.canonical.CanonicalMusicBrain
import io.github.aedev.flow.sync.canonical.OrSet
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.musicBrainCrdtDataStore by safePreferencesDataStore(name = "sync_music_brain_crdt", scope = StoreScope.PROFILE_PRIVATE)

/**
 * The sync-owned CRDT state the music brain itself cannot hold (the music twin of
 * [BrainCrdtState]):
 *
 * - **G-Counter sub-counts** for `totalPlays` and per-artist `plays`, grown by
 *   delta-attribution against the last-synced scalars, so re-syncing never
 *   double-counts.
 * - **HLC stamps** for the LWW fields (per-artist `score`, `dislikedArtists`,
 *   `discoveryAppetite`): the brain stores plain values, so a stamp is minted
 *   whenever the value changed since the last sync.
 * - **OR-Set stamps** for `seenArtists`/`blockedArtists`, recovered by diffing the
 *   brain's plain sets against the last-synced membership — a member that vanished
 *   locally becomes a remove tombstone, which is the only way "unblocked on the
 *   phone" can ever reach the desktop.
 */
@Serializable
data class MusicBrainCrdtState(
    val totalPlays: Map<String, Long> = emptyMap(),
    val lastTotalPlaysScalar: Long = 0,
    val artistPlays: Map<String, Map<String, Long>> = emptyMap(),
    val lastArtistPlayScalars: Map<String, Long> = emptyMap(),
    val scoreHlcs: Map<String, String> = emptyMap(),
    val lastScores: Map<String, Double> = emptyMap(),
    val seenArtists: OrSet = OrSet(),
    val blockedArtists: OrSet = OrSet(),
    val dislikedHlcs: Map<String, String> = emptyMap(),
    val lastDisliked: Map<String, Long> = emptyMap(),
    val appetiteHlc: String = "",
    val lastAppetite: Double = -1.0,
) {
    companion object {
        /** Fold local growth since the last sync into [myDevice]'s sub-counts and mint LWW stamps. */
        fun attributeLocal(
            state: MusicBrainCrdtState,
            myDevice: String,
            totalPlaysScalar: Long,
            artistPlayScalars: Map<String, Long>,
            artistScores: Map<String, Double>,
            seenArtists: Set<String>,
            blockedArtists: Set<String>,
            dislikedArtists: Map<String, Long>,
            appetite: Double,
            hlc: String,
        ): MusicBrainCrdtState {
            val totalDelta = (totalPlaysScalar - state.lastTotalPlaysScalar).coerceAtLeast(0)
            val newTotal = state.totalPlays + (myDevice to ((state.totalPlays[myDevice] ?: 0L) + totalDelta))

            val newArtistPlays = HashMap(state.artistPlays)
            val newLastArtistScalars = HashMap(state.lastArtistPlayScalars)
            for ((artist, count) in artistPlayScalars) {
                val delta = (count - (state.lastArtistPlayScalars[artist] ?: 0L)).coerceAtLeast(0)
                if (delta > 0 || artist !in newArtistPlays) {
                    val perDev = HashMap(state.artistPlays[artist] ?: emptyMap())
                    perDev[myDevice] = (perDev[myDevice] ?: 0L) + delta
                    newArtistPlays[artist] = perDev
                }
                newLastArtistScalars[artist] = count
            }

            val newScoreHlcs = HashMap(state.scoreHlcs)
            val newLastScores = HashMap(state.lastScores)
            for ((artist, score) in artistScores) {
                if (state.lastScores[artist] != score) {
                    newScoreHlcs[artist] = hlc
                }
                newLastScores[artist] = score
            }

            val newDislikedHlcs = HashMap(state.dislikedHlcs)
            val newLastDisliked = HashMap(state.lastDisliked)
            for ((artist, at) in dislikedArtists) {
                if (state.lastDisliked[artist] != at) {
                    newDislikedHlcs[artist] = hlc
                }
                newLastDisliked[artist] = at
            }

            val appetiteChanged = state.lastAppetite >= 0.0 && state.lastAppetite != appetite
            return state.copy(
                totalPlays = newTotal,
                lastTotalPlaysScalar = totalPlaysScalar,
                artistPlays = newArtistPlays,
                lastArtistPlayScalars = newLastArtistScalars,
                scoreHlcs = newScoreHlcs,
                lastScores = newLastScores,
                seenArtists = reconcile(state.seenArtists, seenArtists, hlc),
                blockedArtists = reconcile(state.blockedArtists, blockedArtists, hlc),
                dislikedHlcs = newDislikedHlcs,
                lastDisliked = newLastDisliked,
                appetiteHlc = if (appetiteChanged || state.appetiteHlc.isEmpty()) hlc else state.appetiteHlc,
                lastAppetite = appetite,
            )
        }

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
        fun afterMerge(merged: CanonicalMusicBrain): MusicBrainCrdtState =
            MusicBrainCrdtState(
                totalPlays = merged.totalPlays.perDevice,
                lastTotalPlaysScalar = merged.totalPlays.sum(),
                artistPlays = merged.artistAffinity.mapValues { it.value.plays.perDevice },
                lastArtistPlayScalars = merged.artistAffinity.mapValues { it.value.plays.sum() },
                scoreHlcs = merged.artistAffinity.mapValues { it.value.hlc },
                lastScores = merged.artistAffinity.mapValues { it.value.score },
                seenArtists = merged.seenArtists,
                blockedArtists = merged.blockedArtists,
                dislikedHlcs = merged.dislikedArtists.mapValues { it.value.hlc },
                lastDisliked = merged.dislikedArtists.mapValues { it.value.value },
                appetiteHlc = merged.discoveryAppetite?.hlc ?: "",
                lastAppetite = merged.discoveryAppetite?.value ?: -1.0,
            )
    }
}

@Singleton
class MusicBrainCrdtStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val store = context.musicBrainCrdtDataStore
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
        private val key = stringPreferencesKey("state")

        suspend fun load(): MusicBrainCrdtState {
            val raw = store.data.first()[key] ?: return MusicBrainCrdtState()
            return runCatching { json.decodeFromString(MusicBrainCrdtState.serializer(), raw) }
                .getOrDefault(MusicBrainCrdtState())
        }

        suspend fun save(state: MusicBrainCrdtState) {
            store.edit { it[key] = json.encodeToString(MusicBrainCrdtState.serializer(), state) }
        }
    }
