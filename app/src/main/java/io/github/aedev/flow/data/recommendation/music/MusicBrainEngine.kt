/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.data.recommendation.music

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.contentfilter.aislist.AiListRepository
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.model.ArtistDetails
import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicPlaylist
import io.github.aedev.flow.data.music.model.MusicTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The music taste engine facade: owns the resident [MusicBrain], serializes all
 * access through one mutex, and debounces persistence. Constructor does no I/O —
 * state loads lazily on first use, never on the app cold-start path.
 *
 * Unlike the video engine's legacy statics, this is plain Hilt constructor
 * injection: no getInstance, no companion forwarding.
 */
@Singleton
class MusicBrainEngine
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val backfill: MusicBrainBackfill,
        private val aiList: AiListRepository,
    ) {
        companion object {
            private const val TAG = "MusicBrainEngine"
            private const val SAVE_DEBOUNCE_MS = 5000L
        }

        private val storage = MusicBrainStorage(appContext)
        private val statsStorage = MusicStatsStorage(appContext)
        private val playerPreferences by lazy { PlayerPreferences(appContext) }

        private val mutex = Mutex()
        private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var pendingSaveJob: Job? = null

        private var brain = MusicBrain()

        /** Monthly listening aggregates for the recap surfaces — device-local, never synced. */
        private var ledger = MusicStatsLedger()
        private var isInitialized = false

        private val _hiddenArtists = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

        /**
         * Blocked + dislike-cooldown artists in every matchable key form, kept
         * current across feedback, unblock, import and sync. Shelf composers
         * combine this so feedback removes an artist from every section at once.
         */
        val hiddenArtists: kotlinx.coroutines.flow.StateFlow<Set<String>> = _hiddenArtists

        /** Must run with the mutex held (or during single-threaded init). */
        private fun refreshHiddenArtistsLocked() {
            _hiddenArtists.value = MusicBrainLearn.hiddenArtistKeys(brain, System.currentTimeMillis())
        }

        /** Previous counted artist + timestamp, for session co-occurrence. Ephemeral, never persisted. */
        private var lastCounted: Pair<String, Long>? = null

        suspend fun ensureInitialized() {
            if (isInitialized) return
            // CPU-bound init (backfill replay) runs on Default so it never occupies
            // the app's limited disk/network dispatcher threads.
            withContext(Dispatchers.Default) {
                mutex.withLock {
                    if (isInitialized) return@withLock
                    brain = storage.load() ?: MusicBrain()
                    ledger = statsStorage.load() ?: MusicStatsLedger()
                    if (!brain.backfilled) {
                        backfill.run(brain, System.currentTimeMillis())
                        brain.backfilled = true
                        storage.save(brain)
                    }
                    isInitialized = true
                    refreshHiddenArtistsLocked()
                    Log.i(TAG, "Initialized: plays=${brain.totalPlays} artists=${brain.artistAffinity.size}")
                }
            }
        }

        /**
         * One completed playback session for [track], with [playedFraction] =
         * actualPlayedTime / duration (wall-clock while playing, so seek-immune).
         * Milestones are crossed once from zero — a relisten is simply a new session.
         */
        suspend fun onListenSession(
            track: MusicTrack,
            playedFraction: Double,
            genre: String? = null,
            playedMs: Long = 0L,
        ) {
            if (track.videoId.isBlank() || LocalMediaIds.isLocal(track.videoId)) return
            val pct = playedFraction.coerceIn(0.0, 1.0)
            if (playerPreferences.isDeepFlowCurrentlyActive()) return
            ensureInitialized()

            val signal =
                track
                    .toMusicSignal(pct)
                    .copy(genre = genre?.trim()?.lowercase()?.takeIf { it.isNotEmpty() })
            if (signal.artistKey.isEmpty()) {
                Log.w(TAG, "listen ${track.videoId} has no artist key")
                return
            }
            // Sub-milestone sessions teach the brain nothing, but their listening
            // time still belongs in the recap ledger's minutes.
            val crossed = MusicBrainLearn.newlyCrossed(0.0, pct)
            val now = System.currentTimeMillis()
            mutex.withLock {
                val wasNewArtist = signal.artistKey !in brain.seenArtists
                var counted = false
                if (crossed.isNotEmpty()) {
                    val coArtist =
                        lastCounted
                            ?.takeIf { now - it.second < MusicBrainParams.SESSION_GAP_MS && it.first != signal.artistKey }
                            ?.first
                    counted = MusicBrainLearn.applyMusicSignal(brain, signal, crossed, now, coArtist)
                    if (counted) lastCounted = signal.artistKey to now
                    Log.i(TAG, "listen ${track.videoId} pct=${"%.2f".format(pct)} counted=$counted artist=${signal.artistKey}")
                } else {
                    Log.d(TAG, "listen ${track.videoId} pct=$pct below first milestone")
                }
                MusicStatsLedgerOps.record(
                    ledger,
                    now,
                    artistKey = signal.artistKey,
                    artistName = signal.artistDisplay,
                    trackId = signal.trackId,
                    trackTitle = signal.title,
                    genre = signal.genre,
                    listenedMs = playedMs,
                    counted = counted,
                    newArtist = counted && wasNewArtist,
                    skipped = crossed.isEmpty(),
                    artworkUrl = track.thumbnailUrl,
                )
            }
            scheduleDebouncedSave()
        }

        /**
         * Fire-and-forget wrapper for callers whose own scope may already be dead
         * (e.g. a Service's lifecycleScope during onDestroy). Runs on the engine's
         * process-scoped scope so teardown-time sessions are never dropped.
         */
        fun onListenSessionAsync(
            track: MusicTrack,
            playedFraction: Double,
            genre: String? = null,
            playedMs: Long = 0L,
        ) {
            saveScope.launch {
                try {
                    onListenSession(track, playedFraction, genre, playedMs)
                } catch (e: Exception) {
                    Log.w(TAG, "Listen session failed: ${e.message}")
                }
            }
        }

        /** An explicit like counts as a full play regardless of progress and floors the score at 0.8. */
        suspend fun onExplicitLike(track: MusicTrack) {
            if (track.videoId.isBlank() || LocalMediaIds.isLocal(track.videoId)) return
            if (playerPreferences.isDeepFlowCurrentlyActive()) return
            ensureInitialized()

            val signal = track.toMusicSignal(0.0).copy(isExplicitLike = true)
            if (signal.artistKey.isEmpty()) return
            val now = System.currentTimeMillis()
            mutex.withLock {
                val wasNewArtist = signal.artistKey !in brain.seenArtists
                val counted = MusicBrainLearn.applyMusicSignal(brain, signal, emptyList(), now, coArtist = null)
                if (counted) {
                    lastCounted = signal.artistKey to now
                    MusicStatsLedgerOps.record(
                        ledger,
                        now,
                        artistKey = signal.artistKey,
                        artistName = signal.artistDisplay,
                        trackId = signal.trackId,
                        trackTitle = signal.title,
                        genre = null,
                        listenedMs = 0L,
                        counted = true,
                        newArtist = wasNewArtist,
                    )
                }
            }
            scheduleDebouncedSave()
        }

        /**
         * Reorder candidates for a surface ("quick_picks", "radio", "similar",
         * "discover", …). A cold brain returns the input order unchanged; blocked
         * artists and tracks the AI filter hides are removed even from single-item lists.
         */
        suspend fun rankTracks(
            candidates: List<MusicTrack>,
            surface: String,
        ): List<MusicTrack> {
            val ai = aiList.currentMatcher()
            val tracks = if (ai.isMusicActive) candidates.filterNot(ai::hides) else candidates
            if (tracks.isEmpty()) return tracks
            ensureInitialized()
            return withContext(Dispatchers.Default) {
                mutex.withLock {
                    if (tracks.size == 1) {
                        val key = tracks[0].primaryArtistKey()
                        if (brain.isArtistBlocked(key)) emptyList() else tracks
                    } else {
                        val inputs = tracks.map { MusicRankInput(trackId = it.videoId, artistKey = it.primaryArtistKey()) }
                        MusicBrainRanker.rank(brain, inputs, surface, System.currentTimeMillis()).map { tracks[it] }
                    }
                }
            }
        }

        /**
         * Order a radio append batch so adjacent tracks never share an artist (when an
         * alternative exists), seeded with the current queue tail so the seam between
         * the queue and the appended batch is covered too. Pure sequencing — candidates
         * are already ranked and block/cooldown-filtered by [rankTracks] on the radio
         * surface. Pass more candidates than [limit] so the spread has real alternatives.
         */
        fun sequenceRadioBatch(
            candidates: List<MusicTrack>,
            previousTrack: MusicTrack?,
            limit: Int,
        ): List<MusicTrack> {
            if (candidates.isEmpty() || limit <= 0) return emptyList()
            val inputs = candidates.map { MusicRankInput(trackId = it.videoId, artistKey = it.primaryArtistKey()) }
            val order =
                MusicBrainRanker.spreadArtists(
                    order = inputs.indices.toList(),
                    inputs = inputs,
                    maxRun = MusicBrainParams.RADIO_MAX_CONSECUTIVE_ARTIST,
                    previousArtist = previousTrack?.primaryArtistKey(),
                )
            return order.take(limit).map { candidates[it] }
        }

        /**
         * Highest-affinity artists with routable browseIds — the seeds for artist
         * and fans-also-like lanes. Blocked artists never surface.
         */
        suspend fun topArtistKeys(limit: Int): List<String> {
            ensureInitialized()
            return mutex.withLock {
                brain
                    .topArtists(limit * 4)
                    .map { it.first }
                    .filter { isIdKeyedArtist(it) && !brain.isArtistBlocked(it) }
                    .take(limit)
            }
        }

        /** Store the "fans also like" edges fetched for [artistKey] — platform knowledge for lanes and mixes. */
        suspend fun recordArtistRelated(
            artistKey: String,
            relatedKeys: List<String>,
        ) {
            ensureInitialized()
            mutex.withLock { MusicBrainLearn.recordArtistRelated(brain, artistKey, relatedKeys) }
            scheduleDebouncedSave()
        }

        /** Co-occurrence-clustered Daily Mix seeds; empty until real multi-artist sessions exist. */
        suspend fun dailyMixes(maxMixes: Int): List<DailyMixSeed> {
            ensureInitialized()
            return mutex.withLock { MusicBrainMixes.dailyMixes(brain, System.currentTimeMillis(), maxMixes) }
        }

        /** The display-ready taste projection for stats/recap surfaces. */
        suspend fun tasteProfile(): MusicTasteProfile {
            ensureInitialized()
            return mutex.withLock { MusicBrainProfile.tasteProfile(brain, System.currentTimeMillis()) }
        }

        /** On Repeat, rendered entirely from local meta — zero network. */
        suspend fun heavyRotationTracks(limit: Int): List<MusicTrack> =
            localShelf(limit) { now, cap -> MusicBrainRanker.heavyRotation(brain, now, cap) }

        /** Loved-but-quiet artists' best tracks — zero network. */
        suspend fun rediscoverTracks(limit: Int): List<MusicTrack> =
            localShelf(limit) { now, cap -> MusicBrainRanker.rediscover(brain, now, cap) }

        /** Tracks the user actually plays at this time of day — zero network. */
        suspend fun timeOfDayTracks(limit: Int): List<MusicTrack> =
            localShelf(limit) { now, cap -> MusicBrainRanker.timeOfDayRotation(brain, now, cap) }

        /**
         * The brain's history with one artist, matched across both key forms.
         * Null when the brain has never counted a play for them.
         */
        suspend fun artistInsights(
            artistId: String?,
            artistName: String,
        ): MusicArtistInsights? {
            ensureInitialized()
            return mutex.withLock {
                val keys =
                    buildSet {
                        add(musicArtistKey(artistId, artistName))
                        artistName
                            .trim()
                            .lowercase()
                            .takeIf { it.isNotEmpty() }
                            ?.let { add(it) }
                    }
                val affinity = keys.mapNotNull { brain.artistAffinity[it] }.maxByOrNull { it.plays }
                if (affinity == null || affinity.plays <= 0) return@withLock null
                val topTracks =
                    brain.trackMeta.entries
                        .filter { it.value.artistKey in keys }
                        .mapNotNull { (id, _) ->
                            brain.trackPlays[id]?.takeIf { it.isNotEmpty() }?.let { Triple(id, it.size, it.max()) }
                        }.sortedWith(
                            compareByDescending<Triple<String, Int, Long>> { it.second }.thenByDescending { it.third },
                        ).take(10)
                        .mapNotNull { trackFromMeta(it.first) }
                MusicArtistInsights(plays = affinity.plays, liked = affinity.liked, topTracks = topTracks)
            }
        }

        /** Read-only copy of the learned genre/mood affinities (lowercased keys). */
        suspend fun genreAffinitySnapshot(): Map<String, Double> {
            ensureInitialized()
            return mutex.withLock { HashMap(brain.genreAffinity) }
        }

        /** Every key form of every artist with counted plays — badge lookups on artist pages. */
        suspend fun listenedArtistKeys(): Set<String> {
            ensureInitialized()
            return mutex.withLock {
                val out = HashSet<String>()
                for ((key, affinity) in brain.artistAffinity) {
                    if (affinity.plays <= 0 || brain.isArtistBlocked(key)) continue
                    out.add(key)
                    affinity.display.takeIf { it.isNotBlank() }?.let { out.add(it.trim().lowercase()) }
                }
                out
            }
        }

        private suspend fun localShelf(
            limit: Int,
            pick: (nowMs: Long, cap: Int) -> List<String>,
        ): List<MusicTrack> {
            ensureInitialized()
            return mutex.withLock {
                pick(System.currentTimeMillis(), limit.coerceIn(1, 100))
                    .mapNotNull { trackId -> trackFromMeta(trackId) }
            }
        }

        /** Must run with the mutex held. */
        private fun trackFromMeta(trackId: String): MusicTrack? {
            val meta = brain.trackMeta[trackId] ?: return null
            return MusicTrack(
                videoId = trackId,
                title = meta.title,
                artist = meta.artist,
                thumbnailUrl = meta.thumbnail,
                duration = 0,
                channelId = if (isIdKeyedArtist(meta.artistKey)) meta.artistKey else "",
                artists =
                    listOf(
                        MusicArtist(
                            name = meta.artist,
                            id = meta.artistKey.takeIf { isIdKeyedArtist(it) },
                        ),
                    ),
            )
        }

        suspend fun dislikeArtist(
            artistId: String?,
            artistName: String,
        ) {
            ensureInitialized()
            mutex.withLock {
                val key = musicArtistKey(artistId, artistName)
                stampDisplayLocked(key, artistName)
                MusicBrainLearn.applyDislike(brain, key, System.currentTimeMillis())
                MusicStatsLedgerOps.recordSaidNo(ledger, System.currentTimeMillis(), key, artistName.trim(), blocked = false)
                refreshHiddenArtistsLocked()
            }
            scheduleDebouncedSave()
        }

        suspend fun blockArtist(
            artistId: String?,
            artistName: String,
        ) {
            ensureInitialized()
            mutex.withLock {
                val key = musicArtistKey(artistId, artistName)
                stampDisplayLocked(key, artistName)
                MusicBrainLearn.blockArtist(brain, key)
                MusicStatsLedgerOps.recordSaidNo(ledger, System.currentTimeMillis(), key, artistName.trim(), blocked = true)
                refreshHiddenArtistsLocked()
            }
            scheduleDebouncedSave()
        }

        /**
         * Feedback on a never-listened artist has no affinity row yet; without a
         * display name an id key could never match name-keyed shelf tracks.
         */
        private fun stampDisplayLocked(
            key: String,
            artistName: String,
        ) {
            if (key.isEmpty() || artistName.isBlank()) return
            val affinity = brain.artistAffinity.getOrPut(key) { MusicAffinity() }
            if (affinity.display.isBlank()) affinity.display = artistName.trim()
        }

        suspend fun setFavouriteArtist(
            artistId: String?,
            artistName: String,
            favourite: Boolean,
        ) {
            ensureInitialized()
            mutex.withLock {
                MusicBrainLearn.setFavouriteArtist(brain, musicArtistKey(artistId, artistName), artistName.trim(), favourite)
                refreshHiddenArtistsLocked()
            }
            scheduleDebouncedSave()
        }

        suspend fun unblockArtist(artistKey: String) {
            ensureInitialized()
            mutex.withLock {
                MusicBrainLearn.unblockArtist(brain, artistKey.trim())
                refreshHiddenArtistsLocked()
            }
            scheduleDebouncedSave()
        }

        suspend fun getBlockedArtists(): List<String> {
            ensureInitialized()
            return mutex.withLock { brain.blockedArtists.sorted() }
        }

        /** Blocked artists as (key, display name) — id keys are opaque, the UI needs names. */
        suspend fun getBlockedArtistsWithNames(): List<Pair<String, String>> {
            ensureInitialized()
            return mutex.withLock {
                brain.blockedArtists
                    .map { key ->
                        val display =
                            brain.artistAffinity[key]?.display?.takeIf { it.isNotBlank() }
                                ?: brain.trackMeta.values
                                    .firstOrNull { it.artistKey == key }
                                    ?.artist
                                ?: key
                        key to display
                    }.sortedBy { it.second.lowercase() }
            }
        }

        suspend fun exportBrainToStream(out: OutputStream) {
            ensureInitialized()
            mutex.withLock { storage.save(brain) }
            storage.exportToStream(out)
        }

        suspend fun importBrainFromStream(input: InputStream) {
            mutex.withLock {
                brain = storage.importFromStream(input)
                lastCounted = null
                isInitialized = true
                refreshHiddenArtistsLocked()
            }
        }

        suspend fun resetBrain() {
            mutex.withLock {
                brain = MusicBrain()
                // Leave backfilled=false so the warm start can run again, matching desktop reset.
                lastCounted = null
                isInitialized = true
                refreshHiddenArtistsLocked()
                storage.save(brain)
            }
        }

        /** Artwork per artist from the tracks the engine remembers, for recap portraits with no network. */
        internal suspend fun artistArtwork(): Map<String, String> {
            ensureInitialized()
            return mutex.withLock {
                brain.trackMeta.values
                    .filter { it.artistKey.isNotEmpty() && it.thumbnail.isNotBlank() }
                    .associate { it.artistKey to it.thumbnail }
            }
        }

        /** Counted plays in one month, without copying the ledger. */
        suspend fun monthPlays(monthKey: String): Int {
            ensureInitialized()
            return mutex.withLock { ledger.months[monthKey]?.plays ?: 0 }
        }

        /** Replaces the listening ledger with a restored one; the brain is untouched. */
        internal suspend fun restoreListeningStats(stats: MusicStatsStorage.SerializableStats) {
            ensureInitialized()
            mutex.withLock {
                ledger = stats.toLedger()
                statsStorage.replace(stats)
            }
        }

        /** Immutable snapshot of the listening ledger for the stats/recap surfaces. */
        internal suspend fun listeningStats(): MusicStatsStorage.SerializableStats {
            ensureInitialized()
            return mutex.withLock { ledger.toSerializable() }
        }

        private fun scheduleDebouncedSave() {
            pendingSaveJob?.cancel()
            pendingSaveJob =
                saveScope.launch {
                    delay(SAVE_DEBOUNCE_MS)
                    saveNow()
                }
        }

        /**
         * Waits for listen sessions still being learned, then writes a pending save now, before a profile switch
         * restarts the process. Sessions are learned first because each one schedules its own save.
         */
        suspend fun awaitWrites() {
            saveScope.coroutineContext.job.children
                .filter { it !== pendingSaveJob }
                .toList()
                .joinAll()
            val pending = pendingSaveJob ?: return
            if (!pending.isActive) return
            pending.cancel()
            saveNow()
        }

        private suspend fun saveNow() =
            mutex.withLock {
                storage.save(brain)
                statsStorage.save(ledger)
            }
    }

