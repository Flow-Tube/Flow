// ==================================================================================================
// This implementation was based on metrolist's (https://github.com/MetrolistGroup/Metrolist)
// ==================================================================================================

package io.github.aedev.flow.data.lyrics

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.di.MetadataClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
            private const val MAX_TOTAL_TIMEOUT_MS = 25_000L
            private const val PROVIDER_COOLDOWN_MS = 10 * 60 * 1000L

            // After a line-synced hit, the providers after it get this long in total to find word sync.
            private const val WORD_SYNC_GRACE_MS = 4_000L
            private const val MEMORY_CACHE_SIZE = 32
            private const val MEMORY_CACHE_NAME = "MemoryCache"
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
            } catch (e: Exception) {
                Log.w(TAG, "Disk cache evict failed: ${e.message}")
            }
            providerCooldowns.clear()
            Log.d(TAG, "Force refresh requested for $videoId — caches and cooldowns cleared")
        }

        suspend fun getLyrics(
            videoId: String,
            title: String,
            artist: String,
            duration: Int,
            album: String? = null,
            ctx: Context? = null,
        ): Pair<List<LyricsEntry>, String>? =
            try {
                inFlight.join(videoId) { fetchLyrics(videoId, title, artist, duration, album, ctx) }
            } catch (e: CancellationException) {
                // A hand pick or Refresh ended the run under this caller, which itself lives on.
                currentCoroutineContext().ensureActive()
                cache[videoId]?.let { it to MEMORY_CACHE_NAME }
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

            cache[videoId]?.let { cached ->
                val normalized = normalizeEntries(cached)
                if (normalized.none { !it.words.isNullOrEmpty() } || hasWordSync(normalized)) {
                    Log.d(TAG, "Returning in-memory cached lyrics for $videoId")
                    return normalized to MEMORY_CACHE_NAME
                }
                Log.d(TAG, "Ignoring weak in-memory word-sync cache for $videoId")
                cache.remove(videoId)
            }

            var cachedLineFallback: Pair<List<LyricsEntry>, String>? = null
            val diskCached = LyricsCacheManager.getLyrics(targetContext, videoId)
            val normalizedDiskCached = diskCached?.let { normalizeEntries(it.sorted()) }
            if (!normalizedDiskCached.isNullOrEmpty() && LyricsCacheManager.isManual(targetContext, videoId)) {
                cache[videoId] = normalizedDiskCached
                return normalizedDiskCached to "DiskCache"
            }
            if (!normalizedDiskCached.isNullOrEmpty() && hasWordSync(normalizedDiskCached) &&
                hasReasonableTimestamps(normalizedDiskCached, duration)
            ) {
                cache[videoId] = normalizedDiskCached
                return normalizedDiskCached to "DiskCache"
            } else if (!normalizedDiskCached.isNullOrEmpty() && hasReasonableTimestamps(normalizedDiskCached, duration)) {
                cachedLineFallback = stripWordTimings(normalizedDiskCached) to "DiskCache"
                Log.d(TAG, "Disk cache has line/plain lyrics only; trying to upgrade to word-sync")
            } else if (!diskCached.isNullOrEmpty()) {
                Log.d(TAG, "Disk cache has invalid timestamps for $videoId, discarding")
                LyricsCacheManager.evictLyrics(targetContext, videoId)
            }

            val cleanedTitle = LyricsMatching.cleanTitle(title)
            val cleanedArtist = LyricsMatching.cleanArtist(artist)
            Log.d(TAG, "Cache miss. Querying providers: cleanedTitle=\"$cleanedTitle\", cleanedArtist=\"$cleanedArtist\"")

            val orderString = playerPreferences.lyricsProviderOrder.first()
            val enabledStates = playerPreferences.allLyricsProviderEnabledStates().first()
            val orderedProviders =
                registry
                    .getOrderedProviders(orderString)
                    .filter { enabledStates[it.name] != false }

            Log.d(TAG, "Enabled providers in order: ${orderedProviders.joinToString { it.name }}")

            val now = System.currentTimeMillis()

            var unsyncedFallback: Pair<List<LyricsEntry>, String>? = null
            var lineSyncedFallback: Pair<List<LyricsEntry>, String>? = null
            var wordSyncDeadline = Long.MAX_VALUE

            val syncedResult =
                withTimeoutOrNull(MAX_TOTAL_TIMEOUT_MS) {
                    for (provider in orderedProviders) {
                        val cooldownUntil = providerCooldowns[provider.name]
                        if (cooldownUntil != null && cooldownUntil > now) {
                            Log.d(TAG, "Skipping ${provider.name} (auth cooldown for ${(cooldownUntil - now) / 1000}s)")
                            continue
                        }

                        val timeout = minOf(PER_PROVIDER_TIMEOUT_MS, wordSyncDeadline - System.currentTimeMillis())
                        if (timeout <= 0L) break

                        Log.d(TAG, "Trying provider: ${provider.name}")
                        val providerResult =
                            try {
                                callProvider(provider, timeout, videoId, cleanedTitle, cleanedArtist, duration, album)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "${provider.name} threw: ${e.message}")
                                null
                            }

                        if (providerResult != null && providerResult.isSuccess) {
                            var entries = providerResult.getOrNull()
                            if (!entries.isNullOrEmpty()) {
                                entries = LyricsUtils.filterCreditLines(normalizeEntries(entries.sorted()))
                                if (entries.isNotEmpty() && hasReasonableTimestamps(entries, duration)) {
                                    if (entriesAreSynced(entries) && hasWordSync(entries)) {
                                        Log.d(TAG, "Got ${entries.size} WORD-SYNCED lines from ${provider.name} — using these")
                                        return@withTimeoutOrNull entries to provider.name
                                    } else if (entriesAreSynced(entries)) {
                                        if (lineSyncedFallback == null) {
                                            Log.d(
                                                TAG,
                                                "${provider.name} returned LINE-SYNCED lines — keeping them, looking briefly for word sync",
                                            )
                                            lineSyncedFallback = stripWordTimings(entries) to provider.name
                                            wordSyncDeadline = System.currentTimeMillis() + WORD_SYNC_GRACE_MS
                                        }
                                    } else if (unsyncedFallback == null) {
                                        Log.d(
                                            TAG,
                                            "${provider.name} returned ${entries.size} UNSYNCED lines — saving as fallback, continuing for synced",
                                        )
                                        unsyncedFallback = entries to provider.name
                                    } else {
                                        Log.d(TAG, "${provider.name} returned unsynced; already have a fallback, continuing for synced")
                                    }
                                } else if (entries.isNotEmpty()) {
                                    Log.w(TAG, "${provider.name} returned lyrics with unreasonable timestamps, skipping")
                                }
                            }
                        } else {
                            val errorMsg = providerResult?.exceptionOrNull()?.message ?: "timeout or exception"
                            if (isAuthFailure(errorMsg)) {
                                providerCooldowns[provider.name] = now + PROVIDER_COOLDOWN_MS
                                Log.w(TAG, "${provider.name} auth failure ($errorMsg) — cooling down for 10 min")
                            } else {
                                Log.w(TAG, "${provider.name} failed: $errorMsg")
                            }
                        }
                    }
                    null
                } ?: lineSyncedFallback

            if (syncedResult != null) {
                cache[videoId] = syncedResult.first
                try {
                    LyricsCacheManager.saveLyrics(targetContext, videoId, syncedResult.first)
                } catch (e: Exception) {
                    Log.w(TAG, "Disk cache save failed: ${e.message}")
                }
                return syncedResult
            }

            unsyncedFallback?.let { fallback ->
                Log.d(TAG, "No synced lyrics across providers — falling back to unsynced from ${fallback.second}")
                cache[videoId] = fallback.first
                try {
                    LyricsCacheManager.saveLyrics(targetContext, videoId, fallback.first)
                } catch (e: Exception) {
                    Log.w(TAG, "Disk cache save failed: ${e.message}")
                }
                return fallback
            }

            cachedLineFallback?.let {
                cache[videoId] = it.first
                return it
            }

            Log.w(TAG, "No lyrics found for $videoId")
            return null
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

        private fun hasWordSync(entries: List<LyricsEntry>): Boolean {
            val wordLines = entries.filter { !it.words.isNullOrEmpty() }
            if (wordLines.isEmpty()) return false

            val lineCount = entries.size.coerceAtLeast(1)
            val ratio = wordLines.size.toFloat() / lineCount
            if (lineCount > 5 && wordLines.size < 3) return false
            if (lineCount > 10 && ratio < 0.25f) return false

            val validTimingLines =
                wordLines.count { entry ->
                    val words = entry.words.orEmpty().sortedBy { it.startTime }
                    words.isNotEmpty() &&
                        words.last().endTime > words.first().startTime &&
                        words.zipWithNext().all { (current, next) ->
                            current.endTime >= current.startTime && next.startTime >= current.startTime
                        }
                }
            if (validTimingLines < minOf(2, wordLines.size)) return false

            val firstWordMs = wordLines.minOf { it.words!!.first().startTime }
            val lastWordMs = wordLines.maxOf { it.words!!.last().endTime }
            if (lineCount > 5 && lastWordMs - firstWordMs < 10_000L) return false

            return true
        }

        private fun hasReasonableTimestamps(
            entries: List<LyricsEntry>,
            durationSec: Int,
        ): Boolean {
            val timed = entries.filter { it.time > 0 }
            if (timed.size < 2) return true
            val firstMs = timed.first().time
            val lastMs = timed.last().time
            if (lastMs - firstMs < 10_000L) return false
            if (durationSec > 0 && firstMs > durationSec * 1000L + 10_000L) return false
            return true
        }

        private fun isAuthFailure(message: String?): Boolean {
            if (message.isNullOrBlank()) return false
            return message.contains(" 401") || message.contains(" 403") ||
                message.contains("Unauthorized", ignoreCase = true) ||
                message.contains("Forbidden", ignoreCase = true)
        }

        fun entriesAreSynced(entries: List<LyricsEntry>): Boolean {
            if (entries.size < 2) return false
            val main = entries.filter { !it.isBackground }
            val list = if (main.size >= 2) main else entries
            val times = list.map { it.time }.distinct()
            if (times.size < 2) return false
            val firstPositive = times.firstOrNull { it > 0L } ?: return false
            val maxTime = list.maxOf { it.time }
            if (maxTime - firstPositive < 5_000L) return false
            val hasWordTimings = entries.any { !it.words.isNullOrEmpty() }
            if (hasWordTimings) return true
            val distinctTimedLines = list.count { it.time > 0L }
            return distinctTimedLines >= (list.size * 0.5).toInt().coerceAtLeast(2)
        }

        private fun stripWordTimings(entries: List<LyricsEntry>): List<LyricsEntry> = entries.map { entry -> entry.copy(words = null) }

        private fun normalizeEntries(entries: List<LyricsEntry>): List<LyricsEntry> =
            entries.map { entry ->
                entry.copy(
                    text = LyricsUtils.decodeHtmlEntities(entry.text),
                    words =
                        entry.words?.map { word ->
                            word.copy(text = LyricsUtils.decodeHtmlEntities(word.text))
                        },
                )
            }

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
            val cleanedArtist = LyricsMatching.cleanArtist(artist)
            val orderString = playerPreferences.lyricsProviderOrder.first()
            val enabledStates = playerPreferences.allLyricsProviderEnabledStates().first()
            val orderedProviders =
                registry
                    .getOrderedProviders(orderString)
                    .filter { enabledStates[it.name] != false }
            for (provider in orderedProviders) {
                val providerResult =
                    try {
                        callProvider(provider, PER_PROVIDER_TIMEOUT_MS, videoId, cleanedTitle, cleanedArtist, duration, album)
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
