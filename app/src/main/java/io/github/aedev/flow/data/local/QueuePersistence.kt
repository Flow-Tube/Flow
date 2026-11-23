package io.github.aedev.flow.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.music.model.withTypedArtists
import io.github.aedev.flow.data.profile.ProfileFiles
import io.github.aedev.flow.data.profile.StoreScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val QUEUE_STORE_NAME = "music_queue"

private val Context.queueDataStore: DataStore<Preferences> by safePreferencesDataStore(
    name = QUEUE_STORE_NAME,
    scope = StoreScope.PROFILE_PRIVATE,
)

class QueuePersistence private constructor(
    private val context: Context,
) {
    companion object {
        private const val TAG = "QueuePersistence"
        private const val SAVE_DEBOUNCE_MS = 5_000L // Debounce saves to every 5 seconds
        private const val AUTO_SAVE_INTERVAL_MS = 30_000L // Auto-save every 30 seconds

        private val QUEUE_KEY = stringPreferencesKey("queue_json")
        private val CURRENT_INDEX_KEY = intPreferencesKey("current_index")
        private val CURRENT_POSITION_KEY = longPreferencesKey("current_position")
        private val CURRENT_TRACK_ID_KEY = stringPreferencesKey("current_track_id")
        private val SHUFFLE_ENABLED_KEY = stringPreferencesKey("shuffle_enabled")
        private val REPEAT_MODE_KEY = intPreferencesKey("repeat_mode")
        private val SAVED_AT_KEY = longPreferencesKey("saved_at")
        private val AUTOMIX_KEY = stringPreferencesKey("automix_json")

        @Volatile
        private var instance: QueuePersistence? = null

        fun getInstance(context: Context): QueuePersistence =
            instance ?: synchronized(this) {
                instance ?: QueuePersistence(context.applicationContext).also { instance = it }
            }
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val saveMutex = Mutex()

    private var saveJob: Job? = null
    private var autoSaveJob: Job? = null
    private var lastSaveTime = 0L
    private val resumable by lazy {
        ResumableQueueFile(ProfileFiles.preferences(context, StoreScope.PROFILE_PRIVATE, QUEUE_STORE_NAME))
    }

    /**
     * Data class holding all queue state for persistence
     */
    data class QueueState(
        val queue: List<MusicTrack>,
        val currentIndex: Int,
        val currentPosition: Long,
        val currentTrackId: String?,
        val shuffleEnabled: Boolean,
        val repeatMode: Int, // 0=OFF, 1=ALL, 2=ONE
        val savedAt: Long,
        val automix: List<MusicTrack> = emptyList(),
    )

    /**
     * Save queue state with debouncing to prevent excessive disk writes
     */
    fun saveQueueDebounced(
        queue: List<MusicTrack>,
        currentIndex: Int,
        currentPosition: Long,
        currentTrackId: String?,
        shuffleEnabled: Boolean = false,
        repeatMode: Int = 0,
        automix: List<MusicTrack> = emptyList(),
    ) {
        saveJob?.cancel()
        saveJob =
            scope.launch {
                delay(SAVE_DEBOUNCE_MS)
                saveQueueImmediate(queue, currentIndex, currentPosition, currentTrackId, shuffleEnabled, repeatMode, automix)
            }
    }

    /** Drops a debounced save that an immediate save is about to supersede; it would write older state. */
    fun cancelPendingSave() {
        saveJob?.cancel()
    }

    /**
     * Immediately save queue state (use for critical moments like app pause)
     */
    suspend fun saveQueueImmediate(
        queue: List<MusicTrack>,
        currentIndex: Int,
        currentPosition: Long,
        currentTrackId: String?,
        shuffleEnabled: Boolean = false,
        repeatMode: Int = 0,
        automix: List<MusicTrack> = emptyList(),
    ) {
        if (queue.isEmpty()) return

        saveMutex.withLock {
            try {
                val now = System.currentTimeMillis()
                context.queueDataStore.edit { prefs ->
                    prefs[QUEUE_KEY] = gson.toJson(queue)
                    prefs[CURRENT_INDEX_KEY] = currentIndex
                    prefs[CURRENT_POSITION_KEY] = currentPosition
                    prefs[CURRENT_TRACK_ID_KEY] = currentTrackId ?: ""
                    prefs[SHUFFLE_ENABLED_KEY] = shuffleEnabled.toString()
                    prefs[REPEAT_MODE_KEY] = repeatMode
                    prefs[SAVED_AT_KEY] = now
                    prefs[AUTOMIX_KEY] = gson.toJson(automix)
                }
                lastSaveTime = now
                withContext(Dispatchers.IO) { resumable.markReadable() }
                Log.d(TAG, "Queue saved: ${queue.size} tracks, index=$currentIndex, pos=$currentPosition, automix=${automix.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save queue", e)
            }
        }
    }

    /**
     * Restore queue state from persistent storage
     */
    suspend fun restoreQueue(): QueueState? {
        val restored = readQueue()
        withContext(Dispatchers.IO) { if (restored == null) resumable.markUnreadable() else resumable.markReadable() }
        return restored
    }

    private suspend fun readQueue(): QueueState? {
        return try {
            val prefs = context.queueDataStore.data.first()

            val queueJson = prefs[QUEUE_KEY] ?: return null
            val type = object : TypeToken<List<MusicTrack>>() {}.type
            val queue: List<MusicTrack> =
                gson.fromJson<List<MusicTrack>>(queueJson, type)?.map { it.withTypedArtists() } ?: return null

            if (queue.isEmpty()) return null

            val automixJson = prefs[AUTOMIX_KEY]
            val automix: List<MusicTrack> =
                if (!automixJson.isNullOrBlank()) {
                    gson.fromJson<List<MusicTrack>>(automixJson, type)?.map { it.withTypedArtists() } ?: emptyList()
                } else {
                    emptyList()
                }

            val state =
                QueueState(
                    queue = queue,
                    currentIndex = prefs[CURRENT_INDEX_KEY] ?: 0,
                    currentPosition = prefs[CURRENT_POSITION_KEY] ?: 0L,
                    currentTrackId = prefs[CURRENT_TRACK_ID_KEY]?.takeIf { it.isNotBlank() },
                    shuffleEnabled = prefs[SHUFFLE_ENABLED_KEY]?.toBooleanStrictOrNull() ?: false,
                    repeatMode = prefs[REPEAT_MODE_KEY] ?: 0,
                    savedAt = prefs[SAVED_AT_KEY] ?: 0L,
                    automix = automix,
                )

            Log.d(TAG, "Queue restored: ${queue.size} tracks, saved ${(System.currentTimeMillis() - state.savedAt) / 1000}s ago")
            state
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore queue", e)
            null
        }
    }

    /**
     * Start automatic periodic saving
     */
    fun startAutoSave(getQueueState: () -> QueueState?) {
        autoSaveJob?.cancel()
        autoSaveJob =
            scope.launch {
                while (true) {
                    delay(AUTO_SAVE_INTERVAL_MS)
                    getQueueState()?.let { state ->
                        if (state.queue.isNotEmpty()) {
                            saveQueueImmediate(
                                queue = state.queue,
                                currentIndex = state.currentIndex,
                                currentPosition = state.currentPosition,
                                currentTrackId = state.currentTrackId,
                                shuffleEnabled = state.shuffleEnabled,
                                repeatMode = state.repeatMode,
                                automix = state.automix,
                            )
                        }
                    }
                }
            }
    }

    /**
     * Stop automatic saving (call when service/player is stopped)
     */
    fun stopAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
    }

    /** Whether a Play button may resume the saved queue; see [ResumableQueueFile]. */
    fun hasResumableQueue(): Boolean = resumable.canResume()
}