/** Primary-artist key for a UI track: browseId when known, lowercased name otherwise. */
internal fun MusicTrack.primaryArtistKey(): String {
    val primary = artists.firstOrNull()
    return musicArtistKey(primary?.id ?: channelId.takeIf { it.isNotBlank() }, primary?.name ?: artist)
}

/**
 * Matches on both key forms because [hidden] carries both: a track's id key when
 * it has one, and always its lowercased display name. EVERY credited artist is
 * tested, not just the primary — a blocked artist's collabs and album cards are
 * still that artist's output to the user.
 */
internal fun MusicTrack.isHiddenArtist(hidden: Set<String>): Boolean {
    if (hidden.isEmpty()) return false
    if (primaryArtistKey() in hidden) return true
    for (credited in artists) {
        if (musicArtistKey(credited.id, credited.name) in hidden) return true
        val creditedName = credited.name.trim().lowercase()
        if (creditedName.isNotEmpty() && creditedName in hidden) return true
    }
    val name = (artists.firstOrNull()?.name ?: artist).trim().lowercase()
    return name.isNotEmpty() && name in hidden
}

/**
 * Feedback filter for album/playlist cards. Their [MusicPlaylist.author] is a
 * display subtitle (album cards carry the release year there), so matching uses
 * the structured [MusicPlaylist.authorId]/[MusicPlaylist.authorName] first and
 * falls back to the subtitle only when it is all we have.
 */
internal fun MusicPlaylist.isHiddenAuthor(hidden: Set<String>): Boolean {
    if (hidden.isEmpty()) return false
    if (musicArtistKey(authorId, authorName ?: author) in hidden) return true
    return listOf(authorName, author).any { !it.isNullOrBlank() && it.trim().lowercase() in hidden }
}

internal fun ArtistDetails.isHiddenArtist(hidden: Set<String>): Boolean {
    if (hidden.isEmpty()) return false
    if (musicArtistKey(channelId.takeIf { it.isNotBlank() }, name) in hidden) return true
    val lowered = name.trim().lowercase()
    return lowered.isNotEmpty() && lowered in hidden
}

internal fun MusicTrack.toMusicSignal(pct: Double): MusicSignal {
    val primary = artists.firstOrNull()
    return MusicSignal(
        trackId = videoId,
        artistKey = primaryArtistKey(),
        artistDisplay = (primary?.name ?: artist).trim(),
        percentPlayed = pct,
        title = title,
        thumbnail = thumbnailUrl,
    )
}
