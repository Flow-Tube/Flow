package io.github.aedev.flow.data.lyrics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object LyricsCacheManager {
    private const val TAG = "LyricsCacheManager"
    private const val LYRICS_DIR = "lyrics"
    private const val MANUAL_SUFFIX = ".manual"
    private const val SYNC_OFFSET_SUFFIX = ".offset"
    private const val NO_LYRICS_SUFFIX = ".none"
    private const val UNVERIFIED_MARKER = ".unverified-expired"
    private const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L
    private val lock = Any()

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }

    private fun getCacheFile(
        context: Context,
        videoId: String,
    ): File {
        val dir = File(context.filesDir, LYRICS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "$videoId.json")
    }

    private fun sidecar(
        context: Context,
        videoId: String,
        suffix: String,
    ): File = File(getCacheFile(context, videoId).parentFile, videoId + suffix)

    private fun write(
        context: Context,
        videoId: String,
        entries: List<LyricsEntry>,
    ) {
        try {
            getCacheFile(context, videoId).writeText(json.encodeToString(entries), Charsets.UTF_8)
            sidecar(context, videoId, NO_LYRICS_SUFFIX).delete()
            Log.d(TAG, "Successfully cached lyrics to disk for videoId: $videoId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache lyrics for $videoId to disk: ${e.message}", e)
        }
    }

    /** Saves what a provider found. Lyrics picked by hand stay; returns whether this was saved. */
    suspend fun saveLyrics(
        context: Context,
        videoId: String,
        entries: List<LyricsEntry>,
    ): Boolean {
        if (videoId.isBlank() || entries.isEmpty()) return false

        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (sidecar(context, videoId, MANUAL_SUFFIX).exists()) return@synchronized false
                write(context, videoId, entries)
                true
            }
        }
    }

    /** Saves lyrics the user chose or edited, and marks them so nothing replaces them automatically. */
    suspend fun saveManualLyrics(
        context: Context,
        videoId: String,
        entries: List<LyricsEntry>,
    ) {
        if (videoId.isBlank() || entries.isEmpty()) return

        withContext(Dispatchers.IO) {
            synchronized(lock) {
                write(context, videoId, entries)
                runCatching { sidecar(context, videoId, MANUAL_SUFFIX).createNewFile() }
                    .onFailure { Log.e(TAG, "Failed to mark $videoId as picked by hand: ${it.message}") }
            }
        }
    }

    suspend fun isManual(
        context: Context,
        videoId: String,
    ): Boolean = withContext(Dispatchers.IO) { synchronized(lock) { sidecar(context, videoId, MANUAL_SUFFIX).exists() } }

    suspend fun clearManual(
        context: Context,
        videoId: String,
    ) {
        withContext(Dispatchers.IO) { synchronized(lock) { sidecar(context, videoId, MANUAL_SUFFIX).delete() } }
    }

    suspend fun getLyrics(
        context: Context,
        videoId: String,
    ): List<LyricsEntry>? {
        if (videoId.isBlank()) return null

        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                try {
                    val file = getCacheFile(context, videoId)
                    if (file.exists() && file.length() > 0L) {
                        val jsonString = file.readText(Charsets.UTF_8)
                        val entries = json.decodeFromString<List<LyricsEntry>>(jsonString)
                        Log.d(TAG, "Instant cache hit: loaded lyrics from disk for videoId: $videoId")
                        entries
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read cached lyrics for $videoId: ${e.message}", e)
                    null
                }
            }
        }
    }

    suspend fun evictLyrics(
        context: Context,
        videoId: String,
    ) {
        if (videoId.isBlank()) return

        withContext(Dispatchers.IO) {
            synchronized(lock) {
                try {
                    val file = getCacheFile(context, videoId)
                    if (file.exists()) {
                        file.delete()
                        Log.d(TAG, "Evicted lyrics cache from disk for videoId: $videoId")
                    }
                    Unit
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to evict lyrics cache for $videoId: ${e.message}", e)
                }
            }
        }
    }

    /** Every source answered and none had this song. Never call this after a failure or a timeout. */
    suspend fun rememberNoLyrics(
        context: Context,
        videoId: String,
    ) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                runCatching {
                    sidecar(context, videoId, NO_LYRICS_SUFFIX).apply {
                        createNewFile()
                        setLastModified(System.currentTimeMillis())
                    }
                }.onFailure { Log.e(TAG, "Failed to remember no lyrics for $videoId: ${it.message}") }
            }
        }
    }

    suspend fun remembersNoLyrics(
        context: Context,
        videoId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val file = sidecar(context, videoId, NO_LYRICS_SUFFIX)
                file.exists() && !weekHasPassed(file, nowMs)
            }
        }

    suspend fun forgetNoLyrics(
        context: Context,
        videoId: String,
    ) {
        withContext(Dispatchers.IO) { synchronized(lock) { sidecar(context, videoId, NO_LYRICS_SUFFIX).delete() } }
    }

    /** Kept apart from the lyrics, so a nudge outlives a better answer, another source or Refresh. */
    suspend fun syncOffset(
        context: Context,
        videoId: String,
    ): Long? =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                runCatching {
                    sidecar(context, videoId, SYNC_OFFSET_SUFFIX)
                        .takeIf { it.exists() }
                        ?.readText()
                        ?.trim()
                        ?.toLongOrNull()
                }.getOrNull()
            }
        }

    suspend fun saveSyncOffset(
        context: Context,
        videoId: String,
        offsetMs: Long,
    ) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                runCatching { sidecar(context, videoId, SYNC_OFFSET_SUFFIX).writeText(offsetMs.toString()) }
                    .onFailure { Log.e(TAG, "Failed to save the sync offset for $videoId: ${it.message}") }
            }
        }
    }

    suspend fun clearSyncOffset(
        context: Context,
        videoId: String,
    ) {
        withContext(Dispatchers.IO) { synchronized(lock) { sidecar(context, videoId, SYNC_OFFSET_SUFFIX).delete() } }
    }

    /** Saved lyrics without word sync look for an upgrade at most this often, going by the file's modified time. */
    suspend fun upgradeIsDue(
        context: Context,
        videoId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = withContext(Dispatchers.IO) { synchronized(lock) { weekHasPassed(getCacheFile(context, videoId), nowMs) } }

    suspend fun markUpgradeLookDone(
        context: Context,
        videoId: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        withContext(Dispatchers.IO) { synchronized(lock) { getCacheFile(context, videoId).setLastModified(nowMs) } }
    }

    /**
     * Older versions saved answers nobody had checked, and those used to repair themselves on every
     * play. With the weekly look they would stay, so each one's look comes due on its next play,
     * except hand picks and word-synced files. A look that finds nothing leaves the file alone, so a
     * lyric typed in by hand for a song no source has survives this.
     */
    suspend fun recheckUnverifiedOnce(
        context: Context,
        keep: (List<LyricsEntry>) -> Boolean,
    ) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                runCatching {
                    val dir = getCacheFile(context, UNVERIFIED_MARKER).parentFile ?: return@runCatching
                    val marker = File(dir, UNVERIFIED_MARKER)
                    if (marker.exists()) return@runCatching
                    dir.listFiles { file -> file.extension == "json" }.orEmpty().forEach { file ->
                        if (sidecar(context, file.nameWithoutExtension, MANUAL_SUFFIX).exists()) return@forEach
                        val entries = runCatching { json.decodeFromString<List<LyricsEntry>>(file.readText(Charsets.UTF_8)) }.getOrNull()
                        if (entries == null || !keep(entries)) file.setLastModified(0L)
                    }
                    marker.createNewFile()
                }.onFailure { Log.e(TAG, "Failed to expire old lyrics: ${it.message}") }
            }
        }
    }

    /** Saved by an older version and not looked at since: [recheckUnverifiedOnce] left its time at zero. */
    suspend fun isUnverified(
        context: Context,
        videoId: String,
    ): Boolean = withContext(Dispatchers.IO) { synchronized(lock) { getCacheFile(context, videoId).lastModified() == 0L } }

    private fun weekHasPassed(
        file: File,
        nowMs: Long,
    ): Boolean = nowMs - file.lastModified() >= WEEK_MS

    suspend fun cacheSizeBytes(context: Context): Long =
        withContext(Dispatchers.IO) {
            File(context.filesDir, LYRICS_DIR).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        }

    suspend fun clearAllCache(context: Context) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                try {
                    val dir = File(context.filesDir, LYRICS_DIR)
                    if (dir.exists()) {
                        dir.deleteRecursively()
                        Log.d(TAG, "Cleared all local disk lyrics caches successfully")
                    }
                    Unit
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear all disk lyrics caches: ${e.message}", e)
                }
            }
        }
    }
}
