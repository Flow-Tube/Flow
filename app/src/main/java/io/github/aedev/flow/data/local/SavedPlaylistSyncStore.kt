package io.github.aedev.flow.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.profile.StoreScope
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.savedPlaylistSyncDataStore: DataStore<Preferences> by
    safePreferencesDataStore(name = "saved_playlist_sync", scope = StoreScope.PROFILE_LIBRARY)

/** The last time a saved playlist was checked against its YouTube original, and what its header said then. */
data class SavedPlaylistCheck(
    val checkedAtMillis: Long,
    val headerCount: Int?,
    val ownerName: String?,
)

/** When each saved playlist was last checked against its YouTube original. */
@Singleton
class SavedPlaylistSyncStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val dataStore = context.applicationContext.savedPlaylistSyncDataStore

        suspend fun syncedAt(playlistId: String): Long? = dataStore.data.first()[key(playlistId)]

        suspend fun markSynced(
            playlistId: String,
            atMillis: Long,
        ) {
            dataStore.edit { it[key(playlistId)] = atMillis }
        }

        suspend fun lastCheck(playlistId: String): SavedPlaylistCheck? {
            val prefs = dataStore.data.first()
            val checkedAt = prefs[key(playlistId)] ?: return null
            return SavedPlaylistCheck(checkedAt, prefs[countKey(playlistId)], prefs[ownerKey(playlistId)])
        }

        suspend fun recordCheck(
            playlistId: String,
            check: SavedPlaylistCheck,
        ) {
            dataStore.edit { prefs ->
                prefs[key(playlistId)] = check.checkedAtMillis
                if (check.headerCount != null) prefs[countKey(playlistId)] = check.headerCount else prefs.remove(countKey(playlistId))
                if (check.ownerName != null) prefs[ownerKey(playlistId)] = check.ownerName else prefs.remove(ownerKey(playlistId))
            }
        }

        private fun key(playlistId: String) = longPreferencesKey("synced_at:$playlistId")

        private fun countKey(playlistId: String) = intPreferencesKey("header_count:$playlistId")

        private fun ownerKey(playlistId: String) = stringPreferencesKey("owner:$playlistId")
    }
