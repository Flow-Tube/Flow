package io.github.aedev.flow.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.profile.StoreScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.searchDataStore: DataStore<Preferences> by safePreferencesDataStore(
    name = "search_history",
    scope = StoreScope.PROFILE_LIBRARY,
)

data class SearchHistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val query: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: SearchType = SearchType.TEXT,
    /** The filters the search ran with; null on entries saved before filters were kept. */
    val filters: SearchFilter? = null,
)

enum class SearchType {
    TEXT,
    VOICE,
    SUGGESTION,
}

enum class SearchHistoryScope {
    VIDEO,
    MUSIC,
}

data class SearchSuggestion(
    val text: String,
    val type: SuggestionType = SuggestionType.VIDEO,
)

enum class SuggestionType {
    VIDEO,
    CHANNEL,
    PLAYLIST,
    TRENDING,
}

@Singleton
class SearchHistoryRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val gson = Gson()

        companion object {
            private val SEARCH_HISTORY_KEY = stringPreferencesKey("search_history")
            private val MUSIC_SEARCH_HISTORY_KEY = stringPreferencesKey("music_search_history")
            private val SEARCH_HISTORY_ENABLED_KEY = booleanPreferencesKey("search_history_enabled")
            private val SEARCH_SUGGESTIONS_ENABLED_KEY = booleanPreferencesKey("search_suggestions_enabled")
            private val MAX_HISTORY_SIZE_KEY = intPreferencesKey("max_history_size")
            private val AUTO_DELETE_HISTORY_KEY = booleanPreferencesKey("auto_delete_history")
            private val HISTORY_RETENTION_DAYS_KEY = intPreferencesKey("history_retention_days")

            /** The settings [getSettingsBackup] carries. */
            internal val SETTING_NAMES: Set<String> =
                setOf(
                    SEARCH_HISTORY_ENABLED_KEY.name,
                    SEARCH_SUGGESTIONS_ENABLED_KEY.name,
                    AUTO_DELETE_HISTORY_KEY.name,
                    MAX_HISTORY_SIZE_KEY.name,
                    HISTORY_RETENTION_DAYS_KEY.name,
                )

            private const val DEFAULT_MAX_HISTORY_SIZE = 50
            private const val DEFAULT_RETENTION_DAYS = 90

            private fun historyKey(scope: SearchHistoryScope) =
                when (scope) {
                    SearchHistoryScope.VIDEO -> SEARCH_HISTORY_KEY
                    SearchHistoryScope.MUSIC -> MUSIC_SEARCH_HISTORY_KEY
                }
        }

        // Save search query
        suspend fun saveSearchQuery(
            query: String,
            type: SearchType = SearchType.TEXT,
            scope: SearchHistoryScope = SearchHistoryScope.VIDEO,
            filters: SearchFilter? = null,
        ) {
            if (!isSearchHistoryEnabled()) return
            if (query.isBlank()) return

            context.searchDataStore.edit { preferences ->
                val updated =
                    getSearchHistoryList(preferences, scope).withSearch(
                        query = query,
                        type = type,
                        filters = filters,
                        maxSize = preferences[MAX_HISTORY_SIZE_KEY] ?: DEFAULT_MAX_HISTORY_SIZE,
                        now = System.currentTimeMillis(),
                    )
                preferences[historyKey(scope)] = gson.toJson(updated)
            }
        }

        // Get search history as Flow
        fun getSearchHistoryFlow(scope: SearchHistoryScope = SearchHistoryScope.VIDEO): Flow<List<SearchHistoryItem>> =
            context.searchDataStore.data.map { preferences ->
                if (preferences[SEARCH_HISTORY_ENABLED_KEY] != false) {
                    val history = getSearchHistoryList(preferences, scope)
                    filterExpiredHistory(history, preferences)
                } else {
                    emptyList()
                }
            }

        // Get recent searches (limit)
        suspend fun getRecentSearches(limit: Int = 10): List<SearchHistoryItem> = getSearchHistoryFlow().first().take(limit)

        // Delete specific search item
        suspend fun deleteSearchItem(
            itemId: String,
            scope: SearchHistoryScope = SearchHistoryScope.VIDEO,
        ) {
            context.searchDataStore.edit { preferences ->
                val currentHistory = getSearchHistoryList(preferences, scope)
                val updatedHistory = currentHistory.filter { it.id != itemId }
                preferences[historyKey(scope)] = gson.toJson(updatedHistory)
            }
        }

        suspend fun clearSearchHistory(scope: SearchHistoryScope) {
            context.searchDataStore.edit { preferences ->
                preferences[historyKey(scope)] = gson.toJson(emptyList<SearchHistoryItem>())
            }
        }

        suspend fun clearSearchHistory() {
            context.searchDataStore.edit { preferences ->
                SearchHistoryScope.entries.forEach { scope ->
                    preferences[historyKey(scope)] = gson.toJson(emptyList<SearchHistoryItem>())
                }
            }
        }

        suspend fun replaceSearchHistory(
            items: List<SearchHistoryItem>,
            scope: SearchHistoryScope = SearchHistoryScope.VIDEO,
        ) {
            context.searchDataStore.edit { preferences ->
                val maxSize = preferences[MAX_HISTORY_SIZE_KEY] ?: DEFAULT_MAX_HISTORY_SIZE
                preferences[historyKey(scope)] = gson.toJson(items.newestUnique(maxSize))
            }
        }

        /**
         * Adds searches from another app's export to the ones kept, newest first, each query once and
         * no more than the viewer's limit. Returns how many of [items] are kept; nothing is added while
         * search history is off, and searches older than an auto-delete window are left out.
         */
        suspend fun importSearches(
            items: List<SearchHistoryItem>,
            scope: SearchHistoryScope,
        ): Int {
            if (items.isEmpty() || !isSearchHistoryEnabled()) return 0
            val importedIds = items.mapTo(HashSet()) { it.id }
            var kept = 0
            context.searchDataStore.edit { preferences ->
                val maxSize = preferences[MAX_HISTORY_SIZE_KEY] ?: DEFAULT_MAX_HISTORY_SIZE
                val merged = filterExpiredHistory(getSearchHistoryList(preferences, scope) + items, preferences).newestUnique(maxSize)
                kept = merged.count { it.id in importedIds }
                preferences[historyKey(scope)] = gson.toJson(merged)
            }
            return kept
        }

        private fun List<SearchHistoryItem>.newestUnique(maxSize: Int): List<SearchHistoryItem> =
            asSequence()
                .filter { it.query.isNotBlank() }
                .sortedByDescending { it.timestamp }
                .distinctBy { it.query.searchHistoryKey() }
                .take(maxSize)
                .toList()

        // Settings: Enable/disable search history
        suspend fun setSearchHistoryEnabled(enabled: Boolean) {
            context.searchDataStore.edit { preferences ->
                preferences[SEARCH_HISTORY_ENABLED_KEY] = enabled
            }
        }

        fun isSearchHistoryEnabledFlow(): Flow<Boolean> =
            context.searchDataStore.data.map { preferences ->
                preferences[SEARCH_HISTORY_ENABLED_KEY] ?: true
            }

        suspend fun isSearchHistoryEnabled(): Boolean = isSearchHistoryEnabledFlow().first()

        // Settings: Enable/disable search suggestions
        suspend fun setSearchSuggestionsEnabled(enabled: Boolean) {
            context.searchDataStore.edit { preferences ->
                preferences[SEARCH_SUGGESTIONS_ENABLED_KEY] = enabled
            }
        }

        fun isSearchSuggestionsEnabledFlow(): Flow<Boolean> =
            context.searchDataStore.data.map { preferences ->
                preferences[SEARCH_SUGGESTIONS_ENABLED_KEY] ?: true
            }

        suspend fun isSearchSuggestionsEnabled(): Boolean = isSearchSuggestionsEnabledFlow().first()

        // Settings: Max history size
        suspend fun setMaxHistorySize(size: Int) {
            context.searchDataStore.edit { preferences ->
                preferences[MAX_HISTORY_SIZE_KEY] = size

                SearchHistoryScope.entries.forEach { scope ->
                    val currentHistory = getSearchHistoryList(preferences, scope)
                    if (currentHistory.size > size) {
                        preferences[historyKey(scope)] = gson.toJson(currentHistory.take(size))
                    }
                }
            }
        }

        fun getMaxHistorySizeFlow(): Flow<Int> =
            context.searchDataStore.data.map { preferences ->
                preferences[MAX_HISTORY_SIZE_KEY] ?: DEFAULT_MAX_HISTORY_SIZE
            }

        // Settings: Auto-delete history
        suspend fun setAutoDeleteHistory(enabled: Boolean) {
            context.searchDataStore.edit { preferences ->
                preferences[AUTO_DELETE_HISTORY_KEY] = enabled
            }
        }

        fun isAutoDeleteHistoryEnabledFlow(): Flow<Boolean> =
            context.searchDataStore.data.map { preferences ->
                preferences[AUTO_DELETE_HISTORY_KEY] ?: false
            }

        // Settings: History retention days
        suspend fun setHistoryRetentionDays(days: Int) {
            context.searchDataStore.edit { preferences ->
                preferences[HISTORY_RETENTION_DAYS_KEY] = days
            }
        }

        fun getHistoryRetentionDaysFlow(): Flow<Int> =
            context.searchDataStore.data.map { preferences ->
                preferences[HISTORY_RETENTION_DAYS_KEY] ?: DEFAULT_RETENTION_DAYS
            }

        suspend fun getSettingsBackup(): SettingsBackup {
            val preferences = context.searchDataStore.data.first()
            return SettingsBackup(
                booleans =
                    mapOf(
                        SEARCH_HISTORY_ENABLED_KEY.name to (preferences[SEARCH_HISTORY_ENABLED_KEY] ?: true),
                        SEARCH_SUGGESTIONS_ENABLED_KEY.name to (preferences[SEARCH_SUGGESTIONS_ENABLED_KEY] ?: true),
                        AUTO_DELETE_HISTORY_KEY.name to (preferences[AUTO_DELETE_HISTORY_KEY] ?: false),
                    ),
                ints =
                    mapOf(
                        MAX_HISTORY_SIZE_KEY.name to (preferences[MAX_HISTORY_SIZE_KEY] ?: DEFAULT_MAX_HISTORY_SIZE),
                        HISTORY_RETENTION_DAYS_KEY.name to (preferences[HISTORY_RETENTION_DAYS_KEY] ?: DEFAULT_RETENTION_DAYS),
                    ),
            )
        }

        suspend fun restoreSettings(backup: SettingsBackup) {
            context.searchDataStore.edit { preferences ->
                backup.booleans[SEARCH_HISTORY_ENABLED_KEY.name]?.let { preferences[SEARCH_HISTORY_ENABLED_KEY] = it }
                backup.booleans[SEARCH_SUGGESTIONS_ENABLED_KEY.name]?.let { preferences[SEARCH_SUGGESTIONS_ENABLED_KEY] = it }
                backup.booleans[AUTO_DELETE_HISTORY_KEY.name]?.let { preferences[AUTO_DELETE_HISTORY_KEY] = it }
                backup.ints[MAX_HISTORY_SIZE_KEY.name]?.let { preferences[MAX_HISTORY_SIZE_KEY] = it }
                backup.ints[HISTORY_RETENTION_DAYS_KEY.name]?.let { preferences[HISTORY_RETENTION_DAYS_KEY] = it }
            }
        }

        // Helper: Parse JSON to list
        private fun getSearchHistoryList(
            preferences: Preferences,
            scope: SearchHistoryScope,
        ): List<SearchHistoryItem> {
            val json = preferences[historyKey(scope)] ?: return emptyList()
            return try {
                val type = object : TypeToken<List<SearchHistoryItem>>() {}.type
                gson.fromJson<List<SearchHistoryItem>>(json, type)?.map(SearchHistoryItem::sanitized) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        // Helper: Filter expired history
        private fun filterExpiredHistory(
            history: List<SearchHistoryItem>,
            preferences: Preferences,
        ): List<SearchHistoryItem> {
            val autoDelete = preferences[AUTO_DELETE_HISTORY_KEY] ?: false
            if (!autoDelete) return history

            val retentionDays = preferences[HISTORY_RETENTION_DAYS_KEY] ?: DEFAULT_RETENTION_DAYS
            val cutoffTime = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)

            return history.filter { it.timestamp >= cutoffTime }
        }

        // Get search suggestions from YouTube API (now handled by YouTubeRepository)
        // This method is kept for backward compatibility but deprecated
        @Deprecated("Use YouTubeRepository.getSearchSuggestions() instead")
        fun getSearchSuggestions(query: String): List<SearchSuggestion> {
            if (query.isBlank()) return emptyList()

            // Return empty list - actual suggestions should come from YouTubeRepository
            return emptyList()
        }
    }
