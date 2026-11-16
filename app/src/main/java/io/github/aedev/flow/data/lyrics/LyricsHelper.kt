// ==================================================================================================
// This implementation was based on metrolist's (https://github.com/MetrolistGroup/Metrolist)
// ==================================================================================================

package io.github.aedev.flow.data.lyrics

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.lyrics.LyricsChecks.hasReasonableTimestamps
import io.github.aedev.flow.data.lyrics.LyricsChecks.hasWordSync
import io.github.aedev.flow.data.lyrics.LyricsChecks.normalizeEntries
import io.github.aedev.flow.data.lyrics.LyricsChecks.stripWordTimings
import io.github.aedev.flow.di.MetadataClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A song's synced lyrics as shown on screen; empty until they load. */
data class ShownLyrics(
    val videoId: String,
    val entries: List<LyricsEntry>,
    val syncOffsetMs: Long,
)

@Singleton
class LyricsHelper
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @MetadataClient client: OkHttpClient,
    ) {
        companion object {
            private const val TAG = "LyricsHelper"
            private const val PER_PROVIDER_TIMEOUT_MS = 8_000L

            private const val MEMORY_CACHE_SIZE = 32
            private const val MEMORY_CACHE_NAME = "MemoryCache"
            private const val DISK_CACHE_NAME = "DiskCache"
        }

        // Bounded per call too: a provider's blocking request would otherwise outlive its timeout.
        private val registry =
            LyricsProviderRegistry.create(client.newBuilder().callTimeout(PER_PROVIDER_TIMEOUT_MS, TimeUnit.MILLISECONDS).build())
        private val playerPreferences = PlayerPreferences(context)
        private val cache: MutableMap<String, List<LyricsEntry>> =
            Collections.synchronizedMap(
                object : LinkedHashMap<String, List<LyricsEntry>>(MEMORY_CACHE_SIZE, 0.75f, true) {
                    override fun removeEldestEntry(eldest: Map.Entry<String, List<LyricsEntry>>) = size > MEMORY_CACHE_SIZE
                },
            )

        // The player, the music service and downloads all ask for the track that just started; they
        // share one provider run per video instead of each running the whole chain.
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val inFlight = InFlightRuns<String, Pair<List<LyricsEntry>, String>?>(ioScope)

        private val providerCooldowns = java.util.concurrent.ConcurrentHashMap<String, Long>()

        private val unverifiedCleared =
            ioScope.async(start = CoroutineStart.LAZY) { LyricsCacheManager.recheckUnverifiedOnce(context, LyricsChecks::hasWordSync) }

        // What each running song's run shows now, so a caller joining late still gets it.
        private val runAnswers = MutableStateFlow<Map<String, LyricsAnswer>>(emptyMap())

        private val shownLyrics = MutableStateFlow<ShownLyrics?>(null)

        /** The synced lyrics the player screen shows now, with the offset chosen there. */
        val shown: StateFlow<ShownLyrics?> = shownLyrics.asStateFlow()

        fun publishShown(value: ShownLyrics?) {
            shownLyrics.value = value
        }

        suspend fun forceRefresh(
            videoId: String,
            ctx: Context? = null,
        ) {
            inFlight.cancelAndJoin(videoId)
            cache.remove(videoId)
            try {
                LyricsCacheManager.evictLyrics(ctx ?: context, videoId)
                LyricsCacheManager.clearManual(ctx ?: context, videoId)
                LyricsCacheManager.forgetNoLyrics(ctx ?: context, videoId)
            } catch (e: Exception) {
                Log.w(TAG, "Disk cache evict failed: ${e.message}")
            }
            providerCooldowns.clear()
            Log.d(TAG, "Force refresh requested for $videoId — caches and cooldowns cleared")
        }

        /**
         * The best lyrics a run found, once it ends. A run can last 9 s, so a caller that shows them
         * passes [onAnswer], which gets the first answer as soon as it is ready and any later swap.
         */
        suspend fun getLyrics(
            videoId: String,
            title: String,
            artist: String,
            duration: Int,
            album: String? = null,
            ctx: Context? = null,
            onAnswer: (suspend (Pair<List<LyricsEntry>, String>) -> Unit)? = null,
        ): Pair<List<LyricsEntry>, String>? =
            coroutineScope {
                val watcher =
                    onAnswer?.let { deliver ->
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            runAnswers
                                .map { it[videoId] }
                                .filterNotNull()
                                .distinctUntilChanged()
                                .collect { deliver(it.entries to it.source) }
                        }
                    }
                try {
                    inFlight.join(videoId) { fetchLyrics(videoId, title, artist, duration, album, ctx) }
                } catch (e: CancellationException) {
                    // A hand pick or Refresh ended the run under this caller, which itself lives on.
                    currentCoroutineContext().ensureActive()
                    cache[videoId]?.let { it to MEMORY_CACHE_NAME }
                } finally {
                    watcher?.cancel()
                }
            }

        private suspend fun fetchLyrics(
            videoId: String,
            title: String,
            artist: String,
            duration: Int,
            album: String?,
            ctx: Context?,
        ): Pair<List<LyricsEntry>, String>? {
            val targetContext = ctx ?: context
            unverifiedCleared.await()

            cache[videoId]?.let { cached ->
                val normalized = normalizeEntries(cached)
                if (normalized.none { !it.words.isNullOrEmpty() } || hasWordSync(normalized)) {
                    Log.d(TAG, "Returning in-memory cached lyrics for $videoId")
                    return normalized to MEMORY_CACHE_NAME
                }
                Log.d(TAG, "Ignoring weak in-memory word-sync cache for $videoId")
                cache.remove(videoId)
            }

            var savedCopy: LyricsAnswer? = null
            val saved = LyricsCacheManager.getLyrics(targetContext, videoId)?.let { normalizeEntries(it.sorted()) }
            when {
                saved.isNullOrEmpty() -> {
                    LyricsCacheManager.clearManual(targetContext, videoId)
                }

                LyricsCacheManager.isManual(targetContext, videoId) -> {
                    cache[videoId] = saved
                    return saved to DISK_CACHE_NAME
                }

                !hasReasonableTimestamps(saved, duration) -> {
                    Log.d(TAG, "Disk cache has invalid timestamps for $videoId, discarding")
                    LyricsCacheManager.evictLyrics(targetContext, videoId)
                }

                hasWordSync(saved) -> {
                    cache[videoId] = saved
                    return saved to DISK_CACHE_NAME
                }

                else -> {
                    val copy = stripWordTimings(saved)
                    cache[videoId] = copy
                    if (!LyricsCacheManager.upgradeIsDue(targetContext, videoId)) return copy to DISK_CACHE_NAME
                    Log.d(TAG, "Disk cache has line/plain lyrics only; looking for an upgrade")
                    // An unverified copy may be another song's words, so any synced answer replaces it.
                    val trusted = entriesAreSynced(copy) && !LyricsCacheManager.isUnverified(targetContext, videoId)
                    savedCopy = LyricsAnswer(if (trusted) LyricsKind.LINE else LyricsKind.PLAIN, copy, DISK_CACHE_NAME)
                }
            }

            if (savedCopy == null && LyricsCacheManager.remembersNoLyrics(targetContext, videoId)) {
                Log.d(TAG, "No lyrics for $videoId, as of the last week")
                return null
            }

            val cleanedTitle = LyricsMatching.cleanTitle(title)
            Log.d(TAG, "Querying providers: cleanedTitle=\"$cleanedTitle\", artist=\"$artist\"")

            val orderString = playerPreferences.lyricsProviderOrder.first()
            val enabledStates = playerPreferences.allLyricsProviderEnabledStates().first()
            val now = System.currentTimeMillis()
            val registered = registry.getOrderedProviders(orderString)
            val providers =
                registered
                    .filter { enabledStates[it.name] != false && (providerCooldowns[it.name] ?: 0L) <= now }
                    .associateBy { it.name }
            Log.d(TAG, "Providers in order: ${providers.keys}")

            savedCopy?.let { copy -> runAnswers.update { it + (videoId to copy) } }
            try {
                val result =
                    LyricsProviderRun(
                        sources = providers.keys.toList(),
                        preferred = providers.keys.firstOrNull().orEmpty(),
                        alreadyShown = savedCopy?.kind,
                        ask = { name ->
                            askProvider(providers.getValue(name), videoId, cleanedTitle, artist, duration, album)
                        },
                        show = { keepAnswer(targetContext, videoId, it) },
                    ).run()
                if (savedCopy != null && result.best == null && result.someSourceReplied) {
                    LyricsCacheManager.markUpgradeLookDone(targetContext, videoId)
                }
                if (savedCopy == null && result.everySourceMissed && providers.size == registered.size) {
                    LyricsCacheManager.rememberNoLyrics(targetContext, videoId)
                }
                val best = result.best ?: savedCopy
                if (best == null) Log.w(TAG, "No lyrics found for $videoId")
                return best?.let { it.entries to it.source }
            } finally {
                runAnswers.update { it - videoId }
            }
        }

        private suspend fun keepAnswer(
            targetContext: Context,
            videoId: String,
            answer: LyricsAnswer,
        ): Boolean {
            if (!LyricsCacheManager.saveLyrics(targetContext, videoId, answer.entries)) return false
            cache[videoId] = answer.entries
            runAnswers.update { it + (videoId to answer) }
            Log.d(TAG, "Showing ${answer.kind} lyrics from ${answer.source} for $videoId")
            return true
        }

        private suspend fun askProvider(
            provider: LyricsProvider,
            videoId: String,
            title: String,
            artist: String,
            duration: Int,
            album: String?,
        ): LyricsReply {
            val result =
                try {
                    callProvider(provider, PER_PROVIDER_TIMEOUT_MS, videoId, title, artist, duration, album)
                } catch (e: CancellationException) {
                    // A provider's own timeout is no reason for the run to lose track of it.
                    currentCoroutineContext().ensureActive()
                    null
                } catch (e: Exception) {
                    Log.w(TAG, "${provider.name} threw: ${e.message}")
                    null
                }
            if (result == null) {
                if (provider.restsAfterTimeout) {
                    rest(provider, LyricsChecks.BUSY_REST_MS, "timed out")
                } else {
                    Log.w(TAG, "${provider.name} timed out")
                }
                return LyricsReply.Failed
            }
            val found =
                result.getOrElse { error ->
                    if (error is LyricsNotFoundException) return LyricsReply.Miss
                    val restMs = LyricsChecks.restAfter(error)
                    if (restMs != null) {
                        rest(provider, restMs, error.message)
                    } else {
                        Log.w(TAG, "${provider.name} failed: ${error.message}")
                    }
                    return LyricsReply.Failed
                }
            val entries = LyricsUtils.filterCreditLines(normalizeEntries(found.sorted()))
            if (entries.isEmpty()) return LyricsReply.Miss
            if (!hasReasonableTimestamps(entries, duration)) {
                Log.w(TAG, "${provider.name} returned lyrics with unreasonable timestamps, skipping")
                return LyricsReply.Miss
            }
            return when {
                !entriesAreSynced(entries) -> LyricsReply.Found(LyricsKind.PLAIN, entries)
                hasWordSync(entries) -> LyricsReply.Found(LyricsKind.WORD, entries)
                else -> LyricsReply.Found(LyricsKind.LINE, stripWordTimings(entries))
            }
        }

        private fun rest(
            provider: LyricsProvider,
            restMs: Long,
            reason: String?,
        ) {
            providerCooldowns[provider.name] = System.currentTimeMillis() + restMs
            Log.w(TAG, "${provider.name} skipped for ${restMs / 1000} s: $reason")
        }

        /**
         * Providers make blocking requests that ignore cancellation, so a timeout around the call
         * itself would still wait for the request. Awaiting it as a separate job lets the chain move
         * on at the deadline; the abandoned request ends on the client's call timeout.
         */
        private suspend fun callProvider(
            provider: LyricsProvider,
            timeoutMs: Long,
            videoId: String,
            title: String,
            artist: String,
            duration: Int,
            album: String?,
        ): Result<List<LyricsEntry>>? {
            val call = ioScope.async { provider.getLyrics(videoId, title, artist, duration, album) }
            return try {
                withTimeoutOrNull(timeoutMs) { call.await() }
            } finally {
                call.cancel()
            }
        }

        fun entriesAreSynced(entries: List<LyricsEntry>): Boolean = LyricsChecks.entriesAreSynced(entries)

        /** The offset the user chose for this song, or null if they never nudged it. */
        suspend fun syncOffset(videoId: String): Long? = LyricsCacheManager.syncOffset(context, videoId)

        suspend fun saveSyncOffset(
            videoId: String,
            offsetMs: Long,
        ) = LyricsCacheManager.saveSyncOffset(context, videoId, offsetMs)

        suspend fun clearSyncOffset(videoId: String) = LyricsCacheManager.clearSyncOffset(context, videoId)

        fun clearCache(videoId: String? = null) {
            if (videoId != null) cache.remove(videoId) else cache.clear()
        }

        /**
         * Streams a result from every enabled provider (in the user's configured order) so the UI can
         * offer alternatives when the automatic pick is wrong. Unlike [getLyrics] it never stops at
         * the first synced hit and never touches the caches.
         */
        suspend fun getAllLyrics(
            videoId: String,
            title: String,
            artist: String,
            duration: Int,
            album: String? = null,
            onCandidate: suspend (LyricsCandidate) -> Unit,
        ) {
            val cleanedTitle = LyricsMatching.cleanTitle(title)
            val orderString = playerPreferences.lyricsProviderOrder.first()
            val enabledStates = playerPreferences.allLyricsProviderEnabledStates().first()
            val orderedProviders =
                registry
                    .getOrderedProviders(orderString)
                    .filter { enabledStates[it.name] != false }
            for (provider in orderedProviders) {
                val providerResult =
                    try {
                        callProvider(provider, PER_PROVIDER_TIMEOUT_MS, videoId, cleanedTitle, artist, duration, album)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "${provider.name} threw while browsing: ${e.message}")
                        null
                    }
                val entries = providerResult?.getOrNull()
                if (!entries.isNullOrEmpty()) {
                    val cleaned = LyricsUtils.filterCreditLines(normalizeEntries(entries.sorted()))
                    if (cleaned.isNotEmpty() && hasReasonableTimestamps(cleaned, duration)) {
                        onCandidate(LyricsCandidate(provider.name, cleaned, entriesAreSynced(cleaned)))
                    }
                }
            }
        }

        /**
         * Makes a user-chosen or user-edited set of entries the lyrics of record for [videoId] by
         * overwriting both the in-memory and disk caches, so it survives sheet reopens and restarts.
         * No provider run replaces it until Refresh.
         */
        suspend fun applyManualLyrics(
            videoId: String,
            entries: List<LyricsEntry>,
            ctx: Context? = null,
        ) {
            if (entries.isEmpty()) return
            inFlight.cancelAndJoin(videoId)
            cache[videoId] = entries
            try {
                LyricsCacheManager.saveManualLyrics(ctx ?: context, videoId, entries)
            } catch (e: Exception) {
                Log.w(TAG, "Manual lyrics save failed: ${e.message}")
            }
        }
    }

data class LyricsCandidate(
    val providerName: String,
    val entries: List<LyricsEntry>,
    val synced: Boolean,
)
